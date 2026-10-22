#!/usr/bin/env python3
"""Functional legacy-drain -> reliable-isolated cutover in an owned disposable stack.

No production switch, queue purge, historical-row backfill or destructive rollback.
"""
import argparse
import importlib.util
import json
import os
import pathlib
import signal
import time

spec = importlib.util.spec_from_file_location("native_cutover", pathlib.Path(__file__).with_name("run-native-stack.py"))
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class Cutover(native.Stack):
    phase = "legacy"

    def spawn(self, name, args, env=None, cwd=None):
        if name in ("question", "judge"):
            prefixes = ("--codeoj.reliable-judge.enabled=", "--codeoj.registration.enabled=", "--codeoj.scoreboard.enabled=")
            args = [arg for arg in args if not str(arg).startswith(prefixes)]
            args += [prefix + ("false" if self.phase == "legacy" else "true") for prefix in prefixes]
        if name == "sandbox":
            args = [arg for arg in args if not str(arg).startswith("--codesandbox.mode=")]
            args += ["--codesandbox.mode=" + ("native" if self.phase == "legacy" else "isolated")]
        return super().spawn(name, args, env, cwd)

    def queue(self, name):
        status, data = self.mq("/queues/%2F/" + name)
        assert status == 200, (status, name)
        return data

    def exercise(self):
        self.accounts()
        qid = self.api("/question/add", "POST", {"title": "Cutover fixture sum", "content": "Add two integers.", "tags": [],
                       "judgeConfig": {"timeLimit": 3000, "memoryLimit": 262144},
                       "judgeCase": [{"input": "1 2", "output": "3"}]})
        code = "public class Main { public static void main(String[] a) { java.util.Scanner s=new java.util.Scanner(System.in); System.out.println(s.nextInt()+s.nextInt()); }}"
        body = {"questionId": qid, "language": "java", "code": code}
        self.stop("judge")
        old = self.api("/question/question_submit/do", "POST", body, "participant")
        assert self.sql("SELECT status FROM question_submit WHERE id=" + str(old)) == "0"
        self.wait("question", lambda: self.queue("code_queue").get("messages_ready", 0) == 1)
        self.event("LEGACY_INFLIGHT_QUEUE_OBSERVED", submissionId=str(old), ready=1)
        self.restart("judge")
        self.wait("judge", lambda: self.sql("SELECT status FROM question_submit WHERE id=" + str(old)) == "2", 40)
        original = self.sql("SELECT status,judgeInfo FROM question_submit WHERE id=" + str(old))
        self.wait("judge", lambda: self.queue("code_queue").get("messages", 0) == 0, 20)
        assert self.sql("SELECT COUNT(*) FROM submission_execution WHERE submissionId=" + str(old)) == "0"
        # Freeze new producers before stopping the legacy consumer; never flip flags with backlog.
        self.stop("question")
        self.stop("judge")
        empty = self.queue("code_queue")
        assert empty.get("messages", 0) == 0 and empty.get("messages_unacknowledged", 0) == 0
        self.stop("sandbox")
        self.phase = "reliable"
        for name in ("sandbox", "question", "judge"):
            self.restart(name)
        self.wait("question", lambda: self.api("/contests?admin=true", allow_error=True)[0] == 200)
        newer = self.api("/question/question_submit/do", "POST", body, "participant", "cutover-new-key")
        result = self.wait("judge", lambda: (lambda value: value if value.get("executionStatus") == "COMPLETED" else None)(
            self.api("/submissions/" + str(newer), actor="participant")), 50)
        assert result["judgeInfo"]["verdict"] == "AC", result
        assert self.sql("SELECT status,judgeInfo FROM question_submit WHERE id=" + str(old)) == original
        assert self.sql("SELECT COUNT(*) FROM submission_execution WHERE submissionId=" + str(old)) == "0"
        assert self.sql("SELECT COUNT(*) FROM submission_execution WHERE submissionId=" + str(newer)) == "1"
        self.wait("judge", lambda: self.queue("code_queue").get("consumers", 0) == 0)
        # Management statistics are sampled; wait for the observer after the real AC completes.
        self.wait("judge", lambda: self.queue("codeoj.judge.v1.queue").get("consumers", 0) > 0)
        projection = self.api("/question/question_submit/list/page", "POST", {"current": 1, "pageSize": 10, "questionId": qid}, "participant")
        assert {str(row["id"]) for row in projection["records"]} == {str(old), str(newer)}
        facts = {"status": "PASS", "legacyInflightQueueObserved": True, "legacyDrainedBeforeSwitch": True,
                 "producersStoppedDuringSwitch": True, "legacyConsumerAbsentAfterSwitch": True,
                 "newReliableIsolatedVerdict": "AC", "legacyResultUnchanged": True,
                 "legacyNotSilentlyBackfilled": True, "legacyAndNewRecordsReadable": True,
                 "scope": "Disposable local functional cutover only; not a production rollout or rollback"}
        (self.evidence / "cutover-results.json").write_text(json.dumps(facts, indent=2) + "\n")
        self.ok = True


def main():
    parser = argparse.ArgumentParser()
    for name in ("runtimes", "environment", "evidence"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    env = json.loads(pathlib.Path(args.environment).read_text())
    os.environ.update(CODEOJ_SANDBOX_MODE="isolated", CODEOJ_DOCKER_BINARY=env["docker"],
                      CODEOJ_DOCKER_ENDPOINT=env["endpoint"], CODEOJ_ISOLATION_IMAGE=env["image"])
    stack = Cutover(args.runtimes, args.evidence)
    def interrupted(signum, frame): raise KeyboardInterrupt("Cleaning owned cutover fixture")
    signal.signal(signal.SIGTERM, interrupted)
    try:
        stack.verify_artifacts(); stack.setup_infrastructure(); stack.setup_services(); stack.exercise()
    finally:
        stack.cleanup()


if __name__ == "__main__": main()
