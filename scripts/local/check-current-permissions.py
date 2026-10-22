#!/usr/bin/env python3
"""F03 regression through real Spring Session, Gateway, Feign and private MySQL.

Requires the pinned runtimes from prepare-runtimes.py and freshly packaged services.
Only this run's disposable accounts, questions and database are modified.
"""
import argparse
import datetime
import importlib.util
import json
import pathlib
import signal

spec = importlib.util.spec_from_file_location("native_stack", pathlib.Path(__file__).with_name("run-native-stack.py"))
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class PermissionStack(native.Stack):
    def check(self, name, path, method="GET", body=None, actor="admin", expected="allowed", direct=None):
        if direct:
            reply = self.http("http://127.0.0.1:" + str(self.ports[direct]) + "/api" + path,
                              method, body, self.clients.get(actor))
        else:
            reply = self.api(path, method, body, actor, allow_error=True)
        status, data = reply
        code = data.get("code")
        if expected == "allowed":
            passed = status == 200 and code == 0
        elif expected == "forbidden":
            passed = code in (40101, 40300) and data.get("data") is None
        elif expected == "unauthenticated":
            passed = code == 40100 and data.get("data") is None
        elif expected == "unavailable":
            passed = code in (50000, 50010) and data.get("data") is None
        elif expected == "internal_denied":
            passed = status == 403
        else:
            raise ValueError(expected)
        row = {"case": name, "status": "PASS" if passed else "FAIL", "method": method,
               "path": path, "via": direct or "gateway", "httpStatus": status,
               "businessCode": code, "expected": expected}
        self.results.append(row)
        self.save_results()
        self.event("PERMISSION_CHECK", **row)
        assert passed, row
        return data.get("data")

    def save_results(self):
        (self.evidence / "permission-results.json").write_text(json.dumps(self.results, indent=2) + "\n")

    def cookies(self, actor):
        return [(cookie.name, cookie.value) for handler in self.clients[actor].handlers
                if hasattr(handler, "cookiejar") for cookie in handler.cookiejar]

    def exercise(self):
        self.results = []
        self.accounts()
        original_cookies = {actor: self.cookies(actor) for actor in self.clients}
        owner_id = self.state["participantId"]
        admin_id = self.state["adminId"]
        operator_id = self.state["observerId"]
        question = {"title": "F03 private fixture", "content": "Output 3", "tags": ["fixture"],
                    "answer": "private-answer", "judgeCase": [{"input": "1 2", "output": "3"}],
                    "judgeConfig": {"timeLimit": 1000, "memoryLimit": 262144}}
        qid = str(self.api("/question/add", "POST", question, "participant"))
        full = "/question/get?id=" + qid
        listing = {"current": 1, "pageSize": 10}
        self.check("current administrator reads private question", full)
        self.check("owner reads private question", full, actor="participant")
        self.check("ordinary non-owner cannot read private question", full, actor="observer", expected="forbidden")
        self.check("anonymous private question is unauthenticated", full, actor="anonymous", expected="unauthenticated")
        public = self.check("public question view remains readable", "/question/get/vo?id=" + qid, actor="anonymous")
        assert "judgeCase" not in public and "answer" not in public
        self.check("current administrator updates question", "/question/update", "POST", {"id": qid, "title": question["title"]})
        self.check("current administrator lists private questions", "/question/list/page", "POST", listing)
        self.check("current administrator manages users", "/user/list/page", "POST", listing)
        self.check("current administrator sees judge operations", "/admin/operations")
        code = 'public class Main { public static void main(String[] args) { System.out.println(3); } }'
        submission = str(self.api("/question/question_submit/do", "POST",
                                 {"questionId": qid, "language": "java", "code": code},
                                 "participant", key="f03-practice-ac"))
        result = self.wait("judge", lambda: (lambda item: item if item.get("executionStatus") == "COMPLETED" else None)(
            self.api("/submissions/" + submission, actor="participant")), 60)
        assert result["judgeInfo"]["verdict"] == "AC"
        self.event("REAL_PRACTICE_JUDGE_AC", submissionId=submission)
        self.check("current administrator reads another users submission", "/submissions/" + submission)
        self.check("ordinary session initially has no user management role", "/user/get?id=" + owner_id,
                   actor="observer", expected="forbidden")
        # Real management API changes the database role; no re-login or Session replacement.
        self.api("/user/update", "POST", {"id": operator_id, "userRole": "admin"})
        self.check("promotion applies to same old ordinary Session", "/user/get?id=" + owner_id, actor="observer")
        self.check("promotion applies across service boundary", full, actor="observer")

        now = datetime.datetime.now(datetime.timezone.utc)
        def at(seconds):
            return (now + datetime.timedelta(seconds=seconds)).isoformat().replace("+00:00", "Z")
        draft = {"title": "F03 contest", "contestType": "PRIVATE", "capacity": 1,
                 "registrationOpenAt": at(60), "registrationCloseAt": at(120),
                 "startAt": at(180), "endAt": at(240),
                 "problems": [{"problemCode": "A", "title": "fixture", "content": "Output 3",
                               "limits": {"timeLimit": 1000, "memoryLimit": 262144},
                               "cases": [{"input": "1 2", "output": "3"}]}]}
        contest = self.api("/contests", "POST", draft)
        contest_admin = "/contests/" + contest["id"] + "?admin=true"
        self.check("administrator contest control before demotion", contest_admin)
        self.api("/user/update", "POST", {"id": admin_id, "userRole": "user"}, "observer")
        assert self.sql("SELECT userRole FROM user WHERE id=" + admin_id) == "user"

        self.check("F03 revoked administrator cannot read private judge cases", full, expected="forbidden")
        self.check("F03 revoked administrator cannot update question", "/question/update", "POST",
                   {"id": qid, "title": "MUST_NOT_BE_WRITTEN"}, expected="forbidden")
        self.check("revoked administrator cannot edit another owners question", "/question/edit", "POST",
                   {"id": qid, "title": "MUST_NOT_BE_WRITTEN"}, expected="forbidden")
        self.check("revoked administrator cannot delete another owners question", "/question/delete", "POST",
                   {"id": qid}, expected="forbidden")
        self.check("revoked administrator cannot list hidden questions", "/question/list/page", "POST",
                   listing, expected="forbidden")
        self.check("revoked administrator cannot manage contest", contest_admin, expected="forbidden")
        self.check("revoked administrator cannot read another users submission", "/submissions/" + submission,
                   expected="forbidden")
        projection = self.check("revoked administrator legacy submission list remains sanitized",
                                "/question/question_submit/list/page", "POST", {**listing, "questionId": qid})
        assert projection["records"] and all(item.get("code") is None for item in projection["records"])
        owner_projection = self.check("owner legacy submission list retains own source",
                                      "/question/question_submit/list/page", "POST",
                                      {**listing, "questionId": qid}, "participant")
        assert owner_projection["records"][0]["code"] == code
        for path, method, body in [
            ("/admin/operations", "GET", None),
            ("/admin/operations/1", "GET", None),
            ("/admin/operations/1/retry", "POST", {}),
            ("/user/get?id=" + owner_id, "GET", None),
            ("/user/get/vo?id=" + owner_id, "GET", None),
            ("/user/list/page", "POST", listing),
            ("/user/add", "POST", {"userAccount": "must_not_exist", "userRole": "admin"}),
            ("/user/update", "POST", {"id": admin_id, "userRole": "admin"}),
            ("/user/delete", "POST", {"id": owner_id}),
        ]:
            self.check("revoked administrator denied " + path, path, method, body, expected="forbidden")
        self.check("direct question service enforces revocation", full, direct="question", expected="forbidden")
        self.check("direct user service enforces revocation", "/user/get?id=" + owner_id,
                   direct="user", expected="forbidden")
        assert self.sql("SELECT title FROM question WHERE id=" + qid) == question["title"]
        assert self.sql("SELECT COUNT(*) FROM user WHERE userAccount='must_not_exist'") == "0"
        self.check("owner still edits own question", "/question/edit", "POST",
                   {"id": qid, "title": question["title"]}, "participant")

        # Banning an owner also removes privileges from routes without @AuthCheck.
        self.api("/user/update", "POST", {"id": owner_id, "userRole": "ban"}, "observer")
        for path, method, body in [
            (full, "GET", None),
            ("/question/edit", "POST", {"id": qid, "title": "MUST_NOT_BE_WRITTEN"}),
            ("/question/delete", "POST", {"id": qid}),
            ("/question/add", "POST", question),
            ("/question/question_submit/do", "POST", {"questionId": qid, "language": "java",
                                                     "code": "public class Main {}"}),
            ("/user/get/login", "GET", None),
            ("/user/update/my", "POST", {"userName": "MUST_NOT_BE_WRITTEN"}),
        ]:
            self.check("banned old owner Session denied " + path, path, method, body,
                       "participant", expected="forbidden")
        self.api("/user/update", "POST", {"id": owner_id, "userRole": "user"}, "observer")
        self.check("unban restores owner access without relogin", full, actor="participant")
        self.api("/user/delete", "POST", {"id": owner_id}, "observer")
        self.check("logically deleted owner loses question access", full, actor="participant", expected="unauthenticated")
        self.check("logically deleted owner loses local profile access", "/user/get/login",
                   actor="participant", expected="unauthenticated")

        # An authoritative lookup outage cannot revive an old administrator role.
        self.stop("user")
        self.check("user service outage never falls back to old administrator", full, expected="unavailable")
        self.check("user service outage prevents privileged update", "/question/update", "POST",
                   {"id": qid, "title": "MUST_NOT_BE_WRITTEN"}, expected="unavailable")
        assert self.sql("SELECT title FROM question WHERE id=" + qid) == question["title"]
        self.restart("user")
        self.wait("user", lambda: self.api("/user/get/login", actor="observer", allow_error=True)[1].get("code") == 0)
        self.check("revocation survives user service restart", full, expected="forbidden")
        self.check("current administrator still works after restart", full, actor="observer")
        self.api("/user/update", "POST", {"id": admin_id, "userRole": "admin"}, "observer")
        self.check("regrant applies to original Session", full)
        self.check("regranted administrator can update", "/question/update", "POST",
                   {"id": qid, "title": question["title"]})
        self.check("gateway rejects unauthenticated internal endpoint", "/user/inner/get/id?userId=" + admin_id,
                   actor="anonymous", expected="internal_denied")
        self.check("direct service rejects unauthenticated internal endpoint", "/user/inner/get/id?userId=" + admin_id,
                   actor="anonymous", direct="user", expected="internal_denied")
        assert all(original_cookies[actor] and original_cookies[actor] == self.cookies(actor) for actor in self.clients)
        facts = {"sameSessionCookiesThroughout": True, "roleChangesViaRealManagementApi": True,
                 "privateQuestionUnmodifiedByDeniedRequests": True,
                 "pass": len(self.results), "fail": 0,
                 "scope": "F03 and related account authorization only; no full JV matrix, isolation or load acceptance"}
        (self.evidence / "summary.json").write_text(json.dumps(facts, indent=2) + "\n")
        self.ok = True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtimes", required=True)
    parser.add_argument("--evidence", required=True)
    args = parser.parse_args()
    def interrupted(signum, frame):
        raise KeyboardInterrupt("Fixture interrupted; cleaning owned processes")
    signal.signal(signal.SIGTERM, interrupted)
    stack = PermissionStack(args.runtimes, args.evidence)
    try:
        stack.verify_artifacts()
        stack.setup_infrastructure()
        stack.setup_services()
        stack.exercise()
    finally:
        stack.cleanup()


if __name__ == "__main__":
    main()
