#!/usr/bin/env python3
"""Fresh local acceptance orchestrator; existing product/check entrypoints remain authoritative.

Run from the repository root. Downloads and mutable infrastructure live only in
an owned temporary root. Each suite creates a new database and accounts.
"""
import concurrent.futures
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import traceback
import xml.etree.ElementTree as ET

REPO = Path.cwd().resolve()
EVIDENCE = Path(__file__).resolve().parent
sys.path.insert(0, str(REPO / 'scripts/local'))
JAVA = '/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home'
os.environ.update(JAVA_HOME=JAVA, CODEOJ_JAVA8_HOME=JAVA, PYTHONDONTWRITEBYTECODE='1',
                  PATH=JAVA + '/bin:' + os.environ['PATH'],
                  PLAYWRIGHT_MODULE='/Users/zzds/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright',
                  PLAYWRIGHT_BROWSER_CHANNEL='chrome')
ROOT = Path(tempfile.mkdtemp(prefix='codeoj-overall-', dir='/private/tmp'))
ROOT.chmod(0o700)
ROWS = []


def write(name, value):
    (EVIDENCE / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def event(action, **facts):
    print(json.dumps({'at': dt.datetime.now(dt.timezone.utc).isoformat(), 'action': action, **facts}), flush=True)


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, REPO / 'scripts/local' / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def run(name, command, timeout=1200, cwd=REPO):
    event('STAGE_STARTED', stage=name)
    start = time.monotonic()
    with (EVIDENCE / (name + '.log')).open('wb') as log:
        try:
            result = subprocess.run([str(x) for x in command], cwd=cwd, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=timeout)
            row = {'stage': name, 'status': 'PASS' if result.returncode == 0 else 'FAIL',
                   'exitCode': result.returncode}
        except Exception as error:
            row = {'stage': name, 'status': 'FAIL', 'errorType': type(error).__name__}
    row['elapsedSeconds'] = round(time.monotonic() - start, 3)
    ROWS.append(row)
    write('stages.json', ROWS)
    event('STAGE_FINISHED', **row)
    return row['status'] == 'PASS'


def clean_engine(runtime, label):
    # This VM/daemon belongs exclusively to this run. No user daemon is touched.
    containers = runtime.cli('ps', '-aq', capture=True).split()
    volumes = runtime.cli('volume', 'ls', '-q', capture=True).split()
    write(label + '-engine-cleanup.json', {'remainingContainersBeforeCleanup': containers,
                                         'remainingVolumesBeforeCleanup': volumes})
    for cid in containers:
        runtime.cli('rm', '-f', cid)
    for volume in volumes:
        runtime.cli('volume', 'rm', volume)
    assert not runtime.cli('ps', '-aq', capture=True)
    assert not runtime.cli('volume', 'ls', '-q', capture=True)


def main():
    runtime = module('overall_private', 'private-isolation.py').PrivateIsolation(ROOT / 'isolation', EVIDENCE, event)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    tracked = subprocess.check_output(['git', 'ls-files', 'codeoj-backend', 'codeoj-frontend', 'scripts', 'docs'], text=True).splitlines()
    hashes = {p: hashlib.sha256((REPO / p).read_bytes()).hexdigest() for p in tracked if (REPO / p).is_file()}
    write('source-provenance.json', {'commit': commit, 'platform': platform.platform(),
          'ownedTemporaryRoot': str(ROOT), 'scope': 'Current Mac full local acceptance; no Nacos/deployment/load testing',
          'sourceSha256': hashes})
    cleanup = {'privateVmRemoved': False, 'temporaryRootRemoved': False}
    try:
        with concurrent.futures.ThreadPoolExecutor(3) as pool:
            native = pool.submit(run, 'native-preparation', [sys.executable, '-B', 'scripts/local/prepare-runtimes.py', '--root', ROOT / 'native'])
            isolated = pool.submit(runtime.prepare, REPO)
            frontend = pool.submit(run, 'frontend-build', ['npm', 'run', 'build'], 600, REPO / 'codeoj-frontend')
            native_ok, front_ok = native.result(), frontend.result()
            environment = isolated.result()
        write('isolation-environment.json', environment)
        os.environ.update(CODEOJ_SANDBOX_MODE='isolated', CODEOJ_DOCKER_BINARY=environment['docker'],
                          CODEOJ_DOCKER_ENDPOINT=environment['endpoint'], CODEOJ_ISOLATION_IMAGE=environment['image'])
        assert native_ok, 'Native runtime preparation failed'
        rt = json.loads((ROOT / 'native/runtimes.json').read_text())
        write('native-runtime-provenance.json', rt['archives'])
        backend_ok = run('backend-regression', ['mvn', '-B', '-f', 'codeoj-backend/pom.xml',
             '-Dcodeoj.real-redis=true', '-Dcodeoj.redis-server=' + rt['paths']['redis'],
             '-Dcodeoj.native-observation=true', 'clean', 'package'])
        reports = []
        for path in (REPO / 'codeoj-backend').glob('*/target/surefire-reports/TEST-*.xml'):
            root = ET.parse(path).getroot()
            reports.append({'name': root.attrib['name'], **{k: int(root.attrib.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')}})
        write('backend-tests.json', {'totals': {k: sum(r[k] for r in reports) for k in ('tests', 'failures', 'errors', 'skipped')}, 'classes': reports})
        run('startup-unit-tests', [sys.executable, '-B', '-m', 'unittest', 'discover', '-s', 'scripts/local/tests', '-v'])
        if not backend_ok or not front_ok:
            return
        # Prove the executable contains the very same Lua that this checkout declares.
        import zipfile
        lua = 'src/main/resources/lua/contest-registration.lua'
        question = REPO / 'codeoj-backend/codeoj-backend-question-service'
        with zipfile.ZipFile(question / 'target/codeoj-backend-question-service-0.0.1-SNAPSHOT.jar') as jar:
            packaged = jar.read('BOOT-INF/classes/lua/contest-registration.lua')
        assert packaged == (question / lua).read_bytes()
        write('resource-integrity.json', {'packagedLuaEqualsSource': True, 'sha256': hashlib.sha256(packaged).hexdigest()})
        common = ['--runtimes', ROOT / 'native']
        probes = [sys.executable, '-B', 'scripts/local/check-isolation.py', '--docker', environment['docker'],
                  '--endpoint', environment['endpoint'], '--image', environment['image'], '--docker-config', environment['dockerConfig']]
        run('isolation-probes', probes + ['--evidence', EVIDENCE / 'isolation-probes'], 900)
        clean_engine(runtime, 'isolation-probes')
        suites = [
            ('business-matrix', 'run-final-acceptance.py', ['--environment', EVIDENCE / 'isolation-environment.json'], 1500),
            ('current-permissions', 'check-current-permissions.py', [], 600),
            ('registration-recovery', 'check-registration-recovery.py', [], 600),
            ('judge-cutover', 'check-judge-cutover.py', ['--environment', EVIDENCE / 'isolation-environment.json'], 600),
        ]
        for name, filename, extra, timeout in suites:
            run(name, [sys.executable, '-B', REPO / 'scripts/local' / filename, *common, *extra,
                       '--evidence', EVIDENCE / name], timeout)
            clean_engine(runtime, name)
    except BaseException as error:
        write('orchestrator-error.json', {'type': type(error).__name__, 'traceback': traceback.format_exc()})
        event('ORCHESTRATOR_FAILED', errorType=type(error).__name__)
    finally:
        try:
            runtime.cleanup()
            cleanup['privateVmRemoved'] = True
        except BaseException as error:
            cleanup['cleanupErrorType'] = type(error).__name__
        if cleanup['privateVmRemoved']:
            shutil.rmtree(ROOT)
            cleanup['temporaryRootRemoved'] = not ROOT.exists()
        module('overall_startup', 'start-stack.py').sanitize_proxy_logs(EVIDENCE)
        write('cleanup.json', cleanup)
        write('stages.json', ROWS)
        event('ACCEPTANCE_FINISHED', stages=ROWS, cleanup=cleanup)


if __name__ == '__main__':
    main()
