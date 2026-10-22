#!/usr/bin/env python3
"""Mac arm64 local delivery: build, private runtimes/VM, real application, verify/serve, cleanup."""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


native = module('startup_native', 'run-native-stack.py')
PrivateIsolation = module('startup_isolation', 'private-isolation.py').PrivateIsolation
SUM = 'public class Main { public static void main(String[] a) { java.util.Scanner s=new java.util.Scanner(System.in); System.out.print(s.nextInt()+s.nextInt()); }}'


def scoreboard_ready(board):
    # Shared Jackson configuration serializes Long counters as decimal strings.
    return bool(board['entries'] and int(board['entries'][0]['solvedCount']) == 1
                and int(board['projectionPendingCount']) == 0)


def sanitize_proxy_logs(evidence):
    for path in Path(evidence).rglob('*.log'):
        text = path.read_text(errors='replace')
        sanitized = re.sub(r'(https?://)[^/\s\"\']+@', r'\1[REDACTED]@', text, flags=re.I)
        if sanitized != text:
            path.write_text(sanitized)


class Application(native.Stack):
    def __init__(self, runtimes, evidence):
        super().__init__(runtimes, evidence)
        destination = Path(runtimes).resolve().parent / 'application-data'
        self.work.rename(destination)
        self.work = destination
        self.work.chmod(0o700)

    def api(self, path, method='GET', body=None, actor='admin', key=None, allow_error=False):
        status, data = self.http('http://127.0.0.1:' + str(self.ports['gateway']) + '/api' + path,
                                 method, body, self.clients.get(actor), {'Idempotency-Key': key} if key else {})
        if not allow_error and (status >= 400 or data.get('code') != 0):
            message = data.get('message', '')
            facts = {'path': path, 'method': method, 'actor': actor, 'httpStatus': status,
                     'applicationCode': data.get('code'),
                     'messageCode': message if re.fullmatch(r'[A-Z0-9_]{1,128}', str(message)) else 'NON_CODE_MESSAGE'}
            (self.evidence / 'api-failure.json').write_text(json.dumps(facts, indent=2) + '\n')
            raise RuntimeError('STARTUP_API_FAILED')
        return (status, data) if allow_error else data['data']

    def ready(self):
        self.clients = {}
        self.wait('gateway', lambda: self.api('/contests') == [], 45)
        self.event('GATEWAY_BUSINESS_READY')
        self.accounts()  # Real registration/login; promotion only in this newly-created private DB.
        self.event('CURRENT_ACCOUNT_AND_SHARED_SESSION_READY')

    def seed(self):
        now = dt.datetime.now(dt.timezone.utc)
        at = lambda s: (now + dt.timedelta(seconds=s)).isoformat(timespec='microseconds').replace('+00:00', 'Z')
        draft = {'title': 'CodeOJ local startup', 'contestType': 'PRIVATE', 'capacity': 2,
                 'registrationOpenAt': at(5), 'registrationCloseAt': at(20), 'startAt': at(23), 'endAt': at(3600),
                 'problems': [{'problemCode': 'A', 'title': 'Local sum', 'content': 'Read two integers and output their sum.',
                               'limits': {'timeLimit': 2000, 'memoryLimit': 262144},
                               'cases': [{'input': '1 2', 'output': '3'}, {'input': '6 7', 'output': '13'}]}]}
        contest = self.api('/contests', 'POST', draft)
        cid = contest['id']
        self.api('/contests/' + cid + '/publish', 'POST', {'expectedVersion': contest['version']})
        detail = lambda: self.api('/contests/' + cid)
        current = detail()
        self.api('/contests/' + cid + '/registration-admin/prepare', 'POST',
                 {'expectedVersion': current['version'], 'reason': 'Private local startup'})
        current = detail()
        self.api('/contests/' + cid + '/registration-admin/reconcile', 'POST',
                 {'expectedVersion': current['version'], 'reason': 'Initialize private ledger'})
        self.wait('question', lambda: dt.datetime.now(dt.timezone.utc) >= now + dt.timedelta(seconds=5), 10)
        self.api('/contests/' + cid + '/registrations', 'POST', actor='participant', key='startup-registration')
        self.wait('question', lambda: self.api('/contests/' + cid + '/registrations/me', actor='participant')['status'] == 'CONFIRMED', 20)
        self.event('REAL_REDIS_MQ_REGISTRATION_READY', contestId=cid)
        self.wait('question', lambda: dt.datetime.now(dt.timezone.utc) >= now + dt.timedelta(seconds=23), 30)
        current = self.api('/contests/' + cid, actor='participant')
        self.state.update(contestId=cid, snapshotId=current['problems'][0]['id'], publishedVersion=current['publishedVersion'])

    def frontend(self):
        args = ['node', REPO / 'scripts/local/serve-frontend.cjs', self.frontend_dist,
                str(self.ports['frontend']), 'http://127.0.0.1:' + str(self.ports['gateway'])]
        self.spawn('frontend', args)
        self.url = 'http://127.0.0.1:' + str(self.ports['frontend'])
        self.wait('frontend', lambda: urllib.request.urlopen(self.url, timeout=2).status == 200, 15)
        # Use the actual static server's proxy, not only the gateway's direct URL.
        status, body = self.http(self.url + '/api/contests')
        if status != 200 or body.get('code') != 0:
            raise RuntimeError('FRONTEND_PROXY_NOT_READY')
        self.event('FRONTEND_AND_SAME_ORIGIN_API_READY', url=self.url)

    def verify(self):
        def submit(code, key):
            return self.api('/contests/' + self.state['contestId'] + '/submissions', 'POST',
                            {'problemSnapshotId': self.state['snapshotId'], 'publishedVersion': self.state['publishedVersion'],
                             'language': 'java', 'code': code}, 'participant', key)
        rows = []
        for verdict, code in [('AC', SUM), ('WA', SUM.replace('s.nextInt()+s.nextInt()', '0')),
                              ('CE', 'public class Main { invalid java }')]:
            self.event('STARTUP_VERDICT_CHECK_STARTED', verdict=verdict)
            key = 'startup-result-' + verdict
            sid = submit(code, key)
            result = self.wait('judge', lambda: (lambda r: r if r.get('executionStatus') == 'COMPLETED' else None)(
                self.api('/submissions/' + sid, actor='participant')), 60)
            if result['judgeInfo']['verdict'] != verdict or submit(code, key) != sid:
                raise RuntimeError('REAL_ISOLATED_JUDGE_MISMATCH:' + verdict)
            if self.sql('SELECT COUNT(*) FROM judge_attempt WHERE submissionId=' + str(int(sid))) != '1':
                raise RuntimeError('STARTUP_DUPLICATE_ATTEMPT')
            if verdict == 'AC' and result['judgeInfo'].get('memoryMeasurement') != 'CGROUP_V2_PEAK_KIB':
                raise RuntimeError('CGROUP_METRICS_REQUIRED')
            rows.append({'verdict': verdict, 'submissionId': sid, 'attempts': 1})
            self.evidence.joinpath('startup-verdicts.json').write_text(json.dumps(rows, indent=2) + '\n')
            self.event('STARTUP_VERDICT_CHECK_PASSED', verdict=verdict, submissionId=sid)
        board = self.wait('question', lambda: (lambda b: b if scoreboard_ready(b) else None)(
                            self.api('/contests/' + self.state['contestId'] + '/scoreboard', actor='observer')), 20)
        hidden = self.api('/contests/' + self.state['contestId'], actor='observer')
        if hidden['problems']:
            raise RuntimeError('PRIVATE_PROBLEM_EXPOSED')
        self.evidence.joinpath('startup-results.json').write_text(json.dumps({
            'scope': 'Private Mac startup smoke; real dependencies, static discovery, isolated sandbox; not full JV rerun',
            'results': rows, 'scoreboardVersion': board['projectionVersion'], 'pendingProjection': board['projectionPendingCount'],
            'nonparticipantPrivateProblemHidden': True, 'passed': True}, indent=2) + '\n')
        self.event('ISOLATED_JUDGE_AND_SCOREBOARD_READY')
        self.ok = True

    def credentials(self, root):
        path = root / 'access.json'
        path.write_text(json.dumps({'frontend': self.url, 'contestId': self.state['contestId'],
                                   'accounts': {actor: 'fixture_' + actor for actor in self.clients},
                                   'password': self.secret['test']}, indent=2))
        path.chmod(0o600)
        return path

    def cleanup(self):
        if self.procs.get('mysql') is not None and self.procs['mysql'].poll() is None:
            try:
                facts = {'executions': self.sql('SELECT submissionId,executionStatus,activeAttempt,lastError FROM submission_execution').splitlines(),
                         'resultCounts': self.sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(judgeInfo,'$.verdict')),COUNT(*) FROM question_submit GROUP BY 1").splitlines(),
                         'scoreReceipts': self.sql('SELECT COUNT(*) FROM scoreboard_result_receipt')}
                self.evidence.joinpath('database-facts.json').write_text(json.dumps(facts, indent=2) + '\n')
            except Exception:
                self.event('DATABASE_DIAGNOSTIC_UNAVAILABLE')
        for name in list(self.procs)[::-1]:
            self.stop(name)
        if any(p.poll() is None for p in self.procs.values()):
            raise RuntimeError('OWNED_APPLICATION_PROCESS_STILL_RUNNING')
        for log in self.logs.values():
            log.close()
        for path in self.work.glob('*.log'):
            content = path.read_text(errors='replace')
            for secret in self.secret.values():
                content = content.replace(secret, '[REDACTED]')
            (self.evidence / path.name).write_text(content)
        # Archive first, but keep journals/configuration until the VM (and thus
        # every owned physical execution) has been conclusively removed.
        self.event('APPLICATION_STOPPED_CONTROL_DATA_RETAINED')
        summary = {'scope': 'Mac local startup smoke; real dependencies and isolated Java; static discovery',
                   'sandboxMode': 'isolated', 'isolationImage': self.env.get('CODEOJ_ISOLATION_IMAGE'),
                   'passed': self.ok, 'ports': self.ports, 'runtimeArchives': self.runtime_info['archives'],
                   'events': self.events, 'temporaryDataRemoved': False}
        (self.evidence / 'run.json').write_text(json.dumps(summary, indent=2) + '\n')
        # TCP port closure is independently observable after owned processes exit.
        for port in self.ports.values():
            with socket.socket() as sock:
                if sock.connect_ex(('127.0.0.1', port)) == 0:
                    raise RuntimeError('APPLICATION_PORT_NOT_RELEASED')


class Delivery:
    def __init__(self, args, java):
        self.args = args
        self.evidence = Path(args.evidence).resolve()
        self.evidence.mkdir(parents=True, exist_ok=False)
        self.root = Path(tempfile.mkdtemp(prefix='codeoj-startup-', dir='/private/tmp'))
        self.root.chmod(0o700)
        self.env = dict(os.environ, JAVA_HOME=java, CODEOJ_JAVA8_HOME=java, PATH=java + '/bin:' + os.environ['PATH'],
                        npm_config_cache=str(self.root / 'npm-cache'))
        self.events = []
        self.app = None
        self.runtime = PrivateIsolation(self.root / 'isolation', self.evidence, self.event)
        self.passed = False
        self.control = self.root / 'owner.json'
        self.control.write_text(json.dumps({'ownerPid': os.getpid(), 'root': str(self.root)}))
        self.control.chmod(0o600)
        self.event('PRIVATE_RUNTIME_DIRECTORY_CREATED', directory=str(self.root))

    def event(self, action, **details):
        value = {'at': dt.datetime.now(dt.timezone.utc).isoformat(), 'action': action, **details}
        self.events.append(value)
        print(json.dumps(value), flush=True)

    def run(self, args, log, timeout=600, extra=None):
        env = dict(self.env, **(extra or {}))
        with (self.evidence / log).open('ab') as out:
            process = subprocess.Popen([str(x) for x in args], cwd=REPO, env=env,
                                       stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
            self.control.write_text(json.dumps({'ownerPid': os.getpid(), 'root': str(self.root),
                                                'preparationProcessGroup': process.pid, 'phase': log}))
            try:
                if process.wait(timeout=timeout):
                    raise RuntimeError('PREPARATION_FAILED:' + log)
            except BaseException:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait(timeout=5)
                raise

    def start(self):
        self.event('PREPARING_NATIVE_DEPENDENCIES')
        self.run([sys.executable, '-B', REPO / 'scripts/local/prepare-runtimes.py', '--root', self.root / 'native'],
                 'native-preparation.log', 900)
        if not self.args.skip_build:
            self.event('BUILDING_APPLICATION')
            self.run(['mvn', '-B', '-f', REPO / 'codeoj-backend/pom.xml', '-DskipTests', 'package'], 'backend-build.log')
            frontend = self.root / 'frontend'
            frontend.mkdir()
            for name in ('src', 'public', 'generated'):
                shutil.copytree(REPO / 'codeoj-frontend' / name, frontend / name)
            for name in ('package.json', 'package-lock.json', 'vue.config.js', 'tsconfig.json',
                         'babel.config.js', '.browserslistrc', '.eslintrc.js', 'components.d.ts'):
                shutil.copy2(REPO / 'codeoj-frontend' / name, frontend / name)
            self.run(['npm', '--prefix', frontend, 'ci', '--no-audit', '--no-fund'], 'frontend-dependencies.log')
            self.run(['npm', '--prefix', frontend, 'run', 'build'], 'frontend-build.log',
                     extra={'VUE_APP_API_BASE_URL': ''})
        environment = self.runtime.prepare(REPO)
        # Only this process and its children see the temporary Docker settings.
        os.environ.update(self.env)
        os.environ.update(CODEOJ_SANDBOX_MODE='isolated', CODEOJ_DOCKER_BINARY=environment['docker'],
                          CODEOJ_DOCKER_ENDPOINT=environment['endpoint'], CODEOJ_ISOLATION_IMAGE=environment['image'])
        self.app = Application(self.root / 'native', self.evidence / 'application')
        self.app.frontend_dist = REPO / 'codeoj-frontend/dist' if self.args.skip_build else self.root / 'frontend/dist'
        self.control.write_text(json.dumps({'ownerPid': os.getpid(), 'root': str(self.root),
                                            'applicationData': str(self.app.work), 'phase': 'application'}))
        self.app.verify_artifacts()
        self.app.setup_infrastructure()
        self.app.setup_services()
        self.app.ready()
        self.app.seed()
        self.app.frontend()
        self.app.verify()
        credentials = self.app.credentials(self.root)
        if self.args.browser:
            self.run(['node', REPO / 'scripts/validation/startup-real-browser.cjs'], 'browser.log', 120,
                     extra={'CODEOJ_STARTUP_ACCESS': str(credentials)})
            self.event('REAL_STARTUP_BROWSER_PASSED')
        self.event('LOCAL_STACK_READY', url=self.app.url, privateAccessFile=str(credentials), mode=self.args.mode)
        self.passed = True
        if self.args.mode == 'serve':
            end = time.monotonic() + self.args.lifetime if self.args.lifetime else None
            while end is None or time.monotonic() < end:
                if any(p.poll() is not None for p in self.app.procs.values()):
                    raise RuntimeError('LOCAL_SERVICE_EXITED')
                time.sleep(1)

    def cleanup(self):
        errors = []
        if self.app is not None:
            try:
                self.app.cleanup()
            except Exception as error:
                errors.append(type(error).__name__ + ':APPLICATION_CLEANUP_FAILED')
        try:
            vm = self.runtime.cleanup()
        except Exception as error:
            errors.append(type(error).__name__ + ':VM_CLEANUP_FAILED')
            vm = {'vmDeleted': False}
        # Retain private files on any failure so a live resource never loses its
        # control data; do not label partial cleanup as success.
        if not errors:
            try:
                shutil.rmtree(self.root)
            except OSError:
                errors.append('PRIVATE_DIRECTORY_REMOVAL_FAILED')
        if self.app is not None:
            receipt = self.app.evidence / 'run.json'
            if receipt.exists():
                facts = json.loads(receipt.read_text())
                facts['temporaryDataRemoved'] = not self.app.work.exists()
                facts['physicalRuntimeRemoved'] = vm.get('vmDeleted', False)
                receipt.write_text(json.dumps(facts, indent=2) + '\n')
        sanitize_proxy_logs(self.evidence)
        result = {'passed': self.passed and not errors, 'mode': self.args.mode, 'events': self.events,
                  'cleanup': {**vm, 'applicationStopped': not errors, 'privateRootRemoved': not self.root.exists(),
                              'errors': errors}, 'scope': 'Mac arm64 startup delivery; static discovery; no Nacos/deployment/load acceptance'}
        if errors:
            result['cleanup']['retainedPrivateRoot'] = str(self.root)
        self.evidence.joinpath('delivery.json').write_text(json.dumps(result, indent=2) + '\n')
        self.event('CLEANUP_FINISHED', passed=not errors, privateRootRemoved=not self.root.exists())
        return not errors


def preflight(args):
    if platform.system() != 'Darwin' or platform.machine() != 'arm64' or sys.version_info < (3, 11):
        raise RuntimeError('REQUIRES_MAC_ARM64_PYTHON_311')
    for name in ('mvn', 'npm', 'node', 'make', 'clang', 'tar', 'codesign', 'install_name_tool', 'ps'):
        if shutil.which(name) is None:
            raise RuntimeError('MISSING_TOOL:' + name)
    subprocess.run(['ps', '-o', 'pid=', '-p', str(os.getpid())], stdout=subprocess.DEVNULL, check=True)
    java = os.environ.get('CODEOJ_JAVA8_HOME') or subprocess.check_output(['/usr/libexec/java_home', '-v', '1.8'], text=True).strip()
    version = subprocess.run([java + '/bin/java', '-version'], capture_output=True, text=True, check=True)
    if '"1.8.' not in version.stderr + version.stdout:
        raise RuntimeError('JAVA8_REQUIRED')
    if shutil.disk_usage('/private/tmp').free < 12 * 1024**3:
        raise RuntimeError('REQUIRES_12_GIB_FREE_TEMPORARY_SPACE')
    if args.browser:
        subprocess.run(['node', '-e', 'require(process.env.PLAYWRIGHT_MODULE || "playwright")'], check=True)
    return java


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['verify', 'serve'])
    parser.add_argument('--evidence', default=str(REPO / 'outputs/validation' / dt.datetime.now().strftime('startup-%Y%m%d-%H%M%S')))
    parser.add_argument('--skip-build', action='store_true', help='Diagnostic use only; still checks packaged classes/dependencies')
    parser.add_argument('--browser', action='store_true', help='Also run real Chrome UI smoke; requires Playwright and Chrome')
    parser.add_argument('--lifetime', type=int, help='Serve for this many seconds, then clean up; otherwise Ctrl+C')
    args = parser.parse_args()
    if args.lifetime is not None and args.lifetime < 1:
        parser.error('--lifetime must be positive')
    java = preflight(args)
    delivery = Delivery(args, java)
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    exit_code = 0
    try:
        delivery.start()
    except KeyboardInterrupt:
        delivery.event('INTERRUPTED')
        if not delivery.passed:
            exit_code = 130
    except Exception as error:
        delivery.passed = False
        code = str(error) if re.fullmatch(r'[A-Za-z0-9_.: -]{1,160}', str(error)) else None
        delivery.event('STARTUP_FAILED', errorType=type(error).__name__, errorCode=code)
        exit_code = 1
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        if not delivery.cleanup():
            exit_code = 1
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
