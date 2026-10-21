# CodeOJ 完成验收报告（本机范围）

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
