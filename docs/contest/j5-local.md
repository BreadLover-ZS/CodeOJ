# J5 成绩、榜单与结算本机开发记录

日期：2026-10-18。基线提交 `71d6898519447ab89bbf48428f37bd55e4f486dc`。本轮用户确认：登录用户可查看已发布比赛的榜单，只展示用户 ID、成绩和名次。继续采用本机功能闭环范围，不部署、不压测、不安装 Docker；原有 `output/` 不读写。

## 权威事实与计分

J2 的 `ExecutionCommands.complete` 已在同一事务保存最终判定及 `JUDGE_RESULT` Outbox，J5 接入既有事件，不再生成第二套判题结果。事件载荷继续使用 submissionId/resultVersion，AMQP messageId 使用原 Outbox eventId；消费者还会核验数据库原事件、当前结果版本、最终状态及结果摘要。消息不携带代码、用例和答案。

新增 `scoreboard` 包、V004 增量迁移和可选 `SCOREBOARD_ENABLED` 开关。启用时必须同时启用可靠判题，并先应用 V001→V002→V003→V004；三个业务开关均默认关闭。V004 不改写旧判定，既有 JUDGE_RESULT 可以按原身份投递。旧库未迁移时禁止先打开开关。

计分规则 `FIRST_AC_V1`：每题所有有效 AC 的最早 acceptedAt；solvedCount 为有 AC 的题数，achievedAt 为这些时间的最大值。解题数降序、achievedAt 升序，竞赛名次 1、1、3；零分全部并列。数据库保留微秒时间，userId 只稳定展示顺序。SQL 从整个投影计数严格领先者，跨页不重置名次。普通练习 AC 只保存忽略回执，不进入任何比赛成绩。

报名确认时，在同一数据库事务初始化成绩版本及零分行；查询也补齐遗漏零分行。消费者先锁比赛，再锁当前投影下的用户成绩行，再更新题目最早 AC 并重算总分。更早的 AC 晚到会更新 firstAcSubmittedAt；重复事件不重复加分。当前实现额外以比赛行锁串行化同场计分、资格和版本切换，优先保证本机一致性，未宣称高吞吐。

投影事务采用 READ_COMMITTED，防止在等待版本切换锁之前建立的旧一致性快照影响切换后的读取。所有 Redis、Feign 和 MQ 调用均在数据库事务外。

## 事件投递与恢复

独立拓扑 `codeoj.scoreboard.v1.exchange/queue/dlx/quarantine`；持久消息、mandatory、Correlated Confirm/Return、手动 ACK、prefetch=10、单消费者。数据库投影及回执提交后才 ACK，格式/身份不匹配或权威事实损坏进入隔离路径，暂时数据库故障有界退避后 requeue，中断不 ACK。

复用 OutboxStore/OutboxDelivery；发布确认不代表计分完成。缺少业务回执的已确认消息在等待窗口后按原 eventId 再发，自动发布预算20次。耗尽后保持 ATTENTION，管理界面暴露未更新事件。受控 replay 每批最多20事件、单次追加每事件最多20次、累计上限100；要求原因及比赛版本、投影版本、revision，不删除历史发送 attempt 或 UNKNOWN 证据。

## 查询与缓存

`GET /api/contests/{id}/scoreboard?page=0&size=20&version=...`：登录且数据库账户有效的用户均可查看已发布比赛；草稿不可见。返回 ruleVersion、projectionVersion、activeProjectionVersion、revision、asOf、pendingCount、projectionPendingCount、finalVersion、版本状态和榜单条目。用户 ID 为字符串，避免浏览器大整数精度丢失。

- pendingCount：尚未 COMPLETED 的比赛提交，包含 NEEDS_ATTENTION。
- projectionPendingCount：尚无投影业务回执的结果事件；重建可以直接从权威结果恢复，不能把 MQ ACK 当成此数为0。
- asOf：所选版本的最后投影时间。旧版本保持自己的时间，页面明确临时/最终和版本。

Redis 仅缓存页条目15秒，键包含比赛 hash tag、projectionVersion、revision、cacheEpoch、页和页大小；每次查询仍从数据库选择版本并获取新鲜状态及待定数量。缓存不可用或被清空时按当前数据库投影恢复。管理员清缓存递增 cacheEpoch，无需 KEYS/全量删除。旧命名空间自然过期。缓存内不存代码、判题配置或隐藏用例。

## 重建、结算和修订

生命周期到 endAt 后进入 FINALIZING，拒绝新受理，截止前已受理的提交继续判题。管理员先解决 NEEDS_ATTENTION 或报名异常，不将平台错误转换成 WA。

完整重建只允许 FINALIZING 且所有提交已有最终判定、报名无未决、有效资格不重复且容量守恒。Redis 模式还要求在 FINALIZING 完成一次关闭闸门的全账本对账，恢复 Redis→DB 窗口中的原预留，且所有请求确认/释放标记同步完成。成功对账记录 registrationReconciledAt；未决预留、旧对账或全局暂停都会阻止结算。

重建有本机边界：最多10000参赛者、50000提交，超限明确拒绝。锁住比赛，阻止计分与资格并发变动；由于到期后禁止新提交且 COMPLETED 结果不可变，确认无待定后可以从稳定权威事实构建新版本。先写新版本全部用户/题目成绩、校验，再原子切换读取指针。中途失败整笔回滚，用户继续读旧版本。赛中完整在线重建尚未实现。

重建返回 matchedPrevious，明确披露旧投影与权威事实是否一致；不一致不会自动完赛。finish 再独立核对权威题目最早 AC、所有用户总分、零分行、资格、容量和待定项，才固定 finalVersion 并置 FINISHED。最终版本不会被迟到重投修改。

FINISHED 后只能走明确的 revise 生成新最终版本，保留原 FINAL 版本和审计；历史版本可查询。首版修订是按当前权威事实重新生成投影，不提供直接覆写已完成判定、赛后自动重判或更改已冻结赛制的入口。

## 管理接口与页面

下列接口均位于 `/api/contests/{id}/scoreboard` 下，管理员身份来自会话且再次查询当前数据库角色。

| 方法/后缀 | 行为 |
| --- | --- |
| GET `/operations?page=0` | 分页查看待定提交与缺少回执的结果发布，不含私有代码 |
| POST `/rebuild` | FINALIZING 中从权威事实重建并切换新版本 |
| POST `/finish` | 重建校验后固定最终版本 |
| POST `/revise` | FINISHED 后修订为新最终版本，保留旧榜单 |
| POST `/cache-clear` | 递增版本化缓存命名空间 |
| POST `/replay` | 有限重放原结果事件，可显式追加有限次数 |

所有 POST 要求 expectedVersion（比赛）、expectedProjectionVersion、expectedRevision 和 reason；旧界面请求409并要求刷新。榜单面板随父页面比赛版本变化刷新，管理操作之后重读权威元数据。参赛详情加入榜单，管理页加入待定提交、事件、重建、结算、修订和历史版本入口；查看历史版本时禁用当前版本变更操作。

## 复现与验证边界

```bash
# 固定 Redis 版本/摘要，临时编译后自动清理；Java8完整本机功能回归
BUILD_TEST_REDIS=true scripts/test-registration-local.sh
# 已有 Redis 可执行文件时使用 REDIS_SERVER=/absolute/path/to/redis-server

# H2 + 受控原生 Java 样例：普通报名→真实 javac/java→AC→计分→重建→最终榜单
scripts/demo-local-contest.sh

npm --prefix codeoj-frontend run build
PLAYWRIGHT_MODULE=/absolute/path/to/playwright PLAYWRIGHT_BROWSER_CHANNEL=chrome \
  node scripts/validation/contest-browser-smoke.cjs
```

测试发现与修复：重建中的 nullable finalVersion 曾触发 Java 三元表达式自动拆箱，已改为显式包装类型；浏览器检查发现报名操作改变比赛版本后榜单持有旧元数据，已补版本监听刷新。受限执行环境禁止 `ps` 时原生样例正确返回 RESOURCE_METRICS_UNAVAILABLE；不把未知 RSS 伪装为0或 AC，在允许观察受控进程的环境复验通过。回环监听被禁止时浏览器启动记环境失败，在可绑定随机回环端口的环境复验。前端遵循现有 ESLint/Prettier 和 Vue 宏导入约定。

| 结果 | 本轮范围 |
| --- | --- |
| 后端179项，0失败/错误/跳过 | Java8完整reactor打包；21个实际执行测试类，四个基础设施空上下文仍按既有tag策略排除 |
| J5新增37项 | 25项成绩/HTTP/H2事务、8项mock消息、3项mock缓存、1项真实Redis→报名补建→结算串联 |
| 真实Redis共30项通过 | 独立随机端口、随机密码、私有AOF目录，含原29项报名回归；完成后清理 |
| 前端构建通过 | 既有依赖、lint警告和包体积警告保留 |
| 浏览器15项通过，0页面异常 | mock HTTP；含并列/跨页、大整数ID、版本刷新、重建不一致提示、最终结算、历史版本只读 |
| 原生演示通过 | H2、真实javac/java及RSS采样；最终1人1题、名次1，pendingCount=0，projectionPendingCount=0 |

最终结果见 [本轮证据目录](../../outputs/validation/j5-local-20261018/)。测试使用真实 Spring/JDBC/H2 事务、真实独立 Redis 和受控原生 Java；MQ/Channel、缓存故障及浏览器 HTTP 各自明确使用 mock，不能合并为真实基础设施联合验收。

UNTESTED：MySQL8 V004迁移、InnoDB锁/隔离/死锁、多实例实际消费者、真实MQ持久/Confirm/DLX、真实会话/网关/Feign/Redis/MySQL/MQ联合链；既有提交隔离、跨重启执行回收限制不变。未部署、未压测，未新增容量指标或生产可用性承诺。

实现核验参考：[Spring5.3事务代理与隔离](https://docs.spring.io/spring-framework/docs/5.3.x/reference/html/data-access.html)、[MySQL8锁定读取](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)、[RabbitMQ发布确认与消费确认](https://www.rabbitmq.com/docs/confirms)、[Spring AMQP官方示例](https://github.com/spring-projects/spring-amqp-samples)。这些文档用于核对语义，不替代本机或真实基础设施运行证据。
