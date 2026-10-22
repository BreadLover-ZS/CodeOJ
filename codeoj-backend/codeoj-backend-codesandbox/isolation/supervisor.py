#!/usr/bin/env python3
"""Trusted container supervisor. Contestant code runs in a separate unprivileged process.

The only writable receipt is in a root-only volume. No Docker socket or host paths
are mounted. Linux PID namespace + cgroup own all descendants, including setsid.
"""
import json
import os
import pathlib
import signal
import subprocess
import sys
import time


def unprivileged():
    os.setgroups([])
    os.setgid(10000)
    os.setuid(10000)  # Linux clears effective/permitted capabilities on this transition.
    os.umask(0o077)
    os.setsid()


def main():
    deadline_ms = int(sys.argv[1])
    remaining = min(int(sys.argv[2]), deadline_ms - time.time_ns() // 1_000_000) / 1000
    started = time.monotonic_ns()
    result = {"end": "EXPIRED", "exitCode": -1, "memoryKiB": None, "timeMs": 0}
    if remaining > 0:
        process = subprocess.Popen(sys.argv[3:], preexec_fn=unprivileged,
                                   env={"LANG": "C.UTF-8", "PATH": "/opt/java/openjdk/bin:/usr/bin:/bin", "HOME": "/tmp"})
        try:
            result["exitCode"] = process.wait(timeout=remaining)
            result["end"] = "EXITED"
        except subprocess.TimeoutExpired:
            result["end"] = "TIMEOUT"
        finally:
            # Namespace-wide kill includes reparented/double-forked/setsid children.
            # Linux kill(-1) excludes this process and PID 1. No host PID namespace is used.
            try:
                os.kill(-1, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=2)
        result["timeMs"] = max(1, (time.monotonic_ns() - started) // 1_000_000)
        peak = pathlib.Path("/sys/fs/cgroup/memory.peak")
        if peak.exists():
            result["memoryKiB"] = (int(peak.read_text()) + 1023) // 1024
        events = pathlib.Path("/sys/fs/cgroup/memory.events")
        if events.exists() and int(dict(line.split() for line in events.read_text().splitlines()).get("oom_kill", 0)) > 0:
            result["end"] = "MEMORY_LIMIT"
    target = pathlib.Path("/receipt/result.json")
    with target.open("w") as stream:
        os.chmod(target, 0o600)
        json.dump(result, stream)
        stream.flush()
        os.fsync(stream.fileno())


if __name__ == "__main__":
    main()
