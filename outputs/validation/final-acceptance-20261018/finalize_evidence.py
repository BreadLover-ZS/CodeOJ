"""Aggregate already executed checks, verify cleanup, write the read-only acceptance report."""
from pathlib import Path
import json,re,subprocess,hashlib,xml.etree.ElementTree as ET,socket,datetime,collections,os,shutil
ROOT=Path(__file__).resolve().parent;REPO=ROOT.parents[2]
def read(name):return json.loads((ROOT/name).read_text())
def write(name,value):(ROOT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
base=read('baseline.json');assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()==base['commit']
assert subprocess.run(['git','diff','--quiet','HEAD','--'],cwd=REPO).returncode==0,'Tracked sources changed'
assert read('snapshot-results.json')['status']=='PASS'
runs={name:read(name+'/run.json') for name in ['native','native-followup','native-diagnostics','native-snapshot']}
assert all(r['temporaryDataRemoved'] for r in runs.values())
assert runs['native-snapshot']['passed']
log=(ROOT/'backend-regression.log').read_text();classes=re.findall(r'\[INFO\] Running ([\w.]+)',log)
tests=[]
for cls in classes:
 candidates=list((REPO/'codeoj-backend').glob('*/target/surefire-reports/TEST-'+cls+'.xml'));assert len(candidates)==1,cls
 suite=ET.parse(candidates[0]).getroot();tests.append({'class':cls,**{k:int(suite.get(k,'0')) for k in ['tests','failures','errors','skipped']},'cases':[t.attrib['name'] for t in suite.findall('testcase')]})
totals={k:sum(t[k] for t in tests) for k in ['tests','failures','errors','skipped']};totals['classes']=len(tests)
assert totals=={'tests':208,'failures':0,'errors':0,'skipped':0,'classes':24},totals
write('backend-test-summary.json',{'method':'Only classes printed as Running in this run; do not count stale surefire XML files.',**totals,'suites':tests})
ports=[]
for name,r in runs.items():
 for service,port in r['ports'].items():
  with socket.socket() as sock:active=sock.connect_ex(('127.0.0.1',port))==0
  ports.append({'fixture':name,'service':service,'port':port,'listeningAfterCleanup':active})
assert not any(p['listeningAfterCleanup'] for p in ports),'Fixture port still listening'
work=Path(base['work']);assert work.parent==Path('/private/tmp') and work.name=='codeoj-final-acceptance-p3f4rsrl' and work.stat().st_uid==os.getuid()
assert not any(Path('/private/tmp').glob('codeoj-native-stack-*')),'An owned native fixture remains; inspect before deleting runtime'
shutil.rmtree(work)
write('cleanup.json',{'ports':ports,'allPortsClosed':True,'fixtureRunRecords':len(runs),'temporaryFixtureDataRemoved':True,'temporaryRuntimeRootRemoved':not work.exists(),'originalOutputDirectoryUntouched':True,'businessSourceUnchanged':True})
write('findings.json',[{'id':'F03','priority':'P1','status':'OPEN','title':'管理员撤权后，普通题目接口仍信任旧 Session 角色','trigger':'真实管理员登录；在本轮私有 MySQL 将其 userRole 从 admin 改为 user；不重新登录，复用原 Session。该用户不是目标题目所有者。','observed':[{'endpoint':'GET /api/question/get?id=<fixture-question>','http':200,'businessCode':0,'exposedFieldNames':['judgeCase']},{'endpoint':'POST /api/question/update','http':200,'businessCode':0,'updateReportedSuccess':True,'probeWrite':'仅将题目标题写回原值'}],'control':'同一已撤权 Session 查询 GET /api/contests/<id>?admin=true 返回 403。','rootCause':'UserFeignClient.getLoginUser 默认方法直接返回 Session User，isAdmin 使用其旧角色；AuthInterceptor 同样只读 Session。旧题目接口缺少现时 DB 角色校验。','locations':[{'path':'codeoj-backend/codeoj-backend-service-client/src/main/java/com/codeoj/codeojbackendserviceclient/service/UserFeignClient.java','line':48},{'path':'codeoj-backend/codeoj-backend-service-client/src/main/java/com/codeoj/codeojbackendserviceclient/aop/AuthInterceptor.java','line':38},{'path':'codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/controller/QuestionController.java','line':170},{'path':'codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/controller/QuestionController.java','line':125}],'evidence':'permission-results.json','scope':'已实测旧题目读取和更新；其他依赖同一缓存角色的管理接口需修复时一并审计，未声称逐个复现。','sourceModified':False}])
old=json.loads((REPO/'outputs/validation/acceptance-20261018/jv-matrix.json').read_text())
notes={
'JV01':'实际 HTTP 相同键同 ID、改代码 409，MySQL 仅一条受理事实。',
'JV02':'真实 API 在提交前/后 SIGKILL；前者事务回滚，后者持久 Outbox 在恢复后完成。',
'JV03':'真实不可路由 Return、代理丢 Confirm 帧、Broker 断连均记录并恢复；UNKNOWN 不冒充失败。',
'JV04':'两个真实 judge 进程、重复 Broker 投递；观察到 InnoDB 锁等待，最终一个有效 attempt。以 SLEEP(2) 纠正首轮 SLEEP(5) 超过 Feign 超时的夹具问题。',
'JV05':'RUNNING worker SIGKILL，真实租约过期接管；旧 token 心跳/结果均 false；总计两个 attempt。',
'JV06':'结果已提交，代理丢消费者 ACK，Broker 确认仍 unacked 后杀 worker；重投终态不重判/不多建结果事件。等效验证 Broker 未收到 ACK 的窗口，未声称断在 basicAck 调用之前。',
'JV07':'本轮真实 javac/java 测试覆盖死循环、stderr 刷屏、阻塞 stdin、双流与工作目录清理；范围为直接 JVM，非任意进程树。',
'JV08':'仅 RSS 观测/超限有本轮实际测试；PID、网络、文件与容器级硬隔离未实现，本机可信输入边界后置。',
'JV09':'8 个真实用户并发争 2 名额；MySQL 资格 2、剩余 0、原键请求身份稳定，Redis 账本确认。属于功能竞争，不是压测。',
'JV10':'Redis 已预留、DB 插入受阻时杀 API；重启以原 requestId/eventId 补建并确认。',
'JV11':'实际停 Redis，确认事务已提交且标记未完成；另在 FAILED 提交前停 Redis，释放待办持久保留；恢复后仅确认/释放一次。',
'JV12':'真实绑定控制消费晚于关闭时间；原有效预留确认，新请求 409。',
'JV13':'真实 MySQL 取消与报名 HTTP/提交竞争；本次取消获胜，取消后无新资格/受理，新键 409。未声称穷举全部调度顺序。',
'JV14':'私有题面、他人提交、admin=true、内部入口均拒绝越权；扩展撤权检查发现 F03：旧 Session 仍可读取普通题隐藏用例并更新题目，因此权限项保守记 FAIL。',
'JV15':'独立实例如实时钟赛中受理，暂停 judge 后编辑源题，再启动执行：比赛固定快照 AC、新普通练习 WA；标题/hash 不变，练习 acceptedNum 未受比赛影响。',
'JV16':'真实投影与 Broker 消费验证重复 AC、较早提交迟到；用于精确并列的五条 acceptedAt 由隔离 DB 显式塑形，非自然 HTTP 时间证据。',
'JV17':'一条自然 acceptedAt 在截止前受理、真实判题跨截止；pending 时结束 409；终态后最终版本 2。另基线验证原键重放和新键拒绝。',
'JV18':'真实缓存 epoch 清理，MySQL 权威重建与手算一致；零分、并列样本时间使用显式夹具。',
'JV19':'真实沙箱停机耗尽 3 次预算进入 NEEDS_ATTENTION；未加额度重试 409，显式 +1 审计后第 4 次 AC。',
'JV20':'真实旧调度消息在新代次执行后再投递，旧 token/代次不能领取或覆盖；有效 attempt 未增加。',
'JV21':'真实 Broker 已 ACK 而 judge 停止超过扫描阈值；业务恢复增加代次后完成一次有效执行。',
'JV22':'DB FAILED 先提交、Redis 释放后投原迟到事件及重复事件不确认；Redis 故障窗口待释放状态恢复后收敛。',
'JV23':'真实取消事务提交前停 Redis；DB CANCELLED 时迟到消费者不发新资格；恢复后关闸/释放完成，新报名 409。',
'JV24':'重复 reconcile 不重置；部分/全部账本丢失返回 503 保持暂停；实际 MySQL 连接时钟正负 10 秒偏差均暂停预留（未改宿主/Redis 时钟）。',
'JV25':'本轮旧 token、worker SIGKILL 有据；跨沙箱管理器/重启的持久运行登记与任意残留进程树回收未实现，完整物理执行约束后置。',
'JV26':'真实同用户两题同时 RUNNING 并完成，积分不丢；4 用户并列/零分跨页名次正确。精确并列时间为显式 DB 夹具。',
'JV27':'真实重建中 SQL 失败回滚版本 1；首次迟到成绩投递与重建竞争，读者仅见完整版本 2；终态/收据一致。',
'JV28':'本轮持久账本 TTL=-1；基线真实 Redis AOF 重启恢复。非 TTL 等待演示，保留策略不自动归档。',
'JV29':'真实 API 排队旧普通练习后改原题，新旧提交分别 WA/AC，私有判题 hash 不同。'}
refs={}
for filename in ['extended-results.json','followup-results.json']:
 for row in read(filename):
  if row['status']=='PASS':
   for jv in row['jv']:refs.setdefault(jv,[]).append(filename+'#'+row['case'])
refs['JV04']=['followup-results.json#two-consumer-overlap-precise-observer'];refs['JV14']=['permission-results.json','findings.json'];refs['JV15']=['snapshot-results.json','diagnostic-results.json']
refs['JV07']=['backend-test-summary.json'];refs['JV08']=['backend-test-summary.json'];refs['JV17']+=['native/database-facts.json'];refs['JV28']+=['native/run.json']
matrix=[]
for row in old:
 jv=row['id'];status='UNTESTED' if jv in ['JV08','JV25'] else 'FAIL' if jv=='JV14' else 'PASS'
 matrix.append({'id':jv,'requirement':row['requirement'],'realScenarioStatus':status,'evidenceAndGap':notes[jv],'evidence':refs.get(jv,[]),'scope':'本轮已执行的本机功能验收范围；不等于生产或任意恶意程序安全验收'})
write('jv-matrix.json',matrix);counts=dict(collections.Counter(r['realScenarioStatus'] for r in matrix));assert counts=={'PASS':26,'UNTESTED':2,'FAIL':1}
(ROOT/'jv-matrix.md').write_text('# JV01—JV29 本机验收矩阵\n\n26 PASS / 1 FAIL / 2 UNTESTED。PASS 仅指表中已执行边界；JV14 纳入扩展的撤权检查。\n\n| 编号 | 结果 | 已执行证据与边界 |\n|---|---|---|\n'+''.join('| '+r['id']+' | '+r['realScenarioStatus']+' | '+r['evidenceAndGap']+' |\n' for r in matrix))
permission=read('permission-results.json');browser=read('native/browser.log')
summary={'commit':base['commit'],'result':'FAIL','reason':'F03 P1 revoked administrator session remains authorized on legacy question APIs','backend':totals,'frontendBuild':'PASS (21 lint warnings and bundle-size warnings)','browser':{'passedChecks':len(browser['passed']),'pageErrors':browser['pageErrors']},'jvMatrix':counts,'openFindings':1,'failedPermissionChecks':sum(r['status']=='FAIL' for r in permission),'sourceModified':False,'committedOrPushed':False,'cleanup':'PASS','scope':'本机真实依赖功能/故障/权限验收；不涉及部署、压测；不等于完整生产验收','timestampUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
write('summary.json',summary)
tracked=subprocess.check_output(['git','ls-files','-z'],cwd=REPO).decode().split('\0');source_hashes=[]
for rel in tracked:
 if not rel or rel=='output' or rel.startswith('output/'):continue
 path=REPO/rel
 if path.is_file():source_hashes.append(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+rel)
(ROOT/'tracked-source-sha256.txt').write_text('\n'.join(source_hashes)+'\n')
write('workspace-state.json',{'head':base['commit'],'trackedDiffEmpty':True,'status':subprocess.check_output(['git','status','--short','--untracked-files=normal'],cwd=REPO,text=True).splitlines(),'originalOutputDirectory':'preexisting untracked; never read or traversed by acceptance','acceptanceEvidenceCommitted':False})
report='''# CodeOJ 完成验收报告（本机范围）

日期：2026-10-18。基线：`0b8115cb3bd7ab334c8fa8cba5f485b4659edc57`。

**结论：未全部通过。** 本轮补齐了主要真实故障、并发和多用户榜单缺口；发现 1 个 P1 权限缺陷（两个接口表现），业务源码保持原样。JV 矩阵按本机范围为 **26 PASS / 1 FAIL / 2 UNTESTED**，详见 [完整矩阵](jv-matrix.md) 和 [机器可读结论](summary.json)。这不代表原文 J0—J6 全部生产完成条件已达成。

## 必须修复：F03（P1）旧会话保留已撤销管理员权限

复现用真实账号注册/登录、Spring Session、Gateway 和私有 MySQL。管理员不是目标题目的所有者；登录后仅在验收数据库把角色从 admin 降为 user，保留旧 Session。

| 请求 | 期望 | 实际 |
|---|---|---|
| `GET /api/question/get?id=<fixture-question>` | 拒绝已撤权管理权限 | HTTP 200 / code 0，返回 `judgeCase` |
| `POST /api/question/update` | 拒绝已撤权管理权限 | HTTP 200 / code 0 / true；探针只将标题写回原值 |
| `GET /api/contests/<id>?admin=true` | 拒绝已撤权管理权限 | HTTP 403（对照通过） |

根因：`UserFeignClient.getLoginUser` 默认方法只返回 Session 内的旧 User，`isAdmin` 读取该旧角色；`AuthInterceptor` 也只检查 Session。`QuestionController` 旧接口没有再次确认当前数据库角色。用户服务中另一个会刷新数据库的 `getLoginUser` 实现，并未被这里的 Feign 默认方法调用。比赛新链路中的 `ExecutionRepository.requireAccount` 会读当前业务库，所以对照拒绝正常。

源码定位：`UserFeignClient.java:48`、`AuthInterceptor.java:38`、`QuestionController.java:170` / `:125`。可复验脚本 [permission_probe.py](permission_probe.py)，结果 [permission-results.json](permission-results.json)，结构化缺陷 [findings.json](findings.json)。没有记录隐藏用例内容、密码、会话 cookie 或执行 token。其它使用同一旧角色校验的入口需要在修复时审计；本报告只对上述两个接口作实际复现结论。

## 已验证结果

- 后端：24 个本轮实际运行的测试类，208 tests、0 failures、0 errors、0 skipped；命令启用真实 Redis 和原生 JVM/RSS 测试。按日志中的 Running 类匹配 XML，未计入旧 surefire 文件。详见 [测试明细](backend-test-summary.json)。
- 前端：构建通过；21 条既有 ESLint warning 与包体大小 warning 保留，不作无警告结论。真实浏览器 8 项业务检查通过，pageErrors 为空；见 [浏览器结果](native/browser.log)。
- F01/F02：本轮真实提交覆盖注释包含类声明但正常 AC、真实 CE、可归因异常 RE；JDK/启动器回归覆盖初始化异常及无法归因退出保留平台故障。原缺陷修复未回退。
- 可靠判题：真实 API 提交前/后 SIGKILL、MQ Return/断连/丢 Confirm、两个真实消费者的 InnoDB 竞争、RUNNING worker SIGKILL 与租约恢复、旧 token/代次拒绝、结果提交后 Broker ACK 丢失重投、预算耗尽及人工有限重试均通过。
- 限量报名：8 人争 2 名额、预留后 DB 前 API 崩溃、Redis 停机时 DB 确认/释放待办、关闭后消费、取消竞争、取消已提交却 Redis 不可用、部分/全部账本丢失、重复初始化、双向时钟偏差停闸均有真实依赖记录。
- 比赛规格：赛中已受理提交排队后再编辑公开源题，原比赛快照仍 AC、新普通练习 WA；比赛不增加普通练习 acceptedNum。另普通练习排队编辑规格独立通过。
- 成绩：真实同用户两题同时运行；4 用户多题、重复 AC、较早 AC 迟到、跨页并列/零分、真实跨截止执行、pending 拒绝结算、缓存清理、重建失败回滚、迟到消息与重建竞争、最终版本 2 均通过。

## 证据边界与未完成项

1. 本机使用原生 MySQL 8.0.46、Redis 6.2.24、RabbitMQ 4.3.6、Java 8，5 个实际 Spring 服务、静态发现和原生远程 Java 沙箱。另启动第二 judge 进程验证同消息竞争。没有 Docker/Nacos，不包含全套服务多节点、部署、压测、生产备份恢复。
2. JV08 与 JV25 完整隔离/物理回收仍 UNTESTED：直接 JVM 的时限、流预算、RSS 与临时目录有证据；网络/文件/PID 硬隔离、跨沙箱管理器/重启持久运行登记、任意后代进程树回收未完成。不能运行这些边界之外的恶意代码并声称安全。
3. J2 的旧 `code_queue` 在途消息迁移切换、历史真实数据迁移和回滚未演练；本轮只验证空白临时库 V001—V004。J6 原文包含的部署与负载验收按用户边界后置。
4. 精确并列榜单中五条 HTTP 受理事实的 acceptedAt 只在隔离 DB 中显式塑形，用于可手算的乱序/并列样本。真实 MQ、执行、投影与锁仍实际运行；这些精确时刻不算自然 HTTP 服务端时间证明。跨截止样本保留自然 acceptedAt，未修改其时间。
5. JV06 的 ACK 丢失在真实 AMQP 代理处发生，已观察 Broker unacked 后杀 worker；这验证“结果落库但 Broker 未收到 ACK”，没有声称在 worker 的 basicAck 调用前精确断点。
6. 取消竞争本次由取消获胜，验证无提交/资格越过已提交取消；没有声称穷举并发调度。JV28 通过持久键 TTL=-1、AOF 恢复和代码检查验证保留，不是等待普通缓存 TTL 到期的长时间演示。

## 保留的失败记录及归因

- 首轮 `two-real-consumers-mysql-claim` 失败：夹具使用 SLEEP(5)，达到实际 Feign readTimeout=5000ms，造成响应丢失与租约耗尽；此过程未证明重复有效执行。独立补测预启动两进程、用 SLEEP(2)，观察 InnoDB 锁等待，最终仅 1 个有效 attempt，记 JV04 PASS。首轮原始记录不删除。
- 补测 `published-public-snapshot-edit` 的零 acceptedNum 断言错误：同一普通题先前已被 JV04 AC，不能假定统计为 0。独立新题改为比较前后值，发布快照复验通过；再执行精确 JV15 赛中受理后编辑的独立样本通过。[精确结果](snapshot-results.json)。
- `Checks.run_case` 的 finally 内循环覆盖局部 name，导致原始 END 事件 case 标签为 judge；START 事件和结果 JSON 中的正式 case 名正确。本报告按结果 JSON 对照，未改写原始日志。
- 原始 `native/run.json` / `native-followup/run.json` 的 passed=false 因上述夹具失败被保留；诊断实例 passed=false 包含真实 F03，不能将全部原始运行宣称全绿。

## 复验与清理

命令与版本见 [build-commands.json](build-commands.json)、[运行归档校验](runtime-archives.json)；故障场景见 [extended-results.json](extended-results.json)、[followup-results.json](followup-results.json)。AMQP 代理仅丢所需方法帧，不伪造业务成功，方法帧依据 RabbitMQ 官方 [Confirm 说明](https://www.rabbitmq.com/docs/confirms) 和 [AMQP 规范](https://github.com/rabbitmq/amqp-0.9.1-spec/blob/main/xml/amqp0-9-1.stripped.extended.xml)。

复验时重新准备临时 runtimes，设置 Java 8 与 Playwright 路径，然后依次执行 `run_acceptance.py <runtimes>`、`run_followup.py <runtimes>`、`run_diagnostics.py <runtimes>`、`run_snapshot.py <runtimes>`。权限探针中的 runtimes 路径需改为该次临时路径；每个实例 finally 清理私有服务和数据库。建议使用新的证据目录，避免覆盖本次已归档结果。所有脚本只放在本次验收目录，不修改业务源码。

四个私有服务实例均已退出，全部记录端口复查关闭，临时数据库/配置/凭据和下载运行时目录均删除。原有 `output/` 未查看、遍历或变更。`HEAD` 与跟踪文件保持原样；本轮仅新增本证据目录，没有提交或推送。见 [cleanup.json](cleanup.json)、[workspace-state.json](workspace-state.json) 与 SHA-256 清单。

验收工作已完成；F03 修复及复验后才能关闭本轮本机验收缺陷。后置隔离、迁移和生产验收继续独立保留。
'''
(ROOT/'report.md').write_text(report)
# Only scan this new evidence directory; never traverse preexisting output/.
patterns={'privateKey':r'-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----','githubToken':r'gh[pousr]_[A-Za-z0-9]{25,}','openaiToken':r'sk-(?:proj-)?[A-Za-z0-9_-]{35,}','awsKey':r'AKIA[0-9A-Z]{16}'}
matches=[]
for path in ROOT.rglob('*'):
 if not path.is_file() or path==Path(__file__):continue
 content=path.read_text(errors='replace')
 for name,pattern in patterns.items():
  if re.search(pattern,content):matches.append({'file':str(path.relative_to(ROOT)),'pattern':name})
write('credential-pattern-scan.json',{'scope':'Only current acceptance evidence; structural token patterns; not a universal secret scanner','matches':matches});assert not matches,matches
manifest=[]
for path in sorted(ROOT.rglob('*')):
 if path.is_file() and path.name!='sha256sums.txt':manifest.append(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+str(path.relative_to(ROOT)))
(ROOT/'sha256sums.txt').write_text('\n'.join(manifest)+'\n')
print(json.dumps({'result':summary['result'],'backend':totals,'matrix':counts,'portsChecked':len(ports),'report':str(ROOT/'report.md')},ensure_ascii=False))
