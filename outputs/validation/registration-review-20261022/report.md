# CodeOJ 限量赛事报名审查

日期：2026-10-22。基线：`f10341721a1d8c5c7342bab671b607063338222e`。审查目标为当前 CodeOJ 的限量赛事报名，不涉及 DianPing 优惠券实体。结论：**复现 2 项尚未修复的问题；正常路径的重要保护仍然有效，不能据此认定所有故障窗口已闭合。**

本轮未修改业务代码，未提交或推送。证据：[机器摘要](summary.json)、[第一次验证日志](test-run.log)、[复现入口验证日志](reproduce-run.log)、[源码指纹](source-sha256.json)、[探针源码](probes/RegistrationAuditProbeTest.java)。

## R1 · P1：Redis 旧快照中的未落库预留，能掩盖已确认身份的丢失

位置：[RegistrationMaintenance.java](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/contest/RegistrationMaintenance.java) 第 25—31 行；新请求入口见 [RegistrationService.java](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/contest/RegistrationService.java) 第 47—57 行。

恢复任务仅比较 `数据库请求数 > Redis 账本数`，未检查数据库已知身份是否都在 Redis 中，然后允许打开入口、补建请求。未落库的 Redis 预留会占据账本条数，因此条数相等也可能是两组完全不同的身份。已标记 `COMPLETE` 的确认请求不再进入该任务的逐条同步范围。

### 已运行的故障序列

1. 比赛只有 2 个名额，用户 A 先完成有效 Redis 预留，尚未写数据库；保存此时五个 Redis 键的原始 DUMP 数据。
2. 用户 B 正常完成预留、数据库确认和 Redis 完成标记。此时 Redis 库存为 0，数据库已有 B 的有效资格。
3. 使用原始 DUMP/RESTORE 将测试 Redis 恢复到步骤 1 的快照，数据库保持最新状态。Redis 中只有 A，数据库中只有 B；两侧条数都是 1，B 的 Redis 身份实际已不存在。
4. 调用实际 `maintain()`：条数检查通过，比赛仍 `ready=true` 且无 issue；A 被补建到数据库，但本轮没有再次核验全部已知身份。
5. 在下一次维护扫描之前，用户 C 通过实际报名服务拿到新的 PROCESSING 请求，并优先消费确认，获得最后一个数据库名额。
6. 再消费 A 的原事件：A 转为 `MANUAL_REVIEW`，错误为容量/上下文冲突，并触发比赛级暂停。

实际结果：A 更早的有效预留仍在账本中，却被更晚到达的 C 挤占；B 的 Redis 身份缺失未阻止准入。最终资格数仍为 2，**本例没有最终超额确认**。问题发生在预留准入、恢复完整性和先有效预留的资格保障，随后还影响整场的资格可用性。

本例保留了 A 的原预留，也保留了数据库对 B 的持久事实；不属于“某条从未落库预留随 Redis 整体丢失，无法承诺无损恢复”的已声明边界。手工完整 `reconcile()` 可以检查具体身份，但周期维护及新准入并未要求先完成它。

### 建议修复及验收

- 不能把条数关系作为完整性证明。出现 Redis 重启、切换或恢复后，应先关闸，并完成当前数据库身份与 Redis 身份、终态及容量的对账后再允许新预留；恢复状态需要可持久化的版本或实例证据。
- 在恢复扫描补建请求期间保持准入受控，防止新请求抢占尚未核清的容量；不能只在下一秒补查条数。
- 修复测试应把本探针改成验证：B 缺失被发现，C 在核清前无法获得新预留，A 的原 request/event 身份保留；加入多实例和真实 MySQL 的对应故障验证。
- 单独给每次维护增加有限身份抽查仍存在准入窗口，不足以独立证明修复。

## R2 · P2：准入允许的账本规模超过对账和结算能力

位置：[ContestTransactions.java](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/contest/ContestTransactions.java) 第 58—62 行允许容量至 1,000,000；[contest-registration.lua](../../../codeoj-backend/codeoj-backend-question-service/src/main/resources/lua/contest-registration.lua) 第 53—64 行仅按剩余名额限制新预留；[RedisRegistrationLedger.java](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/contest/RedisRegistrationLedger.java) 第 56—61 行拒绝超过 10,000 条账本的对账。管理页面也允许配置至 1,000,000。

### 已运行的规模边界样例

通过实际建赛、发布、切换与初始化流程接受容量 10,001；使用真实 Lua 创建 10,001 个不同用户标识的预留后，实际调用 `service.reconcile()`。结果为：`REDIS_PAUSED`、`RECONCILIATION_INCOMPLETE`。相同账本不变时重复执行同一入口也无法越过硬上限。J4 开发记录已说明这一安全上限，本项指出准入配置及新预留路径没有落实相同的闭环限制。

这里的 10,001 次是实际 Lua 预留；未通过完整 HTTP/MQ 为 10,001 个真实账号确认资格，不能用作吞吐、用户规模或负载验收。边界结果由实际 Lua 及对账入口共同验证。

影响不限于主动恢复：[ScoreboardTransactions.java](../../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/scoreboard/ScoreboardTransactions.java) 第 172 行要求 Redis 模式在比赛结束后完成关闸对账，账本超限会使正常结算条件无法满足；第 173 行还独立限制确认人数至 10,000。现有恢复路径未提供超过这些限制的分页完成方案。

由于 RELEASED/CONFIRMED 身份长期保留，**总账本条数不等于当前参赛人数或配置容量**。小容量比赛在多轮失败、释放、其他用户新报名后，也可能累积到这个边界。因此仅把 capacity 上限改为 10,000 并不足够。

### 建议修复及验收

- 短期统一配置、前端提示和实际可闭合范围；在 Lua 新建预留前原子限制总账本条数，同时保留已有身份的重试查询能力。超限应明确拒绝新准入，而不是在结束对账时才发现。
- 长期将关闸对账改为可续跑、有版本校验的分页工作流，并同步解决结算人数上限；不能直接删终态映射或放宽一次扫描上限，破坏幂等和资源边界。
- 覆盖 9,999/10,000/10,001 条，以及低容量、多个不同用户失败释放后累计超限的样例；验证结算仍可完成或在准入阶段明确拒绝。

## 其余审查结果与证据层

本轮重新运行了 30 项真实独立 Redis + Spring/H2 事务测试，以及 7 项消息/传输契约测试，全部通过。主要覆盖：

| 检查面 | 本轮结论 | 证据边界 |
| --- | --- | --- |
| 并发争抢、同用户唯一资格 | 现有样例通过；MySQL 条件扣减和唯一约束仍是最终防线 | 8 用户争 2 名额；数据库为 H2 |
| 同键重试、换键冲突、跨比赛键作用域 | 现有样例通过 | 真实 Redis/Lua + H2 |
| 预留成功后数据库写入失败、原身份补建 | 现有样例通过 | AOF 重启不代表任意旧快照恢复正确；R1 即反例 |
| 早期 UNKNOWN、后续 NACK | 未据此释放名额，样例通过 | Broker 传输为 mock |
| 终态先落库、重复释放和完成标记 | 现有故障样例通过 | 真实 Redis + H2 约束注入 |
| 报名关闭前预留、关闭后确认 | 现有样例通过 | 使用原 reservedAt，不以消费时间否决 |
| 比赛取消与确认竞争、迟到消费 | 现有样例通过 | 受控线程竞争，不等于生产故障覆盖 |
| 管理员当前角色、停用账户、事件身份 | 现有样例通过 | H2 角色更新及 MockMvc/伪造消息验证 |
| 旧普通入口与 Redis 通道切换 | 现有样例通过 | 同一比赛锁、固定 epoch；不代表线上迁移验收 |
| 最终结算前发现未落库报名 | 现有小规模样例通过 | R2 超限路径未被原测试覆盖 |

另有 3 个审查探针全部完成：前两个断言“当前缺陷能够被复现”，第三个观察时间样本过旧时的保守关闸行为。**探针通过不意味着业务缺陷已修复，也不能把总计 40 次通过写成无问题验收。**

时间样本观察：保留旧 settings 时间 2.3 秒后执行 reserve，会得到 CLOCK_SKEW 并关闸。当前比较包含采样后的等待时间，无法独立区分排队/网络延迟和真实时钟偏差。H2 时钟使用测试夹具；本项只记录为保守策略及后续运维验证点，没有列为第三项已确认缺陷。

本轮未重新启动完整业务栈，也没有运行真实 MySQL 故障、真实 Redis 主从切换/Cluster、真实 MQ 故障重放、浏览器或压测。历史完整本机验收记录仍保留，但不覆盖这两个新反例。Redis Lua、MySQL 锁语义核对参考：[Redis Lua 文档](https://redis.io/docs/latest/develop/programmability/eval-intro/)、[MySQL 8.0 一致性读说明](https://dev.mysql.com/doc/refman/8.0/en/innodb-consistent-read.html)；本报告的缺陷结论来自当前源码和实际探针。

## 复现及交付

使用 Java 8、现有 Maven 依赖缓存，以及 Redis 6.2.24 可执行文件，在仓库根目录运行：

```bash
REDIS_SERVER=/absolute/path/to/redis-server \
  outputs/validation/registration-review-20261022/probes/reproduce.sh
```

该脚本临时放入探针测试源文件，拒绝覆盖已有同名源文件/类，启动独立随机端口 Redis；退出时移除临时测试源文件和类。Redis 测试夹具关闭自己的实例及 AOF 目录，不连接用户已有 6379。探针源文件保留在证据目录，不混入默认业务回归。

本轮用于编译 Redis 的专用临时目录已清理，清理及进程核查见 [cleanup.json](cleanup.json)。不修改全局服务和业务配置。源码指纹用于对照基线；新增文件仅为本轮报告、原始日志、结果摘要和复现材料。
