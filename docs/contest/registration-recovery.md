# 报名恢复：旧快照、Lua 异常与受理上限

2026-10-22。本次修复对应[细致审查中的 R1/R2](../../outputs/validation/registration-review-20261022/report.md)，并补齐用户提出的“请求入口 Lua 执行失败”窗口。当前代码、[新增故障测试](../../codeoj-backend/codeoj-backend-question-service/src/test/java/com/codeoj/codeojbackendquestionservice/contest/RegistrationRedisIntegrationTest.java)及[本轮验证](../../outputs/validation/registration-fix-20261022/report.md)是判断依据；历史报告保留当时结果。2026-10-23 又在该修复基线上完成[整体本机复验](../../outputs/validation/overall-acceptance-20261023/report.md)，其中[报名专项](../../outputs/validation/overall-acceptance-20261023/registration-recovery/registration-facts.json)也重新执行；当前汇总见[状态页](../status.md)。

## 1. 旧快照不能仅靠条数判断

原问题：Redis 中尚未落库的 A 可以抵消数据库中已确认但 Redis 已丢失的 B，两边各一条记录，数量检查仍通过。后来者 C 因此得到额外预留，先确认后使 A 转人工。

新预留的 `sequence` 随累计账本条数单调增加，持久到请求表的 `reservationSequence`。新增 V005 对 `(contestId,reservationEpoch,reservationSequence)` 建唯一约束。已释放、已确认请求仍保留序号，不在恢复时清零。

- 新准入及周期维护读取数据库已落盘的最大预留序号，Lua 对照自身序号位置；旧快照低于这个位置时立即关 Redis 闸门，并将比赛置为 `LEDGER_ROLLBACK_DETECTED` 暂停。
- 数据库时间样本或查询结果可能过旧，因此唯一约束仍必要：即便调用者以旧的最大序号访问 Redis，重新分配的序号也不能绑定到另一持久请求。
- 原 request/event/key、时间和资格事实保留；不通过重建空库存掩盖已知身份丢失。数据不完整时保持暂停，待恢复完整证据后对账。
- 只回退 Redis 完成/释放标记时，待处理扫描也会依据数据库持久终态重放原标记；数据库已经 COMPLETE/RELEASED 不再被当作“Redis 一定仍是最新状态”。

序号是该比赛/epoch 内的持久一致性依据，不是跨系统时钟或绝对公平排序。这里验证的是完整旧快照回退和序号冲突；任意外部篡改、数据库与 Redis 同时丢失不在这些证明内。

## 2. Lua 抛错不等于此前写入已撤销

Lua 执行过程中不会被其他请求插入，但脚本运行时报错不自动回滚此前写入。特别是“已扣库存、尚未写用户映射”时，只查映射会遗漏部分执行。[Redis 官方说明](https://github.com/redis/redis/issues/10576)。

初始化、预留、完成/释放现在按下列顺序执行：

1. 校验键类型、原身份、时间、容量及上下文，准备好完整结果和目标值。
2. 在同场 meta 内写 `mutation` 操作意图，保存原 request/event/user/key/序号与修改前计数。预留意图先于扣库存。
3. 更新库存、用户映射、完整账本、pending 集合及计数。
4. 最后删除操作意图。任何中间步骤报错，正常读写均不能越过未完成意图。
5. 请求异常恢复、本人查询、维护任务及管理员对账可执行 `repair`：核验上下文、原身份及当前值是否仍处于该操作的修改前/目标值范围，再用确定的目标值补齐，最后清除意图。

恢复使用 `SET 目标库存`，重复修复不会叠加 DECR/INCR。RELEASED 只能源于已落盘 FAILED/CANCELLED；CONFIRMED 的完成标记不退还名额。修复不是根据一次异常猜测失败后回滚。

| 请求情形 | 当前行为 |
| --- | --- |
| 执行前连接失败，未发现原预留 | 503，不伪造已受理请求；原 key 可稍后重试 |
| 预留成功但响应丢失 | 查询原用户映射及账本，落盘原 request/event，返回原请求 |
| 写入意图后、扣库存前或后报错 | 核验并完成原意图，继续原请求；不能另建身份或直接退库存 |
| API 在部分写入后退出 | 后台修复原意图，再补建原请求及 Outbox；真实 MQ 消费确认 |
| 释放写库存后报错 | 按目标值补齐终态/集合，重复执行最多归还一个名额 |
| Redis 仍不可达、证据冲突或键类型损坏 | 返回不可判断并暂停；保留意图与原始数据，不自动删除或重置 |
| SCRIPT FLUSH 后执行 | 由现有 Spring Redis 脚本执行器重新加载/执行，已用实际 Redis 验证 |

没有意图记录的历史损坏，不能靠新代码猜回修改前库存。Redis 整体丢失且某条预留从未进入数据库的边界仍存在。

## 3. 受理能力与闭环能力一致

新建比赛容量上限为 10000，管理页面同步限制。Lua 在创建新身份前原子限制该比赛累计账本条数为 10000；满额后返回 `REGISTRATION_LEDGER_LIMIT`，已有身份的查询与原键重试仍可完成。

累计账本包括 PROCESSING、CONFIRMED 和 RELEASED，不能只限制当前资格数。小容量比赛经过不同用户失败、释放、再次分配后也受累计上限约束。保留终态身份用于幂等，不通过 TTL 或删历史记录绕过限制。该上限用于保证当前有界对账/结算可以闭合，不代表已测得 10000 用户吞吐能力。

迁移不会缩减已有比赛容量或删除历史数据。超过旧版受支持对账规模的既有数据保持暂停，需独立迁移方案；不要通过直接提高扫描上限、改库存或清空身份进行“修复”。

## 4. V005 与旧账本升级

1. 停止旧版报名写入者，先应用 V001—V005，再使用本版代码。不要让不识别操作意图和序号的旧写入者继续并行操作同一账本。
2. V005 增加可空 `reservationSequence` 和唯一索引；历史行不虚构序号，DB_SYNC 记录仍可为 NULL。
3. 缺少 `format=2` 的旧 Redis 账本不允许新准入。管理员走既有 reconcile：关闸、扫描完整原身份、补建已有预留、核对数据库资格和容量。
4. 核对通过后，在同一数据库事务中按保留的原时间/事件顺序分配稳定序号，再在关闭的旧 Redis 账本上填入序号；全部完成后才设置新格式并恢复入口。原 request/event/key 及资格不变。
5. 中途失败保持暂停。数据库已提交的序号不会重分配；Redis 部分升级可以沿用原赋值继续。缺行、身份或容量不一致则拒绝升级。

这是控制数据的兼容升级，不是生产部署自动化或热切换认证。

## 5. 如何验证与解释

后端入口：`REDIS_SERVER=/absolute/path/to/private/redis-server scripts/test-registration-local.sh`。测试仍自行启动独立随机端口 Redis/H2，不连接已有 6379。消息契约测试使用 mock；不要把它们写成真实 MQ 故障证据。

真实依赖入口：[check-registration-recovery.py](../../scripts/local/check-registration-recovery.py)，使用新建私有 MySQL、Redis、RabbitMQ、会话、网关和服务：

```bash
python3 -B scripts/local/check-registration-recovery.py --runtimes /private/path/from-prepare-runtimes --evidence /new/evidence/registration-recovery
```

该入口的旧快照/释放故障准备明确使用私有 SQL/Redis 夹具；正常报名、部分预留后服务重启、旧账本升级后的报名均经过实际 HTTP/Outbox/MQ 消费。沙箱服务采用显式可信 native 模式启动，本入口不执行参赛代码，不替代容器隔离验收。结束自动停止所属进程、删除私有数据库/配置，并检查端口关闭。

学习时应能解释：为什么查不到映射不能认定没扣库存；为什么条数相等仍可能丢身份；为什么序号检查还要有数据库唯一约束；为什么已标记 RELEASED 还可能需要重放 Redis 标记。

## 6. 与点评仓库的区别

本轮只读取同级 DianPing 当前版本 `8bec9928f853ffb0999787b62e4890fc84864fb4`。其 VoucherOrderServiceImpl 的 handleUnknownReserve 已有异常后查原映射的处理，但 seckill.lua 仍先 DECR，再 SADD/HSET 用户映射；映射不存在时将执行认定为未生效，对脚本内部部分写入不充分。本轮没有修改或运行验收 DianPing，不能把 CodeOJ 的新恢复协议描述成点评也已修复。

## 7. 2026-10-25：并发对账与名额展示修复

本轮修复[全链路审查](../../outputs/validation/registration-chain-review-20261025/report.md)发现的两项问题，保留原审查及其探针作为修复前证据。修复与复查收据见[本轮报告](../../outputs/validation/registration-chain-fix-20261025/report.md)。

对账从 `beginReconcile` 到 Redis 关闸、快照校验及数据库完成，始终使用本次操作取得的 revision；数据库完成后只使用该事务返回的新 revision，不通过另一次查询接管其他操作的版本。失败处理在比赛锁内同时校验 epoch、revision 及可处理状态：旧请求不能暂停更新的成功恢复、覆盖更新的安全暂停或改变取消状态。维护、报名、本人查询及未知结果恢复也携带原观察版本，通过 `issueIfCurrent` 防止迟到错误覆盖新恢复。当前操作遇到真实故障时仍转为 `RECONCILIATION_INCOMPLETE` 暂停。

用户比赛列表及详情改为“已确认人数 / 总名额”。MySQL `remainingCapacity` 保持最终确认事务中的原含义；页面用 `capacity - remainingCapacity` 显示已确认人数，不把在途预留误称为可报名余量，也没有新增 Redis 查询或改变配额扣减。

新增七项独立 Redis/H2 回归覆盖旧请求在初始化后/最后开闸前迟到、新安全暂停、取消、当前操作在数据库完成前后的真实失败，以及部分预留后取消并恢复，以及维护/报名/查询的旧账本错误迟到。它们与既有测试一起验证修复，但不替代真实 MySQL/MQ/浏览器全栈验收。
