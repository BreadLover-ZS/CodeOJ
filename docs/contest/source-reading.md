# 赛事模块源码阅读地图

从本轮已实现的业务链阅读，配合各阶段测试核对边界；原方案中的部署、压测和尚无运行证据的功能不可当成已验收事实。

| 业务链 | 按调用顺序阅读 | 对应验证 |
| --- | --- | --- |
| 受理与固定规格 | `ContestController.submit` → `ContestService` → `ContestTransactions.submit` → `SubmissionAcceptance.write` → `reliable_outbox` | ContestCoreTest、ReliableExecutionTest |
| 原生判题 | `ReliableJudgeConsumer` → `ReliableJudgeWorker` → claim/heartbeat → `JavaNativeCodeSandbox` → `BoundedProcessRunner` → `SandboxResultMapper`/严格比较 → complete/fail | JavaNativeCodeSandboxTest、ReliableJudgeWorkerTest；demo-local-contest.sh |
| 隔离与物理回收 | `ReliableJudgeWorker` → `JavaIsolatedCodeSandbox` → `DurableExecutionRegistry`（token/slot journal 与文件锁）→ `DockerIsolationEngine` → `isolation/supervisor.py` → 降权 Java/namespace 清理 → 私有收据 → 删除物理对象后释放 slot | DurableExecutionRegistryTest、check-isolation.py（实际双 JVM、SIGKILL、cgroup、越界样例） |
| 名额竞争 | `RegistrationService.register` → `RedisRegistrationLedger.reserve` → `lua/contest-registration.lua` → persist+Outbox → `RegistrationConsumer` → confirmRegistration → synchronize | RegistrationRedisIntegrationTest；独立Redis+H2，MQ为手动消费/替身 |
| 未知与恢复 | `OutboxStore.claim/record` → 每次 publish_attempt → `RegistrationMaintenance` → `recover`/`reconcile` → 人工 review | RegistrationMessagingTest、RegistrationRedisIntegrationTest |
| 乱序计分 | `ExecutionCommands.complete` → JUDGE_RESULT → `ScoreboardMessaging.Relay` → `ScoreboardConsumer` → `ScoreboardTransactions.project` → 用户锁+题目最早AC+重算 → 业务回执 | ScoreboardTest、ScoreboardMessagingTest |
| 并列/缓存 | `ScoreboardController.page` → `ScoreboardService.page` → snapshot元数据 → 版本化页缓存/SQL排序 → 严格领先人数+1 | ScoreboardCacheTest、ScoreboardTest；mock浏览器检查 |
| 最终结算 | `ContestLifecycle.tick` → FINALIZING → 报名关闭对账/待定解决 → scoreboard rebuild → 新版本校验/切换 → finish → finalVersion | ScoreboardTest、RegistrationRedisIntegrationTest；原生演示 |

后端包根目录：`codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/`。测试位于同模块 `src/test/java/`；判题服务、沙箱、模型协议分别在各自 Maven 模块。迁移按 `mysql-init/migrations/V001` 到 `V004` 阅读。

建议亲自解释并运行三个用例：

1. 8用户争2名额：为什么资格以数据库确认而非Redis返回成功为准，超时后为何不能直接回收。
2. 租约过期和旧token回写：为什么原事件可重复投递，但旧执行器不能覆盖新结果。
3. 四人两题手算基准：为什么 1、1、3、3 会因更早AC晚到改为 1、2、3、3，且只依据 acceptedAt。

J5最终版本与历史版本是投影；有效提交、结果摘要、发布快照和资格是重建依据。最终榜单修订新增版本，不静默覆盖旧版本。当前同场计分采用较粗的比赛锁，不能据此推断吞吐能力。

真实服务联调入口为 `scripts/test-native-stack.sh`，运行时与实例均临时隔离；[J6记录](j6-native-local.md)说明真实HTTP范围、代理故障、会话503与恢复边界。

本机入口与历史证据见 [J5](j5-local.md)、[J4](j4-local.md)。2026-10-22 新增[隔离执行器](isolated-sandbox.md)及[JV01—JV29 全套本机复验](../../outputs/validation/isolation-20261022/jv-matrix.md)；Nacos、部署、压测和本机证据之外的故障场景仍需独立验证。
