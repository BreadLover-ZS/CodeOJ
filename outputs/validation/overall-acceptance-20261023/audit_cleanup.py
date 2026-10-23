#!/usr/bin/env python3
"""Read-only verification independent of the runners' own cleanup flags."""
import json
import os
from pathlib import Path
import shlex
import socket
import subprocess
import tempfile

evidence = Path(__file__).resolve().parent
provenance = json.loads((evidence / 'source-provenance.json').read_text())
owned_root = provenance['ownedTemporaryRoot']
ports = set()
runs = []
for path in evidence.rglob('run.json'):
    row = json.loads(path.read_text())
    ports.update(row.get('ports', {}).values())
    runs.append({'receipt': str(path.relative_to(evidence)), 'runnerReportedDataRemoved': row.get('temporaryDataRemoved')})
open_ports = []
for port in sorted(ports):
    with socket.socket() as connection:
        connection.settimeout(.2)
        if connection.connect_ex(('127.0.0.1', port)) == 0:
            open_ports.append(port)
processes = []
for line in subprocess.check_output(['ps', '-axo', 'pid=,command='], text=True).splitlines():
    pid, command = line.strip().split(None, 1)
    if int(pid) == os.getpid():
        continue
    try:
        args = shlex.split(command)
    except ValueError:
        continue
    if not args or Path(args[0]).name in ('zsh', 'bash', 'sh'):
        continue
    if any(marker in command for marker in (owned_root, '/codeoj-native-stack-', '/codeoj-isolation-check-')):
        # Never archive full arguments, which may contain Redis credentials.
        processes.append({'pid': int(pid), 'executable': args[0]})
remaining_dirs = []
started = (evidence / 'source-provenance.json').stat().st_mtime
for parent in {Path('/private/tmp'), Path(tempfile.gettempdir()).resolve()}:
    for pattern in ('codeoj-native-stack-*', 'codeoj-isolation-check-*', 'codeoj-registration-redis-*'):
        for path in parent.glob(pattern):
            if path.is_dir() and path.stat().st_ctime >= started:
                remaining_dirs.append(str(path))
result = {'ownedTemporaryRootExists': Path(owned_root).exists(), 'remainingOpenOwnedPorts': open_ports,
          'portsChecked': len(ports), 'remainingMatchingRuntimeProcesses': processes,
          'newMatchingHarnessDirectories': remaining_dirs, 'runsChecked': runs}
result['passed'] = (not result['ownedTemporaryRootExists'] and not open_ports and not processes and not remaining_dirs
                    and all(row['runnerReportedDataRemoved'] for row in runs) and len(runs) == 5)
(evidence / 'independent-cleanup-audit.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
