#!/usr/bin/env python3
"""Pinned native macOS/arm64 test runtimes, extracted only into the caller's private directory."""
import argparse, concurrent.futures, hashlib, json, os, pathlib, platform, subprocess, urllib.request
from verified_download import fetch_verified

RUNTIMES = {
    'mysql': ('https://cdn.mysql.com/Downloads/MySQL-8.0/mysql-8.0.46-macos15-arm64.tar.gz', 'sha256', '6ca4a361a45f5019fbf6d500997986b4aaf6f0e30ff9c41179d1e8ecd635f3db'),
    'rabbitmq': ('https://github.com/rabbitmq/rabbitmq-server/releases/download/v4.3.6/rabbitmq-server-generic-unix-4.3.6.tar.xz', 'sha256', 'aa0ff5de9ff0136dc42eba21dc3a973cc3b286437cdba0f45556e890509c1614'),
    'erlang': ('https://ghcr.io/v2/homebrew/core/erlang/28/blobs/sha256:e37b0ce944f1e772767bb7b2fa8844076c3069754ce44c224f15d76fd39aed67', 'sha256', 'e37b0ce944f1e772767bb7b2fa8844076c3069754ce44c224f15d76fd39aed67'),
    'openssl': ('https://ghcr.io/v2/homebrew/core/openssl/4/blobs/sha256:39fb74d25429fbb9794cf4327530ba458b97d0000406715270d3b0715bd6ba35', 'sha256', '39fb74d25429fbb9794cf4327530ba458b97d0000406715270d3b0715bd6ba35'),
    'redis': ('https://download.redis.io/releases/redis-6.2.24.tar.gz', 'sha256', 'c3f112749c7cb108597713469cc9beed305e91f036fba313ce8dcbad829f5821'),
}

def checksum(path, algorithm):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()

def prepare(root):
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise RuntimeError('This pinned fixture requires macOS arm64; no global installation is performed.')
    root = pathlib.Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    def fetch(item):
        name, (url, algorithm, digest) = item
        archive = root / (name + ('.tar.xz' if name == 'rabbitmq' else '.tar.gz'))
        headers = {'User-Agent': 'CodeOJ-native-functional-check'}
        if url.startswith('https://ghcr.io/'):
            repository = url.split('/v2/', 1)[1].split('/blobs/', 1)[0]
            with urllib.request.urlopen('https://ghcr.io/token?service=ghcr.io&scope=repository:' + repository + ':pull', timeout=30) as response:
                headers['Authorization'] = 'Bearer ' + json.load(response)['token']
        def event(action, **details):
            print(json.dumps({'action': action, **details}), flush=True)
        fetch_verified(url, archive, digest, headers, event)
        target = root / name
        target.mkdir(exist_ok=True)
        subprocess.run(['tar', '-xf', str(archive), '-C', str(target)], check=True)
        return name, {'url': url, 'verification': algorithm + ':' + digest,
                      'sha256': checksum(archive, 'sha256')}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        evidence = dict(pool.map(fetch, RUNTIMES.items()))
    redis = next((root / 'redis').glob('redis-*'))
    with (root / 'redis-build.log').open('w') as log:
        subprocess.run(['make', '-C', str(redis), '-j4', 'BUILD_TLS=no'], stdout=log, stderr=subprocess.STDOUT, check=True)
    paths = {'mysql': str(next((root / 'mysql').glob('mysql-*'))),
             'rabbitmq': str(next((root / 'rabbitmq').glob('rabbitmq_*'))),
             'redis': str(redis / 'src' / 'redis-server'),
             'erlang': str(next((root / 'erlang').glob('erlang*/*/lib/erlang'))),
             'openssl': str(next((root / 'openssl').glob('openssl*/*/lib')))}
    # Homebrew bottles retain loader placeholders until relocation. Patch only private copies.
    libraries = list(pathlib.Path(paths['erlang']).glob('lib/crypto-*/priv/lib/crypto.so')) + [pathlib.Path(paths['openssl']) / 'libssl.4.dylib', pathlib.Path(paths['openssl']) / 'libcrypto.4.dylib']
    for library in libraries:
        library.chmod(library.stat().st_mode | 0o200)
        dependencies = subprocess.check_output(['otool', '-L', str(library)], text=True)
        for filename in ['libcrypto.4.dylib', 'libssl.4.dylib']:
            original = '@@HOMEBREW_PREFIX@@/opt/openssl@4/lib/' + filename
            if original in dependencies:
                subprocess.run(['install_name_tool', '-change', original, str(pathlib.Path(paths['openssl']) / filename), str(library)], check=True)
        if library.name.endswith('.dylib'):
            subprocess.run(['install_name_tool', '-id', str(library), str(library)], check=True)
        subprocess.run(['codesign', '--force', '--sign', '-', str(library)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    (root / 'runtimes.json').write_text(json.dumps({'paths': paths, 'archives': evidence}, indent=2) + '\n')
    return paths

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.root), indent=2))
