# CodeOJ 秒杀报名修复与再次审查

2026-10-25。基于 `b9af09bfaf339fa6e39d84eec97fd77dce3d08b4` 的工作区修改，未提交或推送；具体源码以 [source-sha256.json](source-sha256.json) 为准。

结论：**原审查 R1/R2 已修复；再次检查发现的迟到维护错误覆盖新恢复问题也已修复。最终回归通过，本轮已执行场景没有未关闭失败。** 这不等于证明全部环境和故障组合没有 bug。

## 修复内容

1. **对账全过程固定本次版本。** `RegistrationService.reconcile` 沿用 begin 的 revision；完成事务后，使用该事务返回的新 revision，取消两处可能读取并接管其他操作版本的查询。旧请求不能关闭更新操作已恢复的 Redis 闸门。
2. **失败处理校验原观察版本。** `RegistrationTransactions.issueIfCurrent` 在同一数据库事务与比赛行锁内核对 epoch、revision 和 Redis 模式。旧操作的错误不能暂停更新恢复、覆盖新安全暂停或改变取消状态；当前操作真实失败仍暂停。
3. **补齐同类入口。** 维护、报名、本人查询、未知结果恢复的 Redis 观察错误也携带原 settings；数据库持久身份冲突等权威事实仍保留原保护。没有通过吞掉所有错误来维持 ready=true。
4. **修正页面语义。** 用户列表与详情显示“已确认人数 / 总名额”，取 `capacity - remainingCapacity`；数据库字段含义及容量扣减保持原样，没有新增 Redis 查询。

实现：[RegistrationService](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/contest/RegistrationService.java)、[RegistrationTransactions](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/contest/RegistrationTransactions.java)、[RegistrationMaintenance](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/contest/RegistrationMaintenance.java)。

## 再次检查发现的问题及修复闭环

首轮修复后，原对账反例的正确性断言通过。继续检查其他暂停入口时，发现维护任务、报名及本人查询也会把早前 Redis 错误无条件写到更新的数据库状态。

补充反例在独立 Redis 上制造 pending 键类型错误；旧维护线程收到真实 `LEDGER_INCOMPLETE` 后暂缓返回。测试恢复原空 pending 键并调用正常管理员对账，使比赛恢复 ready=true，再放行旧线程。首轮修复代码出现 `maintenance ==> expected: <true> but was: <false>`：旧错误再次暂停已恢复比赛。[修复前失败日志](followup-before-fix.log)。

随后将所有上述外部观察错误改为按 epoch/revision 条件暂停，最终同一测试分别执行维护、报名、本人查询三条路径通过。已有错键类型、旧快照、身份冲突和当前对账故障测试同时通过，表明当前错误的暂停保护仍然生效。

新反例的第一次诊断运行没有到达预设 check 阻塞点：错误先在 repair 发生，等待超时。这是探针观察点错误，没有计为产品缺陷。将观察点移至真实 repair 错误返回处后，保留原业务断言，才得到上面的产品失败证据。[诊断记录](followup-fixture-diagnostic.log)。

## 最终验证

| 层次 | 结果 | 证据与范围 |
| --- | --- | --- |
| Java 8 全后端打包与回归 | **255 tests / 27 类，0 failures / errors / skipped** | [最终日志](backend-final-regression.log)、[逐类结果](backend-tests.json)；依项目默认配置排除 infrastructure 空 context 测试 |
| 其中报名 Redis 事务回归 | **55 项通过** | [报名类结果](RegistrationRedisIntegrationTest.txt)；独立 Redis 6.2.24、生产 Lua、Spring/H2 事务 |
| 其中消息契约 | **7 项通过** | [消息类结果](RegistrationMessagingTest.txt)；RabbitMQ 传输为 mock |
| 前端生产构建 | **通过** | [构建日志](frontend-build.log)；既有 lint、包体积及工具链警告保留，不是性能结论 |
| 实际 Vue 模板渲染 | **待确认/已确认两种夹具通过，0 渲染警告** | [渲染收据](frontend-render.json)；Vue SSR、UI 组件与 API 数据夹具，不是真实浏览器验收 |
| 最终源码/文档检查 | JSON、文档链接、源码摘要及 diff whitespace 检查通过 | [机器摘要](summary.json)、[源码指纹](source-sha256.json) |

首轮报名专项 61 项、首轮全后端 254 项均通过；补充新问题修复后重新执行完整后端，最终数字以上表为准。报名 62 项包含于 255 项，各轮/各子集不相加。

新增七项回归位于 [RegistrationRedisIntegrationTest](../../../codeoj-backend/codeoj-backend-question-service/src/test/java/com/codeoj/codeojbackendquestionservice/contest/RegistrationRedisIntegrationTest.java)：

- 旧请求最后开闸迟到，不得暂停更新的成功对账。
- 旧请求在初始化后迟到，不得接管新版本或关闭新闸门。
- 旧失败不得覆盖更新的账本安全暂停原因。
- 旧失败不得改变取消状态、版本或闸门。
- 当前操作在数据库完成前/后真实失败时，均仍暂停。
- 部分扣库存后取消，恢复原身份、只退一次且迟到消费不确认。
- 维护、报名及本人查询的旧账本错误不得覆盖更新的成功恢复。

再次阅读并核对入口身份与幂等、Lua 预留与 mutation 修复、Outbox 原子落盘、Confirm/Return/ACK 区分、数据库资格与容量、终态回收、取消竞争、V005 旧快照防护及最终结算的未决检查。原有相关测试均包含在最终回归；没有用新发现的可用性问题推断发生超卖，也没有削弱最终数据库容量约束。

## 复现最终回归

准备 Java 8 与 Redis 6.2.24，运行现有入口：

```bash
REDIS_SERVER=/absolute/path/to/redis-server scripts/test-registration-local.sh
```

本轮实际执行的离线等价命令见 [summary.json](summary.json)，已启用真实 Redis 测试及原生进程观察。测试仅连接自建随机回环端口 Redis/H2。

原 [2026-10-25 审查报告](../registration-chain-review-20261025/report.md)和原探针保持不变：它们断言“当时缺陷存在”，不是本轮正确性验收入口；修复后应运行当前源码中的回归测试。

## 边界与交接

本轮未重跑真实 MySQL/RabbitMQ/网关全栈、真实 Chrome、Linux 隔离全栈、JV01—JV29、Redis 主从/Cluster、部署或压测。2026-10-23 的全栈结果仍只属于当时版本；本轮新增七项测试使用 H2，不升级为真实 MySQL 并发证明。

源码学习说明及当前状态已同步。临时 Redis 进程、AOF 目录、下载/编译目录与临时前端 dist 均在交付前清理；最终清理收据见 [cleanup.json](cleanup.json)。保留报告、日志、逐类结果与当前源码指纹，不保留临时运行时。

设计参考：[Spring Batch 4.3.10 的版本并发控制说明](https://docs.spring.io/spring-batch/docs/4.3.10/reference/html/schema-appendix.html#metaDataVersion)。本次沿用本仓库已有 JdbcTemplate、Spring 事务和版本字段；没有新增 Spring Batch 依赖。
