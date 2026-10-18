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
  let registrationMode = "DB_SYNC", revision = 1, reviewState = "MANUAL_REVIEW", reviewCalls = [];
  const adminSettings = () => ({ mode: registrationMode, revision });
  const adminRequests = () => [{ requestId: "9007199254741999", userId: "10", status: reviewState, stateVersion: 3, reclaimState: "RESERVED", attempts: [{ attemptNo: 1, confirmState: "UNKNOWN", returned: false }] }];
  const contest = () => ({ id: "123", title: "浏览器功能样例", contestType: "PRIVATE", capacity: 2, remainingCapacity: registered ? 1 : 2,
    registrationOpenAt: "2026-10-20T00:00:00Z", registrationCloseAt: "2026-10-20T01:00:00Z", startAt: "2026-10-20T02:00:00Z", endAt: "2026-10-20T03:00:00Z",
    status: phase === "FINALIZING" ? "FINALIZING" : "PUBLISHED", phase, version: revision, publishedVersion: 1, registrationReady: true, ruleVersion: "FIRST_AC_V1", registrationMode,
    problems: registered && phase !== "REGISTRATION_OPEN" ? [{ id: "456", problemCode: "A", title: "求和", content: "计算两个数的和。", limits: { timeLimit: 1000, memoryLimit: 131072 } }] : [] });
  const registration = () => ({ requestId: registered ? "789" : null, status: registered ? "CONFIRMED" : "NOT_REGISTERED", qualificationValid: registered, contestStatus: "PUBLISHED" });
  await page.route("**/api/**", async route => {
    const request = route.request(), url = new URL(request.url()); let data, status = 200;
    if (request.method() === "OPTIONS") return route.fulfill({ status: 204, headers: { "access-control-allow-origin": request.headers().origin || "*", "access-control-allow-credentials": "true", "access-control-allow-methods": "GET,POST,PUT,OPTIONS", "access-control-allow-headers": "content-type,idempotency-key" } });
    if (url.pathname.includes("/user/")) data = { id: "99", userName: "本机样例管理员", userRole: "admin" };
    else if (url.pathname === "/api/contests") data = [contest()];
    else if (url.pathname === "/api/contests/123") data = contest();
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
  assert.deepEqual(errors, []);
  process.stdout.write(JSON.stringify({ scope: "mocked HTTP browser smoke", passed: ["contest list", "registration confirmation", "prestart editor hidden", "contest editor", "unknown submission retried with original key and payload after end", "admin draft form", "unknown publication evidence", "paused registration cutover and reconciliation", "versioned review with exact large request ID"], pageErrors: errors }, null, 2) + "\n");
})().catch(e => { console.error(e); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); server.close(); });
