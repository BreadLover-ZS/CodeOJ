#!/usr/bin/env python3
"""Real JV07/JV08/JV25 probes against two sandbox JVMs and a private Linux Docker daemon."""
import argparse
import concurrent.futures
import contextlib
import hashlib
import http.client
import json
import os
import pathlib
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

REPO = pathlib.Path(__file__).resolve().parents[2]


class Checks:
    def __init__(self, args):
        self.args = args
        self.evidence = pathlib.Path(args.evidence).resolve()
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.work = pathlib.Path(tempfile.mkdtemp(prefix="codeoj-isolation-check-", dir="/private/tmp"))
        self.root = self.work / "state"
        self.secret = secrets.token_hex(24)
        self.pool = hashlib.sha256(str(self.root.resolve()).encode()).hexdigest()[:16]
        self.procs, self.logs, self.results = {}, {}, []
        self.ports = {}
        for name in ("a", "b"):
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                self.ports[name] = sock.getsockname()[1]
        properties = {"server.address": "127.0.0.1", "codesandbox.mode": "isolated",
                      "spring.cloud.nacos.discovery.enabled": "false", "spring.cloud.nacos.discovery.register-enabled": "false",
                      "spring.cloud.nacos.config.enabled": "false",
                      "codesandbox.auth-secret": self.secret, "codesandbox.max-concurrent": 1,
                      "codesandbox.isolated.docker-binary": args.docker,
                      "codesandbox.isolated.docker-endpoint": args.endpoint,
                      "codesandbox.isolated.image": args.image,
                      "codesandbox.isolated.state-directory": str(self.root),
                      "spring.main.banner-mode": "off", "logging.level.root": "WARN"}
        self.config = self.work / "sandbox.properties"
        self.config.write_text("".join(f"{k}={v}\n" for k, v in properties.items()))
        self.config.chmod(0o600)

    def docker(self, *args):
        result = subprocess.run([self.args.docker, "--config", self.args.docker_config,
                                 "--host", self.args.endpoint, *args], capture_output=True, text=True, timeout=20)
        assert result.returncode == 0, result.stderr[:400]
        return result.stdout.strip()

    def start(self, name):
        java = "/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home/bin/java"
        jar = REPO / "codeoj-backend/codeoj-backend-codesandbox/target/codeoj-backend-codesandbox-0.0.1-SNAPSHOT.jar"
        log = (self.work / (name + ".log")).open("ab")
        self.logs[name] = log
        self.procs[name] = subprocess.Popen([java, "-Xmx256m", "-jar", str(jar),
                                           "--spring.profiles.active=isolation-check",
                                           "--spring.config.additional-location=file:" + str(self.config),
                                           "--server.port=" + str(self.ports[name])],
                                          cwd=self.work, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        self.wait(lambda: self.listening(name), 45)

    def listening(self, name):
        if self.procs[name].poll() is not None:
            raise RuntimeError("sandbox startup failed; see sanitized log")
        try:
            with socket.create_connection(("127.0.0.1", self.ports[name]), timeout=.2):
                return True
        except OSError:
            return False

    def stop(self, name, hard=False):
        process = self.procs.get(name)
        if process and process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL if hard else signal.SIGTERM)
            process.wait(timeout=15)

    def wait(self, predicate, seconds=20):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            result = predicate()
            if result:
                return result
            time.sleep(.1)
        raise AssertionError("isolation check deadline")

    def request(self, body, inputs=None, limit=3000):
        return {"executionToken": str(uuid.uuid4()), "executionDeadlineEpochMs": int(time.time() * 1000) + 45000,
                "language": "java", "code": "public class Main { public static void main(String[] a) throws Exception {" + body + "}}",
                "inputList": [""] if inputs is None else inputs, "runTimeLimitMs": limit, "memoryLimitKiB": 262144}

    def call(self, request, name="a", timeout=40):
        payload = json.dumps(request).encode()
        req = urllib.request.Request(f"http://127.0.0.1:{self.ports[name]}/executeCode", data=payload,
                                     headers={"Content-Type": "application/json", "auth": self.secret})
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=timeout) as response:
            return json.load(response)

    def resources(self, token=None, running=False):
        args = ["ps", "--quiet"] + ([] if running else ["--all"]) + ["--filter", "label=codeoj.pool=" + self.pool]
        if token:
            args += ["--filter", "label=codeoj.token=" + token]
        return self.docker(*args).split()

    def run_case(self, name, jv, work):
        started = time.monotonic()
        try:
            facts = work() or {}
            row = {"case": name, "jv": jv, "status": "PASS", "facts": facts}
        except Exception as failure:
            row = {"case": name, "jv": jv, "status": "FAIL", "errorType": type(failure).__name__,
                   "error": str(failure).replace(self.secret, "[REDACTED]")[:500]}
        row["elapsedSeconds"] = round(time.monotonic() - started, 3)
        self.results.append(row)
        (self.evidence / "isolation-results.json").write_text(json.dumps(self.results, indent=2) + "\n")
        print(json.dumps(row), flush=True)
        assert row["status"] == "PASS", row

    def expect(self, body, outcome="EXECUTED", outputs=None, **kwargs):
        response = self.call(self.request(body, **kwargs))
        assert response["outcome"] == outcome, response
        if outputs is not None:
            assert response["outputList"] == outputs, response
        assert not self.resources(), "resources survive completed request"
        return {"outcome": response["outcome"], "outputs": response.get("outputList"), "metrics": response.get("judgeInfo")}

    def manager_recovery(self):
        request = self.request("Thread.sleep(30000);", limit=4500)
        token = request["executionToken"]
        self.stop("b")  # Prove deadline enforcement while no manager is alive.
        with concurrent.futures.ThreadPoolExecutor(1) as executor:
            pending = executor.submit(self.call, request)
            def runtime():
                for cid in self.resources(token, running=True):
                    data = json.loads(self.docker("inspect", cid))[0]
                    if data["Name"].endswith("-case-0"):
                        return cid
                return None
            cid = self.wait(runtime)
            self.stop("a", hard=True)
            with contextlib.suppress(Exception):
                pending.result(timeout=5)
            self.wait(lambda: json.loads(self.docker("inspect", cid))[0]["State"]["Running"] is False, 7)
            self.start("b")
            assert not self.resources(token)
            replay = self.call(request, "b")
            assert replay["errorCode"] == "EXECUTION_OWNER_LOST", replay
            newer = self.call(self.request('System.out.print("recovered");'), "b")
            assert newer["outputList"] == ["recovered"], newer
        self.start("a")
        return {"ownerSigkill": True, "noManagerDeadlineTerminatedContainer": True,
                "startupReapedContainersAndVolumes": True, "oldTokenNotReexecuted": True, "slotReusableAfterReap": True}

    def shared_slot(self):
        request = self.request('Thread.sleep(1800);System.out.print("once");', limit=3500)
        with concurrent.futures.ThreadPoolExecutor(2) as executor:
            first = executor.submit(self.call, request)
            self.wait(lambda: self.resources(request["executionToken"], running=True))
            blocked = self.call(self.request('System.out.print("second");'), "b")
            assert blocked["errorCode"] == "EXECUTION_CAPACITY_UNAVAILABLE", blocked
            duplicate = executor.submit(self.call, request, "b")
            first_response, duplicate_response = first.result(), duplicate.result()
            assert first_response["outputList"] == ["once"], first_response
            assert duplicate_response["outputList"] == ["once"], duplicate_response
        self.stop("a"); self.start("a")
        replay = self.call(request)
        assert replay["outputList"] == ["once"], replay
        assert not self.resources()
        return {"managerProcesses": 2, "sharedPhysicalSlots": 1, "capacityExceededRejected": True,
                "concurrentDuplicateSameResult": True, "restartReplayedDurableResult": True}

    def cpu_limit(self):
        request = self.request('for(int i=0;i<4;i++)new Thread(()->{while(true){}}).start();Thread.sleep(30000);', limit=4500)
        with concurrent.futures.ThreadPoolExecutor(1) as executor:
            pending = executor.submit(self.call, request)
            def runtime():
                for cid in self.resources(request["executionToken"], running=True):
                    item = json.loads(self.docker("inspect", cid))[0]
                    if item["Name"].endswith("-case-0"):
                        return cid
                return None
            cid = self.wait(runtime)
            config = json.loads(self.docker("inspect", cid))[0]["HostConfig"]
            assert config["NanoCpus"] == 1000000000
            def throttled():
                data = dict(line.split() for line in self.docker("exec", "--user", "0", cid,
                            "cat", "/sys/fs/cgroup/cpu.stat").splitlines())
                return int(data.get("nr_throttled", 0)) or None
            periods = self.wait(throttled, 3)
            response = pending.result()
            assert response["outcome"] == "TLE", response
        assert not self.resources()
        return {"configuredNanoCpus": config["NanoCpus"], "kernelThrottledPeriodsObserved": periods,
                "fourBusyThreadsBoundedByQuotaAndDeadline": True}

    def pid_limit(self):
        request = self.request('int n=0;try{for(;n<120;n++){Process p=new ProcessBuilder("/bin/sleep","20").start();'
                               'p.getInputStream().close();p.getErrorStream().close();p.getOutputStream().close();}}'
                               'catch(Throwable expected){}System.out.print(n<64);Thread.sleep(2200);', limit=4000)
        with concurrent.futures.ThreadPoolExecutor(1) as executor:
            pending = executor.submit(self.call, request)
            def runtime():
                for short in self.resources(request["executionToken"], running=True):
                    item = json.loads(self.docker("inspect", short))[0]
                    if item["Name"].endswith("-case-0"):
                        return item["Id"]
                return None
            cid = self.wait(runtime)
            # Trusted read-only observer, separate from the contestant container. It never runs user code.
            reader = ('import glob,pathlib,sys,time; '
                      'paths=glob.glob("/host-cgroup/**/docker-"+sys.argv[1]+".scope/pids.events",recursive=True); '
                      'time.sleep(0.5); print(pathlib.Path(paths[0]).read_text())')
            events = self.docker("run", "--rm", "--network", "none", "--read-only", "--cap-drop", "ALL",
                                 "--security-opt", "no-new-privileges:true", "--memory", "32m", "--pids-limit", "8",
                                 "--label", "codeoj.pool=" + self.pool,
                                 "--mount", "type=bind,source=/sys/fs/cgroup,target=/host-cgroup,readonly",
                                 "--entrypoint", "python3", self.args.image, "-c", reader, cid)
            hits = int(dict(line.split() for line in events.splitlines())["max"])
            assert hits > 0, events
            response = pending.result()
            assert response["outcome"] == "EXECUTED" and response["outputList"] == ["true"], response
        assert not self.resources()
        return {"kernelPidsMaxEvents": hits, "childPipesClosedToAvoidNofileLimit": True,
                "observerReadOnlyAndNeverRunsUserCode": True, "descendantsRemoved": True}

    def exercise(self):
        self.start("a"); self.start("b")
        if self.args.focus != "all":
            name = self.args.focus
            self.run_case(name, ["JV08"] if name in ("cpu", "pid") else ["JV25"],
                          self.pid_limit if name == "pid" else self.cpu_limit if name == "cpu" else self.shared_slot if name == "shared" else self.manager_recovery)
            return
        self.run_case("real-java-and-cgroup-metrics", ["JV07"], lambda: self.expect('System.out.print("3");', outputs=["3"]))
        self.run_case("network-denied", ["JV08"], lambda: self.expect(
            'try { new java.net.Socket().connect(new java.net.InetSocketAddress("1.1.1.1",80),300);System.out.print("LEAK"); }'
            'catch(java.io.IOException expected){System.out.print("BLOCKED");}', outputs=["BLOCKED"]))
        self.run_case("nonroot-no-capabilities-no-docker-socket", ["JV08"], lambda: self.expect(
            'String status=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/self/status")),"UTF-8");'
            'System.out.print(status.contains("Uid:\\t10000\\t10000") && status.contains("CapEff:\\t0000000000000000")'
            ' && !java.nio.file.Files.exists(java.nio.file.Paths.get("/var/run/docker.sock")));', outputs=["true"]))
        self.run_case("readonly-root-artifacts-and-private-receipt", ["JV08"], lambda: self.expect(
            'int denied=0;for(String p:new String[]{"/etc/codeoj-probe","/work/classes/changed","/receipt/forged"})'
            '{try{java.nio.file.Files.write(java.nio.file.Paths.get(p),new byte[]{1});}catch(java.io.IOException e){denied++;}}'
            'System.out.print(denied);', outputs=["3"]))
        self.run_case("bounded-tmpfs", ["JV08"], lambda: self.expect(
            'try(java.io.OutputStream o=new java.io.FileOutputStream("/tmp/fill")){byte[] b=new byte[1048576];'
            'for(int i=0;i<40;i++)o.write(b);System.out.print("UNBOUNDED");}'
            'catch(java.io.IOException e){System.out.print("BOUNDED");}', outputs=["BOUNDED"]))
        self.run_case("pid-cgroup-limit-and-descendant-cleanup", ["JV08", "JV25"], self.pid_limit)
        self.run_case("cgroup-memory-limit", ["JV08"], lambda: self.expect(
            'java.util.List<byte[]> data=new java.util.ArrayList<>();while(true)data.add(new byte[1048576]);', outcome="MLE"))
        self.run_case("cpu-quota-throttling", ["JV08"], self.cpu_limit)
        self.run_case("wall-deadline", ["JV07"], lambda: self.expect('while(true){}', outcome="TLE", limit=700))
        self.run_case("stdout-budget", ["JV07"], lambda: self.expect('while(true)System.out.print("xxxxxxxxxxxxxxxx");', outcome="OUTPUT_LIMIT"))
        self.run_case("stderr-budget", ["JV07"], lambda: self.expect('while(true)System.err.print("xxxxxxxxxxxxxxxx");', outcome="OUTPUT_LIMIT"))
        self.run_case("stdin-not-read", ["JV07"], lambda: self.expect('Thread.sleep(10000);', outcome="TLE", inputs=["x"*1000000], limit=700))
        self.run_case("case-filesystem-isolation", ["JV08"], lambda: self.expect(
            'java.nio.file.Path p=java.nio.file.Paths.get("/tmp/previous-case");System.out.print(java.nio.file.Files.exists(p)?"LEAK":"CLEAN");'
            'java.nio.file.Files.write(p,new byte[]{1});', outputs=["CLEAN", "CLEAN"], inputs=["", ""]))
        self.run_case("setsid-descendants-cleaned-on-normal-exit", ["JV25"], lambda: self.expect(
            'new ProcessBuilder("/bin/sh","-c","setsid sleep 30 >/dev/null 2>&1 &").start().waitFor();System.out.print("done");', outputs=["done"]))
        self.run_case("shared-slot-token-dedup-and-restart", ["JV25"], self.shared_slot)
        self.run_case("sigkill-manager-independent-deadline-recovery", ["JV25"], self.manager_recovery)

    def cleanup(self):
        for name in list(self.procs):
            with contextlib.suppress(Exception): self.stop(name)
        for stream in self.logs.values(): stream.close()
        for path in self.work.glob("*.log"):
            (self.evidence / path.name).write_text(path.read_text(errors="replace").replace(self.secret, "[REDACTED]"))
        for cid in self.resources(): self.docker("rm", "--force", cid)
        volumes = self.docker("volume", "ls", "--quiet", "--filter", "label=codeoj.pool=" + self.pool).split()
        for volume in volumes: self.docker("volume", "rm", volume)
        shutil.rmtree(self.work)


def main():
    parser = argparse.ArgumentParser()
    for name in ("docker", "endpoint", "image", "docker-config", "evidence"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--focus", choices=("all", "shared", "recovery", "cpu", "pid"), default="all")
    checks = Checks(parser.parse_args())
    def interrupted(signum, frame): raise KeyboardInterrupt("Cleaning owned isolation fixture")
    signal.signal(signal.SIGTERM, interrupted)
    try: checks.exercise()
    finally: checks.cleanup()


if __name__ == "__main__": main()
