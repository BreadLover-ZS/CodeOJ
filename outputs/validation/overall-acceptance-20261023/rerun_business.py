#!/usr/bin/env python3
"""Recorded correction of the acceptance frontend build configuration.

The original failed browser run remains in business-matrix/. No product source
or business assertion is changed. This continuation uses only the still-owned VM.
"""
import datetime as dt
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

evidence = Path(__file__).resolve().parent
repo = Path.cwd()
pause = json.loads((evidence / 'orchestrator-pause.json').read_text())
provenance = json.loads((evidence / 'source-provenance.json').read_text())
root = Path(provenance['ownedTemporaryRoot'])
java = '/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home'
env = dict(os.environ, JAVA_HOME=java, CODEOJ_JAVA8_HOME=java, PATH=java + '/bin:' + os.environ['PATH'],
           PYTHONDONTWRITEBYTECODE='1', VUE_APP_API_BASE_URL='',
           PLAYWRIGHT_MODULE='/Users/zzds/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright',
           PLAYWRIGHT_BROWSER_CHANNEL='chrome')
started = time.monotonic()
facts = {'stage': 'business-matrix-02', 'status': 'FAIL', 'correction': 'Build frontend with VUE_APP_API_BASE_URL empty for the disposable same-origin Gateway proxy',
         'productSourceChanged': False, 'businessAssertionsChanged': False}
try:
    deadline = time.monotonic() + 600
    while not (evidence / 'registration-recovery/run.json').exists():
        if time.monotonic() >= deadline:
            raise TimeoutError('Prior owned registration suite has not cleaned up')
        time.sleep(.5)
    with (evidence / 'business-matrix-02.log').open('wb') as log:
        result = subprocess.run([sys.executable, '-B', repo / 'scripts/local/run-final-acceptance.py',
               '--runtimes', root / 'native', '--environment', evidence / 'isolation-environment.json',
               '--evidence', evidence / 'business-matrix-02'], env=env, stdout=log, stderr=subprocess.STDOUT, timeout=1500)
    facts.update(exitCode=result.returncode, status='PASS' if result.returncode == 0 else 'FAIL')
finally:
    facts['elapsedSeconds'] = round(time.monotonic() - started, 3)
    facts['finishedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
    (evidence / 'business-rerun.json').write_text(json.dumps(facts, indent=2) + '\n')
    os.kill(pause['pid'], signal.SIGCONT)
    print(json.dumps(facts), flush=True)
