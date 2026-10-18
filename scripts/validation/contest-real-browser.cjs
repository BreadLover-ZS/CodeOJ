/* Real HTTP only: no page.route/request interception and no fabricated sessions. */
const fs = require('fs'), path = require('path'), http = require('http'), assert = require('assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fixture = JSON.parse(fs.readFileSync(process.env.CODEOJ_FIXTURE_CONFIG_FILE, 'utf8'));
const root = path.resolve(__dirname, '../../codeoj-frontend/dist'), gateway = new URL(fixture.gateway);
const types = { '.js': 'application/javascript', '.css': 'text/css', '.html': 'text/html', '.ttf': 'font/ttf', '.json': 'application/json' };
const server = http.createServer((req, res) => {
  if (req.url.startsWith('/api/')) {
    const proxy = http.request({ hostname: gateway.hostname, port: gateway.port, path: req.url, method: req.method, headers: req.headers }, upstream => { res.writeHead(upstream.statusCode, upstream.headers); upstream.pipe(res); });
    proxy.on('error', () => { res.writeHead(502); res.end('Local gateway unavailable'); }); req.pipe(proxy); return;
  }
  const requested = path.resolve(root, '.' + new URL(req.url, 'http://localhost').pathname);
  const file = requested.startsWith(root + path.sep) && fs.existsSync(requested) && fs.statSync(requested).isFile() ? requested : path.join(root, 'index.html');
  res.setHeader('Content-Type', types[path.extname(file)] || 'application/octet-stream'); fs.createReadStream(file).pipe(res);
});
let browser;
(async () => {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(fixture.frontendPort, '127.0.0.1', resolve); });
  const base = `http://127.0.0.1:${fixture.frontendPort}`, errors = [];
  browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL || 'chrome' });
  async function login(actor) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } }), page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base + '/user/login'); await page.getByPlaceholder('账号', { exact: true }).fill(fixture.accounts[actor]);
    await page.getByPlaceholder('密码', { exact: true }).fill(fixture.password); await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL(url => url.pathname === '/', { timeout: 25000 }); return page;
  }
  const admin = await login('admin'); await admin.goto(base + '/manage/contests'); await admin.getByText('Native local integration', { exact: true }).waitFor();
  await admin.getByRole('button', { name: '管理', exact: true }).click(); await admin.locator('.scoreboard-panel').getByText('临时榜单', { exact: false }).waitFor();
  const ledger = admin.locator('.registration-operations');
  await ledger.getByPlaceholder('必填，将保留在操作记录中').fill('Redis恢复后核验原预留与容量账本');
  await ledger.getByRole('button', { name: '关闭入口核对账本，核对通过后恢复', exact: true }).click();
  await admin.getByText('当前通道：秒杀报名', { exact: false }).waitFor();
  const participant = await login('participant'); await participant.goto(base + '/contests/' + fixture.contestId); await participant.locator('.monaco-editor').waitFor();
  const code = 'public class Main { public static void main(String[] a) throws Exception { java.util.Scanner s=new java.util.Scanner(System.in); System.out.println(s.nextInt()+s.nextInt()); Thread.sleep(100); }}';
  await participant.locator('.monaco-editor textarea').focus(); await participant.keyboard.press('Meta+A'); await participant.keyboard.insertText(code);
  await participant.getByRole('button', { name: '提交当前题目', exact: true }).click(); await participant.getByText(/状态：COMPLETED/).waitFor({ timeout: 45000 });
  assert.match(await participant.getByText(/状态：COMPLETED/).innerText(), /\bAC\b/);
  await participant.locator('.scoreboard-panel').getByRole('button', { name: '刷新榜单', exact: true }).click();
  await participant.locator('.scoreboard-panel').getByText(String(fixture.participantId), { exact: true }).waitFor();
  const observer = await login('observer'); await observer.goto(base + '/contests/' + fixture.contestId);
  await observer.locator('.scoreboard-panel').getByText(String(fixture.participantId), { exact: true }).waitFor();
  assert.equal(await observer.locator('.monaco-editor').count(), 0);
  assert.equal((await observer.locator('.contest-detail').innerText()).includes('Read two integers and output their sum.'), false);
  assert.deepEqual(errors, []);
  process.stdout.write(JSON.stringify({ scope: 'Real login, Redis session, Vue -> gateway -> real services; no HTTP mocks', passed: ['admin browser login', 'real contest administration', 'real Redis registration view', 'admin browser reconciliation after Redis hold', 'participant browser login', 'real Monaco submission and remote native AC', 'real scoreboard', 'nonparticipant scoreboard access without private problem access'], pageErrors: errors }, null, 2) + '\n');
})().catch(error => { console.error(error); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); server.close(); });
