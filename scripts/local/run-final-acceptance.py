#!/usr/bin/env python3
"""Re-run frozen real business fault cases into a fresh directory with current isolation.

Historical test modules are loaded read-only. Every output root is redirected;
known fixture mistakes (5s claim gate, absolute acceptedNum assumption) use the
previously diagnosed precise/fresh-fixture variants. No archived results change.
"""
import argparse
import datetime as dt
import importlib
import json
import os
import pathlib
import signal
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
HISTORY = REPO / "outputs/validation/final-acceptance-20261018"
sys.path.insert(0, str(HISTORY))
acceptance = importlib.import_module("run_acceptance")
extended = importlib.import_module("extended_cases")
followup = importlib.import_module("followup_cases")


class Cases(followup.Advanced):
    def save(self):
        (extended.ROOT / "matrix-cases.json").write_text(json.dumps(self.rows, indent=2) + "\n")

    def run_case(self, name, jvs, work):
        # Frozen helper reuses `name` in its cleanup loop; preserve the real case label.
        super().run_case(name, jvs, work)
        self.rows[-1]["case"] = name
        self.save()

    def jv15_exact(self):
        previous = self.qid
        self.qid = self.s.api("/question/add", "POST", {"title": "Acceptance original sum", "content": "Add two integers.",
                             "tags": [], "judgeConfig": {"timeLimit": 3000, "memoryLimit": 262144},
                             "judgeCase": [{"input": "1 2", "output": "3"}]})
        try:
            before = self.count("SELECT acceptedNum FROM question WHERE id=" + str(self.qid))
            cid, draft = self.contest("exact-snapshot", capacity=1, redis=False, close=8, start=11, end=70, source=self.qid)
            self.register(cid, "u0")
            frozen = self.detail(cid)["problems"][0]
            spec = self.s.sql("SELECT judgeSpecHash FROM contest_problem_snapshot WHERE id=" + frozen["id"])
            self.stop_judges()
            start = dt.datetime.fromisoformat(draft["startAt"].replace("Z", "+00:00"))
            self.wait(lambda: dt.datetime.now(dt.timezone.utc) >= start, 15)
            sid = self.csubmit(cid, frozen["id"], "u0")
            assert self.s.sql("SELECT executionStatus FROM submission_execution WHERE submissionId=" + str(sid)) == "QUEUED"
            self.s.api("/question/update", "POST", {"id": self.qid, "title": "Edited after acceptance",
                       "judgeCase": [{"input": "1 2", "output": "99"}]})
            newer = self.practice()
            self.start_judges(); self.completed(newer, "WA")
            result = self.wait(lambda: (lambda r: r if r.get("executionStatus") == "COMPLETED" else None)(
                self.s.api("/submissions/" + str(sid), actor="u0")), 30)
            assert result["judgeInfo"]["verdict"] == "AC"
            assert self.s.sql("SELECT judgeSpecHash FROM submission_execution WHERE submissionId=" + str(sid)) == spec
            assert before == self.count("SELECT acceptedNum FROM question WHERE id=" + str(self.qid))
            return {"acceptedBeforeSourceEdit": True, "contestAC": True, "newPracticeWA": True,
                    "snapshotHashUnchanged": True, "practiceAcceptedNumUnchanged": True, "fixtureQuestionIndependent": True}
        finally:
            self.qid = previous

    def jv14_permissions(self):
        cid, draft = self.contest("private-permissions", capacity=1, redis=False, close=7, start=10, end=40)
        snapshot = self.detail(cid)["problems"][0]["id"]
        status, _ = self.safe_call("/contests/" + cid + "?admin=true", actor="observer")
        assert status == 403
        status, _ = self.safe_call("/contests/" + cid + "/scoreboard", actor="anonymous")
        assert status == 401
        start = dt.datetime.fromisoformat(draft["startAt"].replace("Z", "+00:00"))
        self.wait(lambda: dt.datetime.now(dt.timezone.utc) >= start, 15)
        assert self.s.api("/contests/" + cid, actor="observer")["problems"] == []
        status, _ = self.safe_call("/contests/" + cid + "/submissions", "POST",
                                  {"problemSnapshotId": snapshot, "publishedVersion": self.detail(cid)["publishedVersion"],
                                   "language": "java", "code": extended.SUM}, "observer", self.key("not-registered"))
        assert status in (403, 409)
        status, data = self.safe_call("/question/get?id=" + snapshot, actor="observer")
        assert data.get("code") != 0 and data.get("data") is None
        status, _ = self.safe_call("/question/inner/get/id?questionId=" + str(self.qid), actor="anonymous")
        assert status == 403
        status, _ = self.s.http("http://127.0.0.1:" + str(self.s.ports["question"]) +
                               "/api/question/inner/get/id?questionId=" + str(self.qid))
        assert status == 403
        sid = self.practice(); self.completed(sid)
        status, _ = self.safe_call("/submissions/" + str(sid), actor="u0")
        assert status == 403
        return {"unregisteredPrivateStatementHiddenAfterStart": True, "unregisteredSubmissionDenied": True,
                "snapshotNotAccessibleThroughLegacyQuestion": True, "adminFlagCannotGrantRole": True,
                "otherUsersSubmissionDenied": True, "internalEndpointsDeniedViaGatewayAndDirect": True}


class Stack(acceptance.Acceptance):
    def exercise(self):
        if not self.skip_baseline:
            acceptance.native.Stack.exercise(self)
            self.event("CURRENT_ISOLATED_BROWSER_BASELINE_PASSED")
        else:
            self.accounts()
        self.ok = False
        cases = Cases(self)
        tests = [
            ("fixtures", [], cases.fixtures),
            ("submission-idempotency", ["JV01"], cases.jv01),
            ("api-crash-commit-windows", ["JV02"], cases.jv02),
            ("publisher-return-confirm-loss-disconnect", ["JV03"], cases.jv03),
            ("two-consumers-precise-lock-observer", ["JV04"], cases.jv04_precise),
            ("worker-sigkill-token-generation-fence", ["JV05", "JV20"], cases.jv05),
            ("commit-before-ack-redelivery", ["JV06"], cases.jv06),
            ("limited-registration", ["JV09"], cases.jv09),
            ("reservation-api-crash-before-db", ["JV10"], cases.jv10),
            ("confirmation-marker-failure", ["JV11"], cases.jv11_marker),
            ("redis-down-confirmation-and-release", ["JV11", "JV22"], cases.jv11_redis),
            ("reservation-before-close-consume-after", ["JV12"], cases.jv12),
            ("registration-cancel-race", ["JV13"], cases.jv13),
            ("submission-cancel-race", ["JV13"], cases.cancel_submit),
            ("private-snapshot-and-submission-permissions", ["JV14"], cases.jv14_permissions),
            ("accepted-snapshot-edited-before-execution", ["JV15"], cases.jv15_exact),
            ("budget-exhaustion-manual-recovery", ["JV19"], cases.jv19),
            ("confirmed-unclaimed-recovery", ["JV21"], cases.jv21),
            ("late-confirm-after-release", ["JV22"], cases.jv22),
            ("cancel-committed-redis-down", ["JV23"], cases.jv23),
            ("ledger-initialization-loss-persistence", ["JV24", "JV28"], cases.jv24),
            ("complete-redis-ledger-loss", ["JV24"], cases.complete_loss),
            ("mysql-clock-skew-both-directions", ["JV24"], cases.clock_skew),
            ("score-race-rebuild-finalization", ["JV16", "JV17", "JV18", "JV26", "JV27"], cases.ranking),
            ("practice-spec-frozen-at-acceptance", ["JV29"], cases.jv29),
        ]
        for name, jvs, work in tests:
            cases.run_case(name, jvs, work)
        self.ok = all(row["status"] == "PASS" for row in cases.rows)
        assert self.ok, "Business matrix contains failures; retained all results"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtimes", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--skip-baseline", action="store_true")
    args = parser.parse_args()
    evidence = pathlib.Path(args.evidence).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    for module in (acceptance, extended, followup):
        module.ROOT = evidence
    env = json.loads(pathlib.Path(args.environment).read_text())
    os.environ.update(CODEOJ_SANDBOX_MODE="isolated", CODEOJ_DOCKER_BINARY=env["docker"],
                      CODEOJ_DOCKER_ENDPOINT=env["endpoint"], CODEOJ_ISOLATION_IMAGE=env["image"])
    stack = Stack(args.runtimes, evidence / "native")
    stack.skip_baseline = args.skip_baseline
    def interrupted(signum, frame): raise KeyboardInterrupt("Cleaning owned final acceptance stack")
    signal.signal(signal.SIGTERM, interrupted)
    try:
        stack.verify_artifacts(); stack.setup_infrastructure(); stack.setup_services(); stack.exercise()
    finally:
        stack.cleanup()


if __name__ == "__main__": main()
