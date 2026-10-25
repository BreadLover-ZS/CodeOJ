# CodeOJ 秒杀报名全链路审查

日期：2026-10-25。审查基线：`b9af09bfaf339fa6e39d84eec97fd77dce3d08b4`。

结论：**复现 2 项问题：R1 为 P2 并发恢复问题，R2 为 P3 页面名额口径问题，均未修复。** 本轮没有复现最终超额确认、同一用户重复获得资格或重复退还名额；这不表示这些性质已在全部环境和故障组合下得到证明。既有 2026-10-23 本机验收不覆盖本轮新增的对账并发反例。

按用户选择，本轮只审查、复现和交付证据，不修改业务实现，不提交或推送。新增内容仅在本目录。入口：[机器摘要](summary.json)、[现有测试日志](baseline-tests.log)、[探针日志](probe-tests.log)、[源码指纹](source-sha256.json)、[复现探针](probes/RegistrationChainAuditProbeTest.java)。

## R1 · P2：旧对账请求的迟到失败会暂停更新的成功对账

关键位置：[RegistrationService.java 第 103—104 行](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/contest/RegistrationService.java)，以及 [RegistrationTransactions.java 第 72—76 行](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/contest/RegistrationTransactions.java)。

根因：Redis 闸门通过 revision 拒绝旧写入，但 `reconcile()` 的 catch 对全部 RuntimeException 无条件执行 `hold(id, "RECONCILIATION_INCOMPLETE")`。`issue()` 又只锁当前比赛，没有校验调用者所处的对账代次或预期版本。因此，已经失效的旧操作仍可通过数据库暂停动作覆盖更新的恢复结果。

### 已执行的时序

1. 创建容量为 2 的活动，完成普通通道切换。用户 A 已确认资格，Redis 完成标记也已落下。此时比赛 revision=4。
2. 管理员发起对账 X：begin 到 revision=5，数据库 finish 到 revision=6、ready=true。探针仅在 X 最后打开 Redis 闸门前阻塞其线程，保留 X 已采样的 revision=6；不修改业务源码、Lua 或数据库状态。
3. 另一个管理员请求读取当前 revision=6，发起对账 Y。Y 正常完成，revision=8、REDIS_READY、ready=true、Redis open=1；用户 A 的 qualificationValid=true。
4. 恢复 X。真实 Lua 因 X 的 revision=6 旧于 Redis revision=8，返回 `STALE_GATE_REVISION`。这个拒绝本身正确。
5. X 的 catch 随后对当前比赛执行 hold，写成 revision=9、REDIS_PAUSED、ready=false、issue=RECONCILIATION_INCOMPLETE。

事实收据：

```json
{"newerCompletedRevision":8,"newerCompletedReady":true,"afterOlderFailureRevision":9,"afterOlderFailureReady":false,"afterOlderFailureMode":"REDIS_PAUSED","issue":"RECONCILIATION_INCOMPLETE","lateGateError":"STALE_GATE_REVISION","redisGateBeforeNextTick":"1","newRegistrationRejected":true,"confirmedUserQualificationValid":false}
```

影响：更新的管理员恢复操作已经成功返回，但旧请求仍可把整场资格数据再次暂停；新的报名被拒绝，已有 CONFIRMED 用户的 qualificationValid=false，比赛提交也因 `registrationReady=false` 被拒绝，直到再次人工对账恢复。探针实际检查了报名拒绝和资格失效；提交拒绝由 [ContestTransactions.submit 第 214 行](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/contest/ContestTransactions.java) 的同一 ready 检查确定。本例没有删除资格或超卖。

触发条件是两个对账请求重叠，且较早请求的外部调用迟到；多管理员、跨窗口重试均可形成这种时序。虽然单个页面有 busy 状态，后端 API 仍允许新请求以当前版本开始下一轮对账。

建议：给对账副作用及失败处理使用同一操作代次/所有权约束。只有当前仍属于该操作的状态才允许因其错误转为暂停；被更新操作取代的失败应记录为 superseded，不能再写当前暂停状态。保留当前操作遇到真实账本/容量错误时的暂停能力，不能简单吞掉所有异常。对应回归应覆盖旧操作迟到失败、新操作成功，以及新操作遇到真实故障的不同结果。

证据边界：真实 Redis 6.2.24 / 生产 Lua、Spring 事务与 H2、两个受控并发线程。没有冒充跨服务 HTTP 或真实 MySQL 并发验收；本例暴露的是版本隔离之外的无条件状态写入。

## R2 · P3：页面“剩余名额”忽略已占用的在途预留

位置：[ContestDetailView.vue 第 18—20 行](../../../codeoj-frontend/src/views/contest/ContestDetailView.vue)、[ContestsView.vue 第 26—28 行](../../../codeoj-frontend/src/views/contest/ContestsView.vue)。服务端字段来源为 [ContestTransactions.view 第 227 行](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/contest/ContestTransactions.java)。

根因：页面直接把 MySQL 的 `remainingCapacity` 标为“剩余名额”。这个字段只在资格确认事务中扣减，含义是尚未确认的数据库容量；秒杀入口实际可分配量由 Redis 预留库存决定。在途请求尚未确认时，两者本来就不同。

已执行的复现：创建容量为 1 的活动，用户 A 经真实报名服务进入 PROCESSING，暂不消费确认。此时 Redis stock=0、数据库资格数=0，另一用户读取的比赛详情 remainingCapacity=1。另一用户经真实报名服务尝试报名，得到 CONTEST_FULL。列表和详情的模板均直接呈现该字段。

```json
{"displayedRemainingCapacity":1,"redisAvailable":0,"pendingStatus":"PROCESSING","confirmedQualifications":0,"nextUserResult":"CONTEST_FULL"}
```

影响：名额已全部被有效预留时，用户仍看到有剩余名额；消费延迟时该显示可持续存在，刷新也不能解决。它是页面口径错误，不是数据库超卖，也不应通过提前扣减 MySQL remainingCapacity 来修正。

建议：区分“尚未确认名额”和“当前可预留名额/确认中”；若提供实时可预留量，应有独立字段及不可判断状态，Redis 读失败时不能虚构可用量。也可以明确改用已确认人数和总容量展示，避免把数据库余量承诺成可报名库存。

证据边界：实际服务返回值、真实 Redis/Lua 拒绝结果和当前模板源码；本轮没有启动真实浏览器，因此不声称完成了页面渲染验收。

## 本轮覆盖及保护结果

现有 55 项测试全部通过：48 项独立 Redis/Lua + Spring/H2 测试、7 项消息/传输契约测试。新增 3 个探针全部执行完成；前两个断言缺陷能够复现，第三个验证新的故障组合。**不能把 58 项执行完成写成 58 项无缺陷业务验收。**

| 链路 | 检查结果 | 本轮证据范围 |
| --- | --- | --- |
| 入口身份、本人查询与管理员复核 | 当前账号检查、同键返回原身份、换键冲突保持有效 | 源码及现有 Redis/H2 测试；HTTP 为 MockMvc |
| Redis 预留 | 同槽键、原子容量/用户/时间校验、无未决 TTL、累计账本上限 | 真实 Redis/Lua；8 用户争 2 名额是受控并发样例 |
| Lua 部分写入与未知响应 | mutation/repair 维持原 request/event，确定目标值避免重复退还 | 真实 Lua 故障注入与独立 AOF 重启 |
| 请求与 Outbox | 同库原子落盘、未知结果不释放、幂等补建 | Spring/H2 真实事务；没有复跑 MySQL |
| MQ 发布与消费 | Confirm/Return/UNKNOWN、持久化与手动 ACK 契约；非法事件拒绝 | 消息运输使用 mock，没有复跑真实 RabbitMQ |
| 数据库最终确认 | 条件扣减、唯一资格、重复/迟到确认、原时窗与有限人工恢复 | Redis/H2 及消息契约；不把 H2 结果等同于 MySQL 隔离验证 |
| 回收与取消 | 终态先落盘、确认资格不退款、取消与消费竞争 | 现有样例通过；新增“扣库存后中断 → 取消 → 恢复 → 迟到确认”组合通过，原身份保留、只退一次、无资格 |
| 旧快照/旧账本 | V005 高水位/唯一序号、终态标记重放与中断升级保护仍通过 | 现有真实 Redis/H2 测试；未运行真实主从/Cluster 切换 |
| 对账恢复 | 正常及既有损坏场景通过；新增并发版本窗口发现 R1 | 真实 Redis/Lua + H2 并发探针 |
| 前端与最终结算衔接 | 阅读报名状态、重试标识、管理操作、资格检查及最终结算条件；名额口径发现 R2 | 源码和服务返回探针；本轮未跑浏览器或完整结算链 |

原 R1/R2（2026-10-22 旧快照漏身份、准入超过闭环上限）已进入现有修复测试，本轮这些测试通过；这里的 R1/R2 为本轮独立编号，不代表旧缺陷重新出现。

## 复现与交接

在仓库根目录，准备 Java 8、已有 Maven 依赖缓存和 Redis 6.2.24 可执行文件，然后运行：

```bash
REDIS_SERVER=/absolute/path/to/redis-server \
  bash outputs/validation/registration-chain-review-20261025/probes/reproduce.sh
```

探针沿用现有测试的独立随机回环端口 Redis/H2 夹具，不连接已有业务实例。脚本拒绝覆盖已有同名探针源文件/类，退出时删除临时测试源文件及类；本目录保留所需复现源码。完整现有测试命令见 [summary.json](summary.json)。每次复现探针的版本数字来自新建测试比赛，不能对真实业务数据库直接执行这些步骤。

没有复跑真实 MySQL/RabbitMQ/网关、真实浏览器、Redis 主从切换/Cluster、部署、压测或长期稳定性；这些均未作为本轮 PASS。现有完整本机验收的历史结论仍按原版本和场景读取。

本轮用于下载编译 Redis 的专用临时目录与运行时将在交付前移除；最终清理结果见 [cleanup.json](cleanup.json)。业务源码、配置和原验收材料保持不变。
