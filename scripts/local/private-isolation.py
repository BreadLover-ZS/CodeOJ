"""Owned, disposable Lima/Docker runtime. No global installs, contexts or VM caches."""
import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.parse
import threading
from verified_download import fetch_verified

ASSETS = {
    'lima.tgz': ('https://github.com/lima-vm/lima/releases/download/v2.2.1/lima-2.2.1-Darwin-arm64.tar.gz',
                 '9e9eacce88f37e185c346bad73aa6136f738d8cdf8c3bb23cd42b071824bc66e'),
    'docker-mac.tgz': ('https://download.docker.com/mac/static/stable/aarch64/docker-29.9.2.tgz',
                       'ea8a2ce5ac31f9f5f3fb711a7a485e1e46cc7a69798a629a70306f6e6af25b7a'),
    'docker-linux.tgz': ('https://download.docker.com/linux/static/stable/aarch64/docker-29.9.2.tgz',
                         '873b3e0207857c3b2f28392fb922f3ea6efe37620c16d4d4aaf2fef409c90b73'),
    'ubuntu.img': ('https://cloud-images.ubuntu.com/releases/noble/release-20260926/ubuntu-24.04-server-cloudimg-arm64.img',
                   '1d6bffe64b848468ac97f821d369a4846d983de1800ccf6b5ec8853e85cefc55'),
}
BASE = 'eclipse-temurin@sha256:185d4ef919b98cd195690c33f3fd93bd49c66c70f0b662e54566c0dbaebea6ce'


def proxies_for_vm(environment):
    result = {}
    for key in ('http', 'https'):
        value = environment.get(key.upper() + '_PROXY') or environment.get(key + '_proxy')
        if not value:
            continue
        parsed = urllib.parse.urlsplit(value)
        if parsed.scheme not in ('http', 'https') or not parsed.hostname:
            raise ValueError('HTTP_PROXY_URL_REQUIRED')
        if parsed.hostname in ('127.0.0.1', 'localhost', '::1'):
            host = 'host.lima.internal' + (':' + str(parsed.port) if parsed.port else '')
            auth = parsed.netloc.rsplit('@', 1)[0] + '@' if '@' in parsed.netloc else ''
            value = urllib.parse.urlunsplit(parsed._replace(netloc=auth + host))
        result[key + '-proxy'] = value
    result['no-proxy'] = 'localhost,127.0.0.1,::1,' + environment.get('NO_PROXY', environment.get('no_proxy', ''))
    return result


class PrivateIsolation:
    def __init__(self, root, evidence, event):
        self.root = Path(root).resolve()
        self.evidence = Path(evidence)
        self.event = event
        self.home = self.root / 'instances'
        self.lima = self.root / 'lima/bin/limactl'
        self.docker = self.root / 'client/docker/docker'
        self.endpoint = 'unix://' + str(self.home / 'codeoj/sock/docker.sock')
        self.config = self.root / 'docker-config'
        self.env = dict(os.environ, LIMA_HOME=str(self.home))
        self.attempted = False
        self.cancelled = threading.Event()

    def command(self, args, log, timeout=300, capture=False, extra=None):
        with (self.evidence / log).open('ab') as out:
            if capture:
                result = subprocess.run([str(v) for v in args], env=dict(self.env, **(extra or {})), stdout=subprocess.PIPE,
                                        stderr=out, timeout=timeout, check=True)
                return result.stdout.decode().strip()
            subprocess.run([str(v) for v in args], env=dict(self.env, **(extra or {})), stdout=out, stderr=subprocess.STDOUT,
                           timeout=timeout, check=True)

    def cli(self, *args, capture=False, timeout=300, extra=None):
        return self.command([self.docker, '--config', self.config, '--host', self.endpoint, *args],
                            'isolation-engine.log', timeout, capture, extra)

    def download(self, item):
        name, (url, expected) = item
        self.event('DOWNLOAD_STARTED', asset=name)
        target = self.root / name
        result = fetch_verified(url, target, expected, {'User-Agent': 'CodeOJ-local-startup'}, self.event, self.cancelled)
        self.event('DOWNLOAD_VERIFIED', asset=name)
        return name, {'url': url, **result}

    def prepare(self, repo):
        self.root.mkdir(mode=0o700)
        self.config.mkdir()
        self.home.mkdir()
        assets = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            futures = [pool.submit(self.download, item) for item in ASSETS.items()]
            try:
                for future in concurrent.futures.as_completed(futures):
                    name, result = future.result()
                    assets[name] = result
            except BaseException:
                self.cancelled.set()
                for future in futures:
                    future.cancel()
                raise
        for archive, destination in [('lima.tgz', 'lima'), ('docker-mac.tgz', 'client')]:
            folder = self.root / destination
            folder.mkdir()
            self.command(['tar', '-xf', self.root / archive, '-C', folder], 'isolation-unpack.log')
        # A local, preverified image bypasses Lima's global URL cache. Only this private
        # read-only directory is mounted into the VM, never the user's home/repository.
        proxies = proxies_for_vm(self.env)
        config = {
            'vmType': 'vz', 'arch': 'aarch64', 'cpus': 2, 'memory': '3GiB', 'disk': '10GiB',
            'images': [{'location': str(self.root / 'ubuntu.img'), 'arch': 'aarch64',
                        'digest': 'sha256:' + ASSETS['ubuntu.img'][1]}],
            'mounts': [{'location': str(self.root), 'mountPoint': '/mnt/codeoj-runtime', 'writable': False}],
            'containerd': {'system': False, 'user': False},
            'ssh': {'loadDotSSHPubKeys': False, 'forwardAgent': False},
            'portForwards': [
                {'guestSocket': '/var/run/docker.sock', 'hostSocket': str(self.home / 'codeoj/sock/docker.sock')},
                {'guestPortRange': [1, 65535], 'ignore': True}],
            'provision': [
                {'mode': 'dependency', 'skipDefaultDependencyResolution': True, 'script': '#!/bin/sh\nexit 0\n'},
                {'mode': 'system', 'script': self.provision(proxies)}],
        }
        path = self.root / 'codeoj.yaml'
        path.write_text(json.dumps(config, indent=2))  # JSON is a YAML subset; avoid a PyYAML dependency.
        self.command([self.lima, 'validate', path], 'vm-validate.log')
        self.attempted = True
        self.event('PRIVATE_VM_STARTING')
        self.command([self.lima, 'start', '--tty=false', '--name=codeoj', path], 'vm-start.log', timeout=600)
        deadline = time.monotonic() + 30
        while True:
            try:
                info = json.loads(self.cli('info', '--format', '{{json .}}', capture=True, timeout=10))
                if info.get('CgroupVersion') != '2' or info.get('OSType') != 'linux':
                    raise RuntimeError('LINUX_CGROUP_V2_REQUIRED')
                break
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError):
                if time.monotonic() >= deadline:
                    raise RuntimeError('DOCKER_READINESS_TIMEOUT')
                time.sleep(1)
        self.event('PRIVATE_DOCKER_READY', version=info['ServerVersion'], cgroup=info['CgroupVersion'])
        self.cli('pull', BASE)
        tag = 'codeoj-startup:' + self.root.parent.name
        self.cli('build', '--network=host', '--build-arg', 'BASE_IMAGE=' + BASE,
                 '--build-arg', 'HTTP_PROXY', '--build-arg', 'HTTPS_PROXY', '--build-arg', 'NO_PROXY', '-t', tag,
                 '-f', Path(repo) / 'codeoj-backend/codeoj-backend-codesandbox/isolation/Dockerfile',
                 Path(repo) / 'codeoj-backend/codeoj-backend-codesandbox', timeout=600,
                 extra={'HTTP_PROXY': proxies.get('http-proxy', ''), 'HTTPS_PROXY': proxies.get('https-proxy', ''),
                        'NO_PROXY': proxies['no-proxy']})
        image = self.cli('image', 'inspect', tag, '--format', '{{.Id}}', capture=True)
        environment = {'docker': str(self.docker), 'endpoint': self.endpoint, 'image': image,
                       'dockerConfig': str(self.config)}
        (self.evidence / 'runtime-provenance.json').write_text(json.dumps({
            'assets': assets, 'baseImage': BASE, 'image': image,
            'daemon': {k: info.get(k) for k in ('ServerVersion', 'KernelVersion', 'CgroupVersion', 'OSType', 'Architecture')},
            'localImageAvoidsGlobalLimaCache': True}, indent=2) + '\n')
        self.event('PINNED_ISOLATION_IMAGE_READY', image=image)
        return environment

    @staticmethod
    def provision(proxies=None):
        config = json.dumps({'proxies': proxies or {}}, ensure_ascii=True)
        return '''#!/bin/sh
set -eu
tar -xf /mnt/codeoj-runtime/docker-linux.tgz -C /usr/local/bin --strip-components=1
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'CONFIG'
''' + config + '''
CONFIG
chmod 0600 /etc/docker/daemon.json
cat > /etc/systemd/system/codeoj-docker.service <<'SERVICE'
[Unit]
After=network-online.target
Wants=network-online.target
[Service]
Type=notify
ExecStart=/usr/local/bin/dockerd --host=unix:///var/run/docker.sock --storage-driver=overlay2
Restart=on-failure
Delegate=yes
KillMode=process
[Install]
WantedBy=multi-user.target
SERVICE
systemctl daemon-reload
systemctl enable --now codeoj-docker.service
for i in $(seq 1 30); do test -S /var/run/docker.sock && break; sleep 1; done
chown {{.UID}} /var/run/docker.sock
chmod 0600 /var/run/docker.sock
'''

    def cleanup(self):
        if not self.attempted:
            return {'vmDeleted': True, 'vmCreated': False}
        # Keep the whole private root if shutdown/removal cannot be proved. Never
        # delete a running VM's files or report a failed cleanup as success.
        rows = self.command([self.lima, 'list', '--json'], 'vm-cleanup.log', capture=True)
        instances = [json.loads(line) for line in rows.splitlines() if line.strip()]
        owned = [row for row in instances if row['name'] == 'codeoj']
        if owned:
            if owned[0]['status'] == 'Running':
                self.command([self.lima, 'stop', '--force', 'codeoj'], 'vm-cleanup.log', timeout=120)
            self.command([self.lima, 'delete', '--force', 'codeoj'], 'vm-cleanup.log', timeout=120)
        after = self.command([self.lima, 'list', '--json'], 'vm-cleanup.log', capture=True)
        if any(json.loads(row)['name'] == 'codeoj' for row in after.splitlines() if row.strip()):
            raise RuntimeError('OWNED_VM_CLEANUP_INCOMPLETE')
        return {'vmDeleted': True, 'vmCreated': bool(owned)}
