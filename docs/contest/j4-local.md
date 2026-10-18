# J4 秒杀报名本机开发记录

日期：2026-10-18。继续按 v1.1 方案开发，参考同级 DianPing 的真实秒杀源码，改写为赛事报名。未安装全局 Redis 服务、未使用 Docker、未部署或压测，未提交/推送代码。

## 实现范围

报名现有入口按比赛的 `registrationMode` 分派，身份来自现有会话，再检查数据库当前账户。J3 的 `DB_SYNC` 只在尚未切换的比赛中可用；切换与旧报名共用比赛行锁，因此没有同时分配名额的两个入口。

切换顺序：

1. 管理员提交原因、`expectedVersion`。数据库确认普通报名无 PROCESSING/MANUAL_REVIEW，且 `capacity = confirmed + remainingCapacity`。
2. 同事务置 `REDIS_PAUSED`、`registrationReady=false`，固定 `registrationEpoch` 和 `initialRedisCapacity`。旧普通报名路径随即拒绝新请求。
3. 管理员执行对账，先进入 `REDIS_RECONCILING`。Redis 首次初始化关闭入口；初始化成功后再写数据库初始化标记。任何后续初始化重试只核验原配置，不重置库存。
4. 在关闭且带版本的 Redis 闸门下扫描账本、补建请求，并核对数据库确认资格、未决预留及 Redis 可用量。只在一致后更新数据库为 `REDIS_READY`，再打开 Redis 入口。**没有用数据库余量覆盖 Redis 的操作。**

所有 Redis/MQ 调用均在数据库事务之外；数据库写入遵循比赛锁→账户/报名请求→资格的顺序。锁竞争/瞬时错误在事务代理返回并回滚后有限重试整笔事务。

### 预留与原请求身份

Lua 使用五个同槽键，统一 `{contestId}` hash tag：meta、stock、users、ledger、pending。预留前生成 requestId/eventId，但重复调用始终以 Redis 原映射为准。脚本原子检查发布版本、epoch、入口开关、报名时间、余量和本人映射；同时保存原 key/hash/身份及预留账本。

- 时间来自 Redis `TIME`，使用脚本效果复制，兼容本轮实际测试的 Redis 6.2.24。报名窗口比较和 reservedAt 保留微秒，账本用十进制字符串保存 epoch 微秒，避免 cjson 数字精度截断；落库后按相同精度校验身份。新预留为左闭右开；DB 时间与 Redis 时间允许偏差默认 2000ms，任一方向超过即关闸并暴露 CLOCK_SKEW，两处校验使用同一配置。
- 同用户/比赛原键重试返回原请求，即使当前已关闭或余量为零；换键409。不同比赛可独立使用同一键。
- 请求哈希绑定报名类型、比赛、固定发布版本；跟踪 ID 不进入哈希。
- 映射、账本、初始化标记和未决集合不设置普通缓存 TTL。完成/回收仍保留原身份和终态标记，失败后换键不能重新报名。
- 账本/映射/未决计数异常、用户映射指向别人事件、已知数据库请求在 Redis 快照中缺失时保持暂停。Redis 全部丢失且数据库已记初始化，不会自动新建空库存；从未落库且随 Redis 一起丢失的预留仍无法保证恢复。

### 数据库落盘与异步确认

Redis 成功后，题目服务用原身份同事务写 `contest_registration_request(PROCESSING)` 和 `REGISTRATION_CONFIRM` Outbox，再返回202。此时没有参赛资格。若 Redis 调用或落库结果未知，返回503并保留原映射；本人查询和原键重试可以找到预留并幂等补建，不创建新身份。

独立拓扑为 `codeoj.registration.v1.exchange/queue/dlx/quarantine`，与判题隔离；消息持久化、mandatory、Correlated Confirm、Return，独立消费者 prefetch=10、并发1、手动 ACK。消息只含请求/比赛/事件/发布版本/epoch/载荷版本，不带用例或代码。

确认事务锁同一比赛、原请求，校验完整事件身份，只有 PROCESSING 可确认。复用 J3 数据库条件扣容量及资格写入函数，重复确认不再扣减；关闭报名或已到 FINALIZING 后，仍按原有效 reservedAt 处理已受理预留，不以消费时间重新拒绝。

消费者只在数据库事务持久处理后 ACK。数据库不可判断时有界退避后 requeue；格式/身份不匹配进入隔离路径。中断不 ACK，并保留线程中断。真实 Broker 的持久化/Confirm/隔离队列行为本轮尚未联合验证。

### 发布未知、恢复与人工处理

J2/J4 共用 OutboxStore 和 OutboxDelivery，按事件类型分派。每次发送均保存独立 attempt；早期 UNKNOWN 不会被后一次 NACK 或 Return 抹去。Broker ACK 不等于已产生资格：长期未确认请求仍会按原 eventId 有限重新派发。

自动预算为20次发布、从**原 reservedAt**计算的5分钟确认/恢复期限；预算或期限耗尽只进入 MANUAL_REVIEW，名额继续保留。Redis 预留后很久才补建数据库记录，也不会得到一个从补建时刻开始的新期限。

管理员复核要求当前数据库管理员身份、原因、请求 `expectedVersion`：

- CONTINUE 只作用于 MANUAL_REVIEW，沿用原 eventId。若预算/期限已经耗尽，必须显式追加有限次数/期限；单次发布额度最多20、累计最多100，单次延长最多3600秒且期限不超过当前时间后一小时。保留已有 attempt 和审计，不清零。
- FAIL 只终止 PROCESSING/MANUAL_REVIEW，先事务写 FAILED+RELEASE_PENDING。已确认资格不能通过此动作释放；终态不能继续确认。
- 回收器在数据库终态之后，才按原 event/request/user/version/epoch 执行 Lua 释放。Lua 保留终态映射，重复调用最多恢复一次。Redis 释放成功、数据库回收标记失败可幂等重试。
- 确认后 Redis 完成标记失败也可重试；不会把已确认资格当成失败回收。

比赛取消先按既有事务提交 CANCELLED；此后同比赛锁阻止新确认/提交。后台关 Redis 闸门、补建未落库预留为取消终态，并安排回收。已确认记录保留历史但资格无效；取消不复售其名额。

恢复任务对比赛/请求做有界批次扫描；Redis 未决扫描每次推进下次检查时间，避免总是停留在同一批未决记录。全量人工对账当前最多10000条账本、扫描期限5秒，超限或证据不全则保持暂停，不宣称支持更大规模在线恢复。

## 迁移、开关与 API

先核验 V001→V002→`V003__async_registration.sql`。V003 扩展比赛的 epoch/初始化/初始容量/问题标记，以及请求的预留 epoch、发送预算、恢复期限和扫描时间；不重置历史数据。实际 MySQL 迁移本轮未运行。

两个开关均默认关闭：`RELIABLE_JUDGE_ENABLED`、`ASYNC_REGISTRATION_ENABLED`。启用异步模块不会自动把所有旧比赛切换通道；需要逐场审核切换。已有 REDIS 模式数据时禁止盲目回退代码或把配置切回 DB_SYNC。

参赛者仍用 J3 原报名及本人查询路径。新增管理员接口：

| 方法 | 路径 | 请求/行为 |
| --- | --- | --- |
| POST | `/api/contests/{id}/registration-admin/prepare` | expectedVersion/reason；关闭普通入口并准备切换 |
| POST | `/api/contests/{id}/registration-admin/reconcile` | expectedVersion/reason；关闸对账，一致后恢复 |
| GET | `/api/contests/{id}/registration-admin?page=0` | 每页20请求，附最多20次/请求的发布证据 |
| POST | `/api/contests/{id}/registration-admin/requests/{requestId}/review` | expectedVersion/reason/decision；可显式 additionalPublishAttempts/extendRecoverySeconds |

管理页面提供通道切换、对账、未知发布证据和有限复核表单，所有权限仍由后端当前数据库角色裁决。请求 ID 用字符串显示和发送，避免 JavaScript 大整数精度丢失。Redis 模式下不能通过旧资格恢复按钮绕过对账，必须走对账恢复。

## 本机复现

无需 Docker。已有原生 Redis 可执行文件时：

```bash
REDIS_SERVER=/absolute/path/to/redis-server scripts/test-registration-local.sh
```

无 Redis 时可明确选择临时编译本轮固定版本：

```bash
BUILD_TEST_REDIS=true scripts/test-registration-local.sh
```

脚本使用 Java8，校验 Redis 6.2.24 源码 SHA-256，仅在临时目录编译，不安装全局服务。JUnit 自建随机回环端口和独立 AOF 目录，关闭测试实例并清理；不会连接已有6379，也不清理用户已有 Redis。脚本退出清理自己的构建目录。需要本机 C 编译工具；普通 Maven 命令未显式开启 `codeoj.real-redis` 时会将这些测试标为跳过，不能据此宣称 Lua 已通过。

前端继续 `npm run build`；浏览器脚本及依赖用法见 J3，本轮补充了管理操作的 mock HTTP 检查。既有 `scripts/demo-local-contest.sh` 仍可验证 H2 比赛事务和真实 Java 判题；该样例手动派发，不能代替真实 Broker 联调。

## 本轮证据与边界

| 结果 | 范围 |
| --- | --- |
| 后端142项通过，0失败/错误/跳过 | Java8完整 reactor；含29项真实 Redis+H2、7项消息/传输契约；四个依赖基础设施的空 Spring 上下文沿用排除策略 |
| Redis/Lua 29项通过 | Redis6.2.24临时实例+AOF，Spring真实H2事务；预留落库窗口、重启、确认/释放重复、取消竞争、8用户争用2名额、跨窗口确认、时钟偏差、损坏/旧/丢失账本、有限人工恢复和HTTP权限 |
| 消息/传输7项通过 | mock Channel/Template；持久属性、Return+ACK、发送UNKNOWN、持久处理后ACK、身份隔离、退避、中断；不是实际Broker验收 |
| 前端构建通过 | 既有lint/依赖与包体积警告保留 |
| 浏览器9项通过、0页面异常 | 已有Chrome，无头，HTTP明确mock；含通道切换、未知证据、带版本复核和大整数ID |
| 既有真实Java样例通过 | H2普通报名→固定快照→真实javac/java→AC回写，保留J3证据口径 |

证据位于 `outputs/validation/j4-local-20261018`，含命令、测试分项、Redis版本/源码摘要、浏览器结果、受控报名样例、真实Java回归、源码摘要与累计工作区补丁。测试报名样例手动调用消费事务，未经过真实RabbitMQ。

**UNTESTED**：真实 MySQL8 迁移、排序规则、InnoDB锁/隔离/死锁，以及真实 Redis+MySQL+RabbitMQ+会话/网关/Feign 的联合链；多节点Redis Cluster只检查同槽键设计，没有运行Cluster。AOF重启验证不代表Redis整体丢失可无损恢复。完整J4验收仍需这些环境证据。

**待后续**：J5成绩投影、并列名次、重建和最终结算；账本归档/清理策略目前为保留，不自动删除未决身份。提交隔离和跨管理器/重启执行回收沿用J1/J2限制。部署、Docker、压测继续按用户要求后置。

参考核验：[Redis EVAL与显式KEYS](https://redis.io/docs/latest/commands/eval/)、[Lua时间与效果复制](https://redis.io/docs/latest/develop/programmability/eval-intro/)、[RabbitMQ Confirm与Consumer ACK边界](https://www.rabbitmq.com/docs/confirms)、[Spring AMQP2.4](https://docs.spring.io/spring-amqp/docs/2.4.x/reference/html/)、[Redis官方源码目录](https://download.redis.io/releases/)。本轮固定使用6.2.24验证，不把新版网页特性当成6.2保证。
