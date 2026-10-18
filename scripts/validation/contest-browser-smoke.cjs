/* Browser-only contract smoke with explicitly mocked HTTP authority, never service acceptance. */
const fs = require("fs"), path = require("path"), http = require("http"), assert = require("assert/strict");
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const root = path.resolve(__dirname, "../../codeoj-frontend/dist");
const types = { ".js": "application/javascript", ".css": "text/css", ".html": "text/html", ".json": "application/json", ".ttf": "font/ttf" };
const server = http.createServer((req, res) => {
  const requested = path.resolve(root, "." + new URL(req.url, "http://localhost").pathname);
  const file = requested.startsWith(root + path.sep) && fs.existsSync(requested) && fs.statSync(requested).isFile() ? requested : path.join(root, "index.html");
  res.setHeader("Content-Type", types[path.extname(file)] || "application/octet-stream"); fs.createReadStream(file).pipe(res);
});
let browser;
(async () => {
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = []; page.on("pageerror", e => errors.push(e.message));
  let registered = false, phase = "REGISTRATION_OPEN", failures = 0, submissionCalls = [];
  let scoreVersion = 1, scoreRevision = 4, finalVersion = null, scoreCalls = [];
  let registrationMode = "DB_SYNC", revision = 1, reviewState = "MANUAL_REVIEW", reviewCalls = [];
  const adminSettings = () => ({ mode: registrationMode, revision });
  const adminRequests = () => [{ requestId: "9007199254741999", userId: "10", status: reviewState, stateVersion: 3, reclaimState: "RESERVED", attempts: [{ attemptNo: 1, confirmState: "UNKNOWN", returned: false }] }];
  const contest = () => ({ id: "123", title: "浏览器功能样例", contestType: "PRIVATE", capacity: 2, remainingCapacity: registered ? 1 : 2,
    registrationOpenAt: "2026-10-20T00:00:00Z", registrationCloseAt: "2026-10-20T01:00:00Z", startAt: "2026-10-20T02:00:00Z", endAt: "2026-10-20T03:00:00Z",
    status: ["FINALIZING", "FINISHED"].includes(phase) ? phase : "PUBLISHED", phase, version: revision, publishedVersion: 1, registrationReady: true, ruleVersion: "FIRST_AC_V1", registrationMode,
    problems: registered && phase !== "REGISTRATION_OPEN" ? [{ id: "456", problemCode: "A", title: "求和", content: "计算两个数的和。", limits: { timeLimit: 1000, memoryLimit: 131072 } }] : [] });
  const registration = () => ({ requestId: registered ? "789" : null, status: registered ? "CONFIRMED" : "NOT_REGISTERED", qualificationValid: registered, contestStatus: "PUBLISHED" });
  await page.route("**/api/**", async route => {
    const request = route.request(), url = new URL(request.url()); let data, status = 200;
    if (request.method() === "OPTIONS") return route.fulfill({ status: 204, headers: { "access-control-allow-origin": request.headers().origin || "*", "access-control-allow-credentials": "true", "access-control-allow-methods": "GET,POST,PUT,OPTIONS", "access-control-allow-headers": "content-type,idempotency-key" } });
    if (url.pathname.includes("/user/")) data = { id: "99", userName: "本机样例管理员", userRole: "admin" };
    else if (url.pathname === "/api/contests") data = [contest()];
    else if (url.pathname === "/api/contests/123") data = contest();
    else if (url.pathname === "/api/contests/123/scoreboard") {
      const version = Number(url.searchParams.get("version") || scoreVersion), page = Number(url.searchParams.get("page") || 0);
      data = { contestVersion: revision, projectionVersion: version, activeProjectionVersion: scoreVersion, revision: scoreRevision, ruleVersion: "FIRST_AC_V1", status: contest().status,
        final: version === finalVersion, finalVersion, asOf: "2026-10-20T03:01:00Z", pendingCount: 0, projectionPendingCount: 0, total: 21,
        entries: page === 0 ? [{ userId: "10", rank: 1, solvedCount: 2, achievedAt: "2026-10-20T02:20:00Z" }, { userId: "11", rank: 1, solvedCount: 2, achievedAt: "2026-10-20T02:20:00Z" }, { userId: "12", rank: 3, solvedCount: 0, achievedAt: null }] : [{ userId: "9007199254741999", rank: 3, solvedCount: 0, achievedAt: null }] };
    }
    else if (url.pathname === "/api/contests/123/scoreboard/operations") data = { pending: [], publications: [] };
    else if (url.pathname.startsWith("/api/contests/123/scoreboard/")) {
      const action = url.pathname.split("/").pop(), body = request.postDataJSON();
      assert.equal(body.expectedVersion, revision); assert.equal(body.expectedProjectionVersion, scoreVersion); assert.equal(body.expectedRevision, scoreRevision); assert.ok(body.reason.trim());
      scoreCalls.push({ action, body }); revision++;
      if (action === "rebuild") { scoreVersion++; scoreRevision++; data = { matchedPrevious: false }; }
      else if (action === "finish") { finalVersion = scoreVersion; phase = "FINISHED"; scoreRevision++; data = true; }
      else if (action === "revise") { scoreVersion++; finalVersion = scoreVersion; scoreRevision++; data = { matchedPrevious: true }; }
      else data = true;
    }
    else if (url.pathname === "/api/contests/123/registration-admin") data = { settings: adminSettings(), requests: adminRequests() };
    else if (url.pathname.endsWith("/registration-admin/prepare")) { registrationMode = "REDIS_PAUSED"; revision = 2; data = adminSettings(); }
    else if (url.pathname.endsWith("/registration-admin/reconcile")) { registrationMode = "REDIS_READY"; revision = 4; data = adminSettings(); }
    else if (url.pathname.endsWith("/requests/9007199254741999/review")) { reviewCalls.push(request.postDataJSON()); reviewState = "PROCESSING"; data = true; }
    else if (url.pathname.endsWith("/registrations/me")) data = registration();
    else if (url.pathname.endsWith("/registrations")) { registered = true; data = registration(); status = 202; }
    else if (url.pathname.endsWith("/submissions")) {
      submissionCalls.push({ key: request.headers()["idempotency-key"], body: request.postDataJSON() });
      if (failures++ === 0) return route.abort("failed");
      data = "10001"; status = 202;
    } else if (url.pathname === "/api/submissions/10001") data = { id: "10001", executionStatus: "COMPLETED", judgeInfo: { verdict: "AC", time: 10, memory: 1000 } };
    else return route.fulfill({ status: 404, body: "unexpected mock endpoint" });
    await route.fulfill({ status, contentType: "application/json", headers: { "access-control-allow-origin": request.headers().origin || "*", "access-control-allow-credentials": "true" }, body: JSON.stringify({ code: 0, data }) });
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  await page.goto(base + "/contests"); await page.getByText("浏览器功能样例", { exact: true }).waitFor();
  await page.getByRole("link", { name: "查看比赛" }).click(); await page.getByRole("button", { name: "报名", exact: true }).click();
  await page.getByText("已确认", { exact: false }).waitFor();
  assert.equal(await page.locator(".monaco-editor").count(), 0, "no prestart editor");
  const publicBoard = page.locator(".scoreboard-panel"); await publicBoard.getByText("临时榜单", { exact: false }).waitFor();
  const ties = publicBoard.getByRole("row").filter({ has: page.getByRole("cell", { name: "1", exact: true }) }); assert.equal(await ties.count(), 2);
  await publicBoard.getByRole("button", { name: "下一页榜单", exact: true }).click(); await publicBoard.getByText("9007199254741999", { exact: true }).waitFor();
  assert.equal(await publicBoard.getByRole("row").filter({ hasText: "9007199254741999" }).getByRole("cell").first().textContent(), "3");
  phase = "RUNNING"; await page.getByRole("button", { name: "刷新状态" }).click(); await page.locator(".monaco-editor").waitFor();
  await page.getByRole("button", { name: "提交当前题目", exact: true }).click(); await page.getByRole("button", { name: "重试上次未确认的提交" }).waitFor();
  phase = "FINALIZING"; await page.getByRole("button", { name: "刷新状态" }).click(); await page.getByRole("button", { name: "重试上次未确认的提交" }).click();
  await page.getByText("提交 10001", { exact: true }).waitFor();
  assert.equal(submissionCalls.length, 2); assert.equal(submissionCalls[0].key, submissionCalls[1].key); assert.deepEqual(submissionCalls[0].body, submissionCalls[1].body);
  await page.goto(base + "/manage/contests"); await page.getByRole("button", { name: "保存草稿", exact: true }).waitFor();
  await page.getByText("隐藏判题用例（只供管理员配置）", { exact: true }).waitFor();
  await page.getByRole("button", { name: "管理", exact: true }).click();
  await page.getByText("结果未知，名额继续保留", { exact: false }).waitFor();
  const reason = page.getByPlaceholder("必填，将保留在操作记录中"); await reason.fill("已核对原预留及发布证据");
  await page.getByRole("button", { name: "关闭普通报名，准备秒杀通道", exact: true }).click();
  await page.getByRole("button", { name: "关闭入口核对账本，核对通过后恢复", exact: true }).click();
  await page.getByText("当前通道：秒杀报名", { exact: false }).waitFor();
  await page.getByRole("button", { name: "继续确认原请求", exact: true }).click();
  await page.getByText("确认中", { exact: true }).waitFor();
  assert.equal(reviewCalls.length, 1); assert.equal(reviewCalls[0].expectedVersion, 3); assert.equal(reviewCalls[0].decision, "CONTINUE"); assert.equal(reviewCalls[0].reason, "已核对原预留及发布证据");
  const scores = page.locator(".scoreboard-panel");
  await scores.getByRole("button", { name: "刷新榜单", exact: true }).click();
  await scores.getByPlaceholder("成绩操作原因（必填，保留审计）").fill("已按权威提交核对成绩");
  await scores.getByRole("button", { name: "清除榜单缓存", exact: true }).click(); await scores.getByText("成绩操作已完成", { exact: true }).waitFor();
  await scores.getByRole("button", { name: "从提交重建成绩", exact: true }).click(); await scores.getByText("已重建新版本，发现原投影与权威记录不一致，请核查后结算。", { exact: true }).waitFor();
  await scores.getByRole("button", { name: "完成结算", exact: true }).click(); await page.getByRole("button", { name: "确定", exact: true }).click(); await scores.getByText("最终榜单", { exact: false }).waitFor();
  await scores.getByPlaceholder("历史投影版本（留空为当前版本）").fill("1"); await scores.getByRole("button", { name: "查看榜单版本", exact: true }).click(); await scores.getByText("临时榜单", { exact: false }).waitFor();
  assert.equal(await scores.getByRole("button", { name: "清除榜单缓存", exact: true }).isDisabled(), true);
  assert.deepEqual(scoreCalls.map(call => call.action), ["cache-clear", "rebuild", "finish"]);
  assert.deepEqual(errors, []);
  process.stdout.write(JSON.stringify({ scope: "mocked HTTP browser smoke", passed: ["contest list", "registration confirmation", "prestart editor hidden", "contest editor", "unknown submission retried with original key and payload after end", "admin draft form", "unknown publication evidence", "paused registration cutover and reconciliation", "versioned review with exact large request ID", "provisional scoreboard ties", "cross-page rank and exact large user ID", "versioned cache invalidation", "projection rebuild mismatch surfaced", "final settlement", "historical scoreboard with mutations disabled"], pageErrors: errors }, null, 2) + "\n");
})().catch(e => { console.error(e); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); server.close(); });
