# 赛事源码速查

需要按步骤学习和自测，读[源码阅读指南](../learning/README.md)；这里只用于按业务查方法。

后端下列类位于题目服务 [Java 包目录](../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice)，判题与沙箱类分别在各自模块。

| 业务 | 按调用顺序定位 | 重点测试 |
| --- | --- | --- |
| 练习受理 | `ReliableSubmissionService.accept` → `SubmissionAcceptance.accept/write/freezeSpec` | ReliableExecutionTest |
| 比赛发布与提交 | `ContestTransactions.publish/submit` → `SubmissionAcceptance.write` | ContestCoreTest |
| 发布与执行 | `OutboxStore.claim/record` → `ReliableJudgeConsumer.receive` → `ReliableJudgeWorker.process` → `ExecutionCommands.claim/complete/fail` | OutboxRelayTest、ReliableJudgeWorkerTest |
| 隔离与回收 | `JavaIsolatedCodeSandbox` → `DurableExecutionRegistry` → `DockerIsolationEngine` → `supervisor.py` | DurableExecutionRegistryTest；check-isolation.py |
| 秒杀报名 | `RegistrationService.register` → `RedisRegistrationLedger.reserve` → Lua → `RegistrationTransactions.persist/confirm` | RegistrationRedisIntegrationTest |
| Lua 中断恢复 | `RegistrationService.recoverUnknown` → Lua `mutation/apply/repair` | 同上；check-registration-recovery.py |
| 报名对账 | `RegistrationService.reconcile` → `finishReconcile`；迟到错误 → `issueIfCurrent` | 同上，包含旧对账/维护/报名/查询错误迟到 |
| 计分与榜单 | `ExecutionCommands.complete` → `ScoreboardConsumer` → `ScoreboardTransactions.project` → `ScoreboardService.page` | ScoreboardTest、ScoreboardMessagingTest、ScoreboardCacheTest |
| 最终结算 | `ContestLifecycle.tick` → `ScoreboardTransactions.rebuild/finish` | ScoreboardTest；真实验收矩阵 |
| 当前账号授权 | `SessionUserResolver` → `AuthInterceptor`；事务内 `ExecutionRepository.requireAccount` | CurrentAccountAuthorizationTest |

迁移从 [V001—V005](../../codeoj-backend/mysql-init/migrations) 读表、唯一约束和版本字段。Redis/H2、消息 mock、真实依赖与浏览器测试的结果分层查看[当前状态](../status.md)，不按测试名称推断证据范围。
