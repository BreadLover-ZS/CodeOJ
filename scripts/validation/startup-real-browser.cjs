/* Smoke the actually running UI/proxy/services. No HTTP interception or fake sessions. */
const fs=require('fs'), assert=require('assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const access=JSON.parse(fs.readFileSync(process.env.CODEOJ_STARTUP_ACCESS,'utf8'));
let browser;
(async()=>{
  browser=await chromium.launch({headless:true, channel:process.env.PLAYWRIGHT_BROWSER_CHANNEL || 'chrome'});
  const errors=[];
  async function login(actor){
    const page=await (await browser.newContext()).newPage();
    page.on('pageerror', e=>errors.push(e.message));
    await page.goto(access.frontend+'/user/login');
    await page.getByPlaceholder('账号',{exact:true}).fill(access.accounts[actor]);
    await page.getByPlaceholder('密码',{exact:true}).fill(access.password);
    await page.getByRole('button',{name:'登录',exact:true}).click();
    await page.waitForURL(url=>url.pathname==='/',{timeout:25000});
    return page;
  }
  const admin=await login('admin');
  await admin.goto(access.frontend+'/manage/contests');
  await admin.getByText('CodeOJ local startup',{exact:true}).waitFor();
  const participant=await login('participant');
  await participant.goto(access.frontend+'/contests/'+access.contestId);
  await participant.locator('.monaco-editor').waitFor();
  await participant.locator('.monaco-editor textarea').focus();
  await participant.keyboard.press('Meta+A');
  await participant.keyboard.insertText('public class Main { public static void main(String[] a) { java.util.Scanner s=new java.util.Scanner(System.in); System.out.print(s.nextInt()+s.nextInt()); }}');
  await participant.getByRole('button',{name:'提交当前题目',exact:true}).click();
  await participant.getByText(/状态：COMPLETED/).waitFor({timeout:45000});
  assert.match(await participant.getByText(/状态：COMPLETED/).innerText(),/\bAC\b/);
  const observer=await login('observer');
  await observer.goto(access.frontend+'/contests/'+access.contestId);
  await observer.locator('.scoreboard-panel').waitFor();
  assert.equal(await observer.locator('.monaco-editor').count(),0);
  assert.deepEqual(errors,[]);
  console.log(JSON.stringify({passed:true,scope:'Real local startup UI -> proxy -> gateway -> isolated Java; no mocks',checks:['admin login and contest list','participant login and isolated AC','observer scoreboard without editor'],pageErrors:errors}));
})().catch(()=>{console.error('STARTUP_BROWSER_CHECK_FAILED');process.exitCode=1;}).finally(async()=>{if(browser)await browser.close();});
