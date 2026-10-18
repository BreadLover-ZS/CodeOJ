# CodeOJ 赛事平台二次开发文档

版本：1.1｜复审日期：2026-10-17｜性质：待实施开发方案

目标读者：项目开发者、协作开发 Agent、代码审查者。本文给出业务、数据、状态、接口、任务顺序和验收标准；不是功能已实现或验收通过的证明。

## 1. 已确认的目标与范围

用户已确认：以 CodeOJ 为 Java 主项目；完善异步判题；将 DianPing 中打磨过的秒杀机制迁移成限量赛事报名；报名成功获得参赛资格；采用通过题数优先、同分按达成时间排序的简化赛制。commerce Agent 是另一个独立项目，两边并行推进。

成品定位：支持限量报名、受控代码执行、可靠判题和可重建成绩的编程训练平台。

主用户链路：

    管理员建赛并发布 → 用户抢占名额 → 报名确认 → 获得资格
                                              ↓
    查看最终榜单 ← 成绩结算 ← 异步判题 ← 比赛期间提交代码

必须交付：

1. 普通练习与比赛提交均能可靠处理，用户代码错误与平台故障可区分。
2. 限量报名不会超过数据库权威容量，同一用户不能重复取得资格。
3. 有效提交不会因消息发送故障而永久无人处理；旧执行者不能覆盖接管后的结果。
4. 比赛题目、用例、限制与计分规则固定版本，排名可从原始提交和有效判题结果重建。
5. 端到端演示、真实基础设施故障验证和可复现压测，有明确证据范围。

第一版不包含支付、会员、优惠券核销、团队赛、部分分数、ACM 罚时、退赛后再报名、自动补位、赛后自动重判、反作弊算法。赛事取消和人工异常处理属于第一版管理员能力，规则见下文。页面不用出现点评、券、订单等原业务名称。

这些是本版本的边界，不限制后续扩展。不要在首轮任务中顺手开发无关商城或社交功能。

### 1.1 可行性结论与关键取舍

方案可行：限量报名负责流量突发和资格一致性，异步判题负责长任务可靠执行，榜单负责结果投影，三者有自然业务联系。项目的深度取决于并发、恢复和计分是否真实正确，不能仅靠增加页面或复制秒杀代码获得。

复审后保留现有微服务、MySQL/Redis/RabbitMQ和Java主链；不另起赛事微服务，不引入分布式事务框架或通用调度平台。报名、提交与资格先在同一业务库闭环，有意接受比赛行短事务竞争，再通过实测判断是否需要细化锁。

第一版明确只承诺Java判题。其他语言在接口和界面禁用，或标明尚未支持；不能因为语言枚举存在就承诺可用。容器与JDK版本固定，开发机、执行机架构差异写入运行说明。

本次优化重点：补齐调度代次、数据库执行租约与沙箱执行名额的区别；固定报名回收和取消的竞争顺序；修正全场等待单个报名的可用性问题；增加同用户不同题并发计分和重建追平规则。

以下阶段只规定工作、依赖、交付和完成条件，不估工期、不按每周投入安排进度。J0—J6均属于最终方案，分阶段只是为了让每一步可验证。

## 2. 当前基线：哪些已经有，哪些还没有

### 2.1 本轮核查

- 仓库：/Users/zzds/Workspace/gitStore/CodeOJ
- 当前 HEAD：b2f43551a4929babe6f5b8be22007670f18767b9
- 秒杀参考仓库：/Users/zzds/Workspace/gitStore/DianPing
- CodeOJ 工作区存在未跟踪的 output/；本轮未读取或修改其内容。
- 本轮执行的是静态阅读；没有重新构建、运行服务、执行回归、压测或同步远端。历史联调报告仅作为定位线索。
- 当前父 POM 声明 Java 8、Spring Boot 2.6.13，既有微服务和前端继续作为迁移起点。依赖升级另设兼容检查，不与业务重写混成一次不可回滚提交。
- 现有环境Compose声明MySQL 8、Redis 6、RabbitMQ 3.12.6；其中MySQL标签未固定补丁版本。J0记录实际镜像版本/摘要，不把最新官网示例当成当前部署特性。

### 2.2 源码事实与风险

以下路径均相对于 CodeOJ 仓库：

| 当前入口 | 当前事实 | 本轮改造 |
| --- | --- | --- |
| codeoj-backend/codeoj-backend-question-service/.../service/impl/QuestionSubmitServiceImpl.java，doQuestionSubmit | 保存 WAITING，再调用 MQ 发送 | 本地事务内写提交和 Outbox |
| codeoj-backend/codeoj-backend-judge-service/.../judge/JudgeServiceImpl.java，doJudge | 读取 WAITING 后通过普通更新写 RUNNING | 原子抢占、执行租约、结果版本校验 |
| 同服务 rabbitmq/MyMessageConsumer.java | 成功 ACK；异常尝试写 FAILED 后不重新入队 NACK | 分类错误、受控重试、终态幂等、失败队列 |
| codeoj-backend/codeoj-backend-codesandbox/.../service/impl/JavaNativeCodeSandbox.java | 本机子进程；有代码/输出长度限制；先读流再限时等待 | 修复阻塞窗口、独立执行隔离和资源采集 |
| judge/strategy/JavaLanguageJudgeStrategy.java | 内存来自沙箱，当前沙箱固定返回 0；时间扣固定 10000 ms | 统一单位、明确统计口径，删除未经测量的固定扣减 |
| judge/codesandbox/impl/RemoteCodeSandbox.java | 当前调用点没有显式设置 HTTP 超时 | 明确连接、读取、总执行期限及重试语义 |
| mysql-init/create_table.sql | 有用户、题目、提交等表；没有比赛、报名、执行租约或 Outbox 表 | 增量迁移，不重置现有库 |

“...”是包路径省略，不是可直接执行的文件路径。附录给出核对命令与精确定位方法。上述风险来自静态代码，不等同于已经运行复现的故障。

当前沙箱读流可能在到达 waitFor(timeout) 前就阻塞；需要用死循环无输出、stderr 持续输出、stdin 不读取等样例验证，不能因配置了超时就判定保护生效。

## 3. 业务规则

### 3.1 比赛生命周期

比赛管理状态：DRAFT、PUBLISHED、FINALIZING、FINISHED、CANCELLED。

PUBLISHED 下的报名未开始、报名中、等待开赛、比赛中、已到结束时间由数据库时间与时间配置计算，避免多个定时任务分别维护互相矛盾的时间状态。

- 时间约束：registrationOpenAt < registrationCloseAt < startAt < endAt。
- 报名区间为左闭右开，比赛提交区间为左闭右开。数据库统一记录 UTC，界面按用户时区显示。
- 发布后容量、题目版本、计分版本和时间冻结。首版如需实质修改，取消并新建比赛；不原地修改已经产生报名/提交的规则。
- 注册关闭后，仍应处理关闭前已经有效受理的预留，不能按 MQ 消费时间重新否决。
- 开赛前检查报名待确认和对账异常。仅个别请求待确认时，该用户暂不能参赛，其他已确认用户正常参赛；数据版本错误、容量账本不明等全局异常才令registrationReady=false并阻止全场新提交。不得悄悄推迟既定时间；无法恢复时管理员取消重建。
- 到 endAt 后禁止新受理；已受理提交继续判题，比赛进入 FINALIZING。
- 所有有效提交完成判定、异常得到明确处理且排名重建校验通过后，才进入 FINISHED。
- 有未解决的平台故障时保持结算中。不得把平台故障计成用户 WA 来强行完赛。

### 3.2 报名与名额

- 用户身份从现有登录会话获得，不接受请求体中的 userId。
- 只有开放的报名活动接受新预留，每个 userId、contestId 唯一。
- Redis 成功预留后接口返回 202 和 registrationRequestId、PROCESSING；不返回“报名成功”。
- MySQL 内成功写入有效报名记录后，才返回 CONFIRMED 并授予参赛资格。
- 名额规则是有效原子预留先到先得；不承诺按客户端点击时间、网络到达顺序或绝对公平排序。
- 超时与数据源不可用返回 UNKNOWN/UNAVAILABLE，客户端用原业务标识查询，不创建第二次报名请求。
- 进入预留前的未开放、已关闭或无名额拒绝不创建持久报名请求；客户端稍后可重新尝试。预留一旦受理，同一比赛/用户始终映射到原请求，网络重试沿用原 Idempotency-Key。暂时故障保留 PROCESSING 或转人工核查；FAILED/CANCELLED 是终态，不靠换 key 重开资格，首版不支持终态后的再次报名。
- 明确失败的预留才能按事件标识幂等释放。仅因没有收到 Confirm、重试次数用尽或某次查询为空，不足以证明可以释放。
- 发布后取消比赛立即关闭报名和参赛入口，阻止尚未落库事件继续确认。已确认资格保留审计记录但不再有效；预留标记为取消并对账，首版不把取消容量投入另一场活动。
- 无退赛/补位流程，避免迁移时引入第二套库存生命周期。

### 3.3 资格与题目权限

比赛提交必须同时满足：本人报名 CONFIRMED、比赛 PUBLISHED 且registrationReady=true、数据库受理时间在比赛区间、题目属于发布快照、账户有效。registrationReady表示全局资格数据可可信使用，不等于所有人的异步请求都已完成。

比赛专用题目开赛前不出现在公开练习列表；隐藏用例、答案、他人代码不能通过普通题目详情、列表、导出或旧接口泄漏。赛中绕过比赛接口调用普通提交入口，也不能对受保护比赛题目取得额外能力。已有公开练习题是否用于公开训练赛由比赛类型声明，不能把公开题宣称成未见题。

首版普通练习结果不计入比赛；比赛结果按 contestId、发布版本、比赛提交记录计算，不依赖全局 acceptedNum。

### 3.4 简化赛制的精确定义

对每个用户、比赛题目，取所有最终判定为 AC 的有效提交中最早的 acceptedAt（服务端受理时间，持久化在提交记录中），称 firstAcSubmittedAt。这里不是判题完成时间，也不是客户端时间。

solvedCount 是具有该时间的题目数。achievedAt 是这些时间的最大值。排序：solvedCount 降序，再 achievedAt 升序。两者相同则并列，榜单采用竞赛名次 1、1、3。零 AC 全部并列；展示可用 userId 稳定排序，但不据此改变名次。

例如甲、乙各 AC 三题，第三个首次通过的提交受理于开赛后 40、50 分钟，即使甲更晚收到判题结果，甲仍在前。不累计三道题的用时，不加错误罚时。

同题重复 AC 不加分。迟到的早期提交变为 AC 时，必须重新计算该题最早时间，不能“已有 AC 就全部忽略”。赛中榜单标明临时；FINALIZING 仍可能因迟到结果变化。

手算基准：四名已确认用户U1—U4、题A/B。U1首次AC受理时间为A=开赛后10分钟、B=20分钟；U2为A=15分钟、B=20分钟；U3/U4无AC。临时榜为U1/U2并列第1、U3/U4并列第3。随后U1另一条受理于12分钟的B提交才返回AC，则U1的B最早时间改为12分钟，achievedAt=12分钟；最终名次为1、2、3、3。任意重复或消费顺序均需得到相同结果。

## 4. 架构与模块职责

首版保留现有微服务。比赛、报名、提交的权威数据先放在题目服务同一个 MySQL 业务库，分包隔离；资格校验、取消竞争和提交受理可在同库事务处理。以后如拆独立赛事服务，需要另做跨服务一致性设计，不能沿用这里的本地事务保证。

| 模块 | 责任 |
| --- | --- |
| user-service / gateway | 既有身份、用户角色、路由；管理入口只允许管理员 |
| question-service 的 contest 包 | 比赛发布快照、容量、报名确认、资格与赛程、成绩投影及结算 |
| question-service 的 submission 包 | 提交受理、幂等、执行权威状态、结果提交、Outbox |
| judge-service | 领取执行权、调用沙箱、续租、分类结果、提交有效结果 |
| codesandbox | 按 attempt 运行隔离环境，执行与资源限制，不直接修改业务库 |
| Redis | 报名快速预留、限流、可重建榜单缓存；不作为最终报名/成绩唯一事实源 |
| RabbitMQ | 报名与判题独立队列/消费者，单独配置 prefetch、并发和告警 |
| frontend | 活动、报名处理结果、资格、赛内编辑器、判题进度、临时/最终榜单、管理诊断 |

Outbox 的公共发布基础能力可以复用，报名与判题保留各自事件类型和业务状态。不要直接共用点评订单实体或让报名与判题挤在同一队列。

## 5. 数据与迁移设计

以下为拟新增逻辑模型，表名以实现前的命名检查为准；不是当前已有表。

| 表/扩展 | 关键字段 | 约束与索引 |
| --- | --- | --- |
| contest | id、title、capacity、remainingCapacity、四个时间、status、version、publishedVersion、registrationReady、ruleVersion、activeProjectionVersion、finalVersion | 时间/容量 CHECK 或服务端校验；status/startAt 索引；取消与受理锁同一比赛行 |
| contest_problem_snapshot | id、contestId、publishedVersion、problemCode、sourceQuestionId、title/content、caseBlobRef、caseHash、limits、snapshotHash | UNIQUE(contestId,publishedVersion,problemCode)；快照不可变；用例仅内部读取 |
| contest_registration_request | id、contestId、publishedVersion、userId、eventId、requestKey、requestHash、reservedAt、status、stateVersion、reclaimState、lastError、createdAt | UNIQUE(contestId,userId)、UNIQUE(eventId)；status/createdAt、reclaimState索引；同场请求键由业务唯一行校验，容纳恢复补建记录 |
| contest_registration | id、contestId、userId、requestId、confirmedAt、status | UNIQUE(contestId,userId)、UNIQUE(requestId)；资格只读此权威表与比赛状态 |
| question_submit 扩展 | contestId 可空、problemSnapshotId、judgeSpecRef/judgeSpecHash、acceptedAt、requestKey、requestHash、executionStatus、dispatchGeneration、activeAttempt、executionToken、leaseOwner、leaseUntil、nextRunAt、recoveryDeadlineAt、recoveryPolicyVersion、verdict、resultVersion | UNIQUE(userId,requestKey)；executionStatus/leaseUntil、executionStatus/nextRunAt；contestId/userId/problemSnapshotId/acceptedAt |
| judge_attempt | id、submissionId、attemptNo、executionToken、state、deadlineAt、startedAt、finishedAt、errorCode、resourceMetrics、resultDigest | UNIQUE(submissionId,attemptNo)；executionToken 唯一；不要保存隐藏用例到公开日志 |
| reliable_outbox | id/eventId、type、aggregateId、aggregateVersion、payloadVersion、payload、publishState、leaseToken、leaseUntil、nextRetryAt、attempts | UNIQUE(type,aggregateId,aggregateVersion)；publishState/nextRetryAt |
| publish_attempt | id、eventId、attemptNo、confirmState、returned、errorCode、createdAt | UNIQUE(eventId,attemptNo)；用于识别早期发送仍未知的情况 |
| contest_problem_result | contestId、userId、snapshotId、firstAcSubmittedAt、sourceSubmissionId、projectionVersion | UNIQUE(contestId,userId,snapshotId,projectionVersion)，支持新旧重建版本并存 |
| contest_scoreboard | contestId、userId、solvedCount、achievedAt、projectionVersion、updatedAt | UNIQUE(contestId,userId,projectionVersion)；按版本与排序字段索引；缓存失效可重建 |
| operation_audit | actorId、action、targetId、reason、before/afterVersion、traceId、createdAt | 管理员操作审计，重放不清除既有失败记录 |

报名确认事务：锁比赛行并校验未取消 → 锁请求且校验PROCESSING/原预留身份 → 查有效报名 → 条件扣减 remainingCapacity > 0 → 写报名记录/请求确认状态，同成同败。重复事件读取原结果，不再次扣减；业务完成由请求/资格表证明，不用Outbox的发布状态代替。

同库事务不做Redis、MQ、Feign或沙箱网络调用。事务内锁按“比赛→报名请求/资格→提交”顺序取得；恢复器、取消与人工处理遵守相同顺序。唯一约束冲突回读原业务结果；数据库死锁回滚后有限重试整笔事务，不在已失效事务里继续扣容量。单场比赛行会序列化部分受理操作，这是首版明确的吞吐边界，不宣称Redis入口吞吐等于最终确认吞吐。

新提交的requestKey非空，历史记录可以为空；对相同用户的练习/比赛提交统一约束，requestHash必须包含提交类型、比赛/快照、语言和代码。报名的key是另一业务命名空间，查询和错误文案明确区分。新关键时间使用数据库UTC、微秒精度；不能沿用旧秒级时间造成无意的大量并列。

报名 requestHash 绑定比赛、发布版本及有业务含义的请求参数；不能包含每次变化的追踪ID。Redis 用户映射和预留账本同时保存原 requestKey/hash，以便在 MySQL 写入前崩溃后恢复。相同 key 不同内容返回409；同比赛/用户换 key 也不能创建第二次预留，只返回原请求或明确冲突。

报名Idempotency-Key的作用域是“报名操作+contestId+当前userId”，不同比赛可独立使用相同字符串；不能依赖按比赛hash tag的Lua提供跨比赛的全局key唯一性。响应丢失且客户端尚不知道requestId时，用本人报名查询或原key重试定位同一预留；数据库暂无记录但Redis查询失败时返回未知，不能据此创建新请求。

Outbox 的 aggregateVersion 是业务事件代次，payloadVersion 仅是消息结构版本，两者不能混用。判题首次派发使用初始代次，租约恢复事务递增调度代次并创建新 eventId；同代次发布重试沿用原 eventId。计分事件以有效 resultVersion 为代次。这样恢复可产生新事件，重复扫描又不会无限插入同代事件。

提交受理事务：先按 userId/requestKey 查重并比较内容哈希 → 比赛行/资格校验 → 记录数据库统一时间 acceptedAt 和不可变题目版本 → 写提交与 Outbox。幂等重放不因比赛已经结束而失去原提交结果；同键不同内容返回冲突。重试 key 必须跨网络重试稳定，不能每次自动换。

普通练习也在受理时固化判题规格judgeSpecRef/hash，不能在排队后读取刚被编辑的最新用例。比赛提交引用发布快照；练习提交引用当时题目版本生成的私有规格。规格存储若在业务事务外，先持久化内容寻址对象再在事务内引用，允许清理孤立对象，不允许先提交一个指向尚未存在用例的引用。历史记录保留其原有语义。

迁移采用 expand → backfill → switch → cleanup：

1. 在隔离 MySQL 备份副本演练增量 SQL；新字段先兼容旧数据。
2. 历史提交保留原语义，contestId=NULL；不伪造历史执行资源和 attempt。
3. 代码上线兼容新旧结构后切换写入路径；为新状态提供明确 API 映射。
4. 暂停新提交并排空/登记旧 code_queue 的在途消息，再切换消费者；不能让新旧消费者同时用不同状态规则修改同一记录。
5. 保留旧字段的只读兼容期，确认前端与查询迁移后再清理。
6. 回滚代码前先核查是否已有新状态/新比赛数据。不能只回退二进制却留下不可理解的数据，也不能用删表作为业务回滚。

## 6. 两条核心状态链

### 6.1 判题：业务事实与执行尝试分开

提交执行状态建议 QUEUED → RUNNING → COMPLETED；可恢复基础设施失败进入 RETRY_WAIT，再回 QUEUED；用尽策略进入 NEEDS_ATTENTION。用户代码 CE/RE/TLE/MLE/OUTPUT_LIMIT/WA/AC 都属于完成判定的 verdict，不是平台任务失败。

旧 SUCCEED 表示流程完成，并不等于 AC，API 迁移必须保留这个区别。

- 原子抢占由题目服务数据库事务执行，返回 attemptNo、executionToken、leaseUntil 和快照；不能先 RPC 读取再普通更新。
- 续租和最终写入均校验当前 token、attempt 和未过期租约。
- 租约比较和nextRunAt到期判断都在权威数据库中使用UTC时间；worker本地时间只用于定时发心跳，不能用其漂移时钟延长写入资格。
- 租约过期由恢复器创建新的可调度事件，同一恢复代次只创建一次；旧结果即使晚到也被拒绝并记录审计。
- 多次物理执行可能发生，有效业务结果按版本只提交一次；用户看到的是有效结果。
- 结果写入与计分 Outbox 在同一事务提交，消费端再更新成绩投影。
- 收到重复消息且另一执行者仍运行时，不能把提交改 FAILED；按已登记的权威执行状态安全确认消息或延后处理。
- 先持久化可恢复状态再 ACK。不能靠无限 requeue=true 热循环重试，也不能因 NACK(false) 就假定一定进入死信队列。

重试策略按错误类别配置最大尝试次数、退避、单次期限和提交总恢复期限；上线前必须给出有限配置并验证耗尽路径。恢复不能每次重置总期限；用户代码判定不触发基础设施重试，持续失败进入 NEEDS_ATTENTION，人工处理保留原提交身份和全部 attempt。

### 6.2 判题消息、领取与恢复的执行规则

消息只携带eventId、submissionId、dispatchGeneration、payloadVersion和traceId，不携带隐藏用例/代码。claim在同一事务中校验事件代次、QUEUED状态、nextRunAt和总期限，递增attemptNo并生成token。旧代消息和重复消息不能凭submissionId抢到新一轮执行权。

消费者处理顺序：

1. 校验格式/消息版本；未知格式进入可追踪隔离队列，不能修改任意业务提交。
2. 已完成、已过期代次、另一有效租约已领取：读取权威状态后ACK。后一种情况依靠数据库恢复器兜底，不依赖这条重复消息保持未ACK。
3. 可执行消息先预占本节点slot，再尝试claim；领取失败立即释放slot，无slot时保留QUEUED并受限等待/退避，不抢占数据库执行租约。领取成功后启动沙箱，执行期间续租，结果按token和期限提交。
4. 平台暂时错误：先事务写RETRY_WAIT及nextRunAt、关闭当前attempt，再ACK。恢复器在到期时原子切换QUEUED、递增dispatchGeneration并写新Outbox。
5. 业务状态无法查询/无法持久化：不能ACK为已处理；消费者退避/暂停，交由连接恢复重投，禁止立即无限热重入。

超过租约的RUNNING由恢复器CAS关闭旧attempt并创建下一代事件；重试耗尽只转NEEDS_ATTENTION。人工重试只对非完成提交创建新调度代次，不能重置总次数、抹掉失败或改变已完成判定。若未来增加重判，另建显式结果修订规则。

已用尽次数或总期限的提交不能仅靠“重放”按钮再次运行。管理员如需恢复，必须显式追加有限的尝试额度/恢复期限，记录原因、expectedVersion及新的recoveryPolicyVersion；累计attempt不清零，旧策略留档。普通自动恢复器无权追加额度。这样既可处理修复基础设施后的任务，又不会把人工重放变成无限自动重试。

Outbox发布成功后仍保留事件，另扫描长时间QUEUED未领取与过期RUNNING：Broker收到消息不代表业务已执行。发布状态不能代替提交状态；持久队列、消息持久化、mandatory与Confirm均需配置验证。毒消息隔离队列的存在及重放权限也要有实际证据。

### 6.3 执行容量与租约

数据库token只限制谁能提交有效结果，不能阻止旧沙箱在失联后继续耗CPU。每个沙箱管理器设置有限并发slot、有限等待队列及残留执行扫描；prefetch/消费者并发与slot匹配。无slot时不得无限堆积RUNNING，领取/等待期限有界。

同一executionToken重复启动必须返回既有执行标识；新token可能产生新的物理执行。新token接管后尽力终止旧执行，旧执行的结果仍被拒绝。总执行期限由沙箱本地看门狗执行，不能只靠远程worker续租维持。平台过载触发限流/退避，不能把等待资源误算为用户代码TLE。

### 6.4 报名：复用点评机制并改写语义

报名请求：PROCESSING → CONFIRMED / FAILED / MANUAL_REVIEW；取消活动可把非确认请求转 CANCELLED。UNKNOWN 是查询/发送证据不完整的表达，不是可以释放名额的业务终态。

Redis 预留应原子校验活动开关、报名时间、剩余容量、用户映射，并记录 eventId/requestId/userId/contestId/reservedAt/version。所有同场 Lua 键使用相同 {contestId} hash tag。

报名预留的 reservedAt 由 Redis TIME 取得，不接受客户端时间；发布的UTC报名时窗作为Lua固定版本参数。提交受理和最终业务状态仍以MySQL为权威。两处时钟需监控偏差，超过配置容差暂停新预留并告警；边界验证覆盖两种时钟偏差，不能声称整个系统存在无偏差全局时钟。

活动初始化与恢复必须带发布版本和一次性标记，不能每次发布重试就重置Redis库存。恢复时先关闭入口，对齐MySQL已确认资格与可恢复的未决预留，再生成可用容量；不能把MySQL剩余容量直接覆盖Redis而忽略在途预留。账本不完整则保持暂停/人工核查，MySQL条件扣减仍是防超容量的最后约束。

必须覆盖 Redis 预留成功但 MySQL 请求/事件未写入的窗口：预留账本扫描按稳定标识幂等补建；接口重试先找原映射。MySQL 确认后标记预留完成，处理“数据库成功而 Redis 完成标记失败”的重复扫描。

发布重试记录每次 attempt；Confirm、Return、超时分别记录。任何先前 attempt 仍未知时，不能根据后一次失败释放名额。长期未知升级人工，后台显示证据和可执行处理动作。

明确失败才进入幂等回收；回收失败本身也要持久化重试。人工释放必须锁住权威报名状态并禁止迟到消费者重新确认，不允许直接手工 INCR Redis 绕过状态机。

更具体地，确认消费者和终止操作都锁比赛及报名请求，且只有PROCESSING允许确认；MANUAL_REVIEW暂停自动确认。复核决定继续时，在同事务中更新stateVersion并重新调度原报名事件，沿用原预留eventId；决定失败/取消时先提交终态与RELEASE_PENDING，回收器再按eventId/发布版本执行Lua并写RELEASED。数据库终态先于Redis释放，迟到消费者不能重新确认。CONFIRMED不进入普通失败回收，避免已有资格仍有效却再次售出同一名额。

比赛取消的权威步骤是锁比赛并提交CANCELLED；此后所有确认/受理入口在同一比赛锁下拒绝新业务，Redis关闸与预留对账异步幂等补齐。尚未补建MySQL记录的Redis预留由扫描器补建为取消终态并审计。不得用跨网络调用组成一个看似原子的取消事务。

预留账本和用户映射不能在事件未决时因普通缓存TTL消失；活动键保留至比赛结束、未决收敛且审计归档后，再按明确保留策略清理。对账口径：capacity = confirmed + databaseRemaining；Redis可用量只在入口已暂停且账本一致时，与databaseRemaining减去未决有效预留对齐。对账不直接将任一侧数值覆盖另一侧。

**保证边界：** Redis 与 MySQL 不是一个事务。Redis 整体丢失、且某条预留从未进入持久化业务账本时，不能宣称该预留必然可恢复。需暂停报名、保留原请求身份、按可用账本恢复并暴露无法判定记录。若以后要求这类故障也不丢任何已受理报名，应另选“先持久化受理再排队”的权威准入设计，重新测量延迟；本版不掩盖该窗口。

## 7. API 契约

路径均为拟新增业务路由；保持现有 /api/question 的兼容期。以下错误码是新增应用码，不直接复用 HTTP 文案。

| 方法与路径 | 用途/响应 | 关键规则 |
| --- | --- | --- |
| POST /api/contests | 管理员创建草稿，201 | 容量/时间/题目合法；身份从会话取 |
| POST /api/contests/{id}/publish | 发布并固化版本 | 请求携 expectedVersion，冲突返回409 |
| POST /api/contests/{id}/cancel | 取消，要求原因 | 同事务关闭资格，审计；重复取消幂等 |
| GET /api/contests、/{id} | 列表与详情 | 未到开赛不泄露专用题目内容和答案 |
| POST /api/contests/{id}/registrations | 抢名额，202 | Idempotency-Key；返回 requestId/status/statusUrl |
| GET /api/contests/{id}/registrations/me | 查询本人报名 | PROCESSING/CONFIRMED/FAILED/MANUAL_REVIEW/CANCELLED；同时返回比赛状态与资格是否有效，故障不伪装NOT_FOUND |
| POST /api/contests/{id}/submissions | 比赛提交，202 | 问题快照、language、code；幂等键；成功返回 submissionId |
| GET /api/submissions/{id} | 查询状态/判定 | 本人或管理员，隐藏敏感内部错误 |
| GET /api/contests/{id}/scoreboard | 临时/最终榜 | 包含 ruleVersion、projectionVersion、asOf、pendingCount |
| GET /api/admin/operations | 异常列表 | 按业务/事件/attempt定位，分页，不暴露代码和凭据 |
| POST /api/admin/events/{id}/replay | 受控重放 | 管理员、原因、expectedVersion；复用原业务ID并审计 |

建议映射：未登录401，已登录无权限403，资源不存在/不可见404，状态/幂等冲突409，容量耗尽409、报名时间关闭409、非法内容400、限流429、基础设施暂不可判断503。报名处理中的202必须允许后续查询。

内部执行权、续租、结果提交接口使用现有内部认证并限制网络访问；前端不可访问。废除普通实体 updateById 作为执行状态通用写入口，改成显式 claim/heartbeat/complete/fail 命令，避免绕过状态机。

## 8. 沙箱与运行隔离

首先修复读写流和超时顺序：并发有界消费 stdout/stderr，输入写入也受期限控制；统一总期限；超时回收执行进程树/容器，关闭流和临时文件。输出超限判定为 OUTPUT_LIMIT，不只截断后继续等待。远程调用超时不代表执行一定终止，attempt 标识与回收任务必须能定位残留运行。

大改交付需要每次执行有独立隔离边界：非特权用户、禁网络、只读根文件系统、独立有上限临时目录、CPU/内存/PID限制、无宿主业务目录和凭据。受信的执行管理器负责生命周期，用户程序不可接触 Docker socket。仅把现有整个沙箱服务放进一个容器，不等于提交级隔离。

时间区分排队、编译、运行 wall time 和 CPU time；内存注明测量方式/单位。运行 wall time 是否包含 JVM 启动由契约固定，首版包含启动，不凭经验扣10秒；编译设置独立限额。资源统计缺失返回 unknown，不以0表示“没有占用”。机器负载导致波动需要在报告中披露。

资源上限通过隔离环境小样本基线确定，写入版本化配置。不要在文档阶段承诺某CPU配额能支撑多少判题QPS。容器限制是约束之一，不等于已经证明可公开接受任意恶意代码。

判题协议还必须固定：一次attempt编译一次，每个用例在独立运行进程内执行；逐用例时限与整次attempt总期限同时存在。首版文本比较仅规范CRLF为LF、忽略末尾一个换行，不随意trim每行、忽略大小写或自动压缩空白；需要其他比较器时写入题目快照版本。

同一attempt的编译产物只读；各用例使用独立可写临时目录与受控进程组，用例结束清理全部后代进程，不共享上个用例留下的可写文件。只重启Java主进程而保留后台子进程/可写目录，不能算用例隔离。

CE只来自明确编译诊断；TLE来自受信执行器确认运行期限超限；MLE需明确内存限额触发证据；输出预算超限为OUTPUT_LIMIT；正常资源范围内非零退出为RE。沙箱不可达、编译基础设施缺失、资源指标无法确定归因等归入平台错误，不凭HTTP超时或字符串猜成TLE/MLE。用例总数、输入/输出字节、编译和运行预算都需设上限。

源代码和隐藏用例是判题必需持久数据，不属于“完全不存储”；限制读取者、存放位置和保留期，公开日志只记录安全摘要。容器名称/临时目录由服务端执行ID生成，用户代码/题目文本不能拼成shell命令。

## 9. 榜单、投影与重建

权威来源是有效提交及其最终判定，投影和 Redis 都可丢弃重建。

计分消费者先锁同projectionVersion下的contest/user成绩行，再更新该题最早有效AC并重算用户solvedCount/achievedAt。只有题目锁不够：同一用户两道题同时AC会竞争总分。首次成绩行先幂等创建，再锁行；乱序更早结果到达时更新firstAcSubmittedAt。重复事件不得累加分数，不把“消费过某个用户”作为所有后续事件的去重键。

榜单缓存与数据库投影发生不同步时显示 asOf，优先保证可重建；缓存更新失败不回滚已完成判题。重建写新 projectionVersion，完成校验后原子切换读版本，避免用户看到一半旧一半新。完全并列按业务并列名次显示，不能由 ZSet 成员字符串悄悄决胜。

首版赛中提供缓存清除后按当前投影恢复；完整投影重建先安排在FINALIZING且所有有效结果已终态时进行，短暂暂停计分消费、从权威提交构建新版本、校验并切换，重投消息再按最终事实幂等处理。赛中在线完整重建若后续需要，必须增加一致快照和增量追平协议，不能扫描旧数据后立即切换而漏掉并发AC。该选择不影响最终榜单可重建的交付目标。

首版按数据库复合排序查询榜单，Redis先缓存版本化页；不把题数和微秒时间强拼成超出双精度整数范围的ZSet分值。所有已确认参赛者都有零分行；同分并列名次跨页计算，不能只在当前页内重新从1计名次。

比赛结束后列出受理截止前的全部有效提交，等待其进入已完成判定；NEEDS_ATTENTION 由管理员解决并保留原因。结算验证：按原始记录重建结果与投影一致、无未解决提交、无重复资格、无超容量，再固定 finalVersion。赛后如发现错误，修订新版本并保留旧榜单，不能静默覆盖。

## 10. 开发任务与依赖

下面的单项完成定义为：实现、必要迁移、针对性测试、故障证据、学习说明和接口示例齐全。不制定工期；每阶段通过完成条件后推进依赖它的工作。

| 阶段/任务ID | 主要修改位置（拟新增目录已标明） | 交付与验收出口 |
| --- | --- | --- |
| J0 基线与契约 | README、现有模型/枚举、演示数据、结果契约 | 保存当前提交/消费/沙箱请求样本；列清已验证与未验证 |
| J1 判题与沙箱 | judge策略、RemoteCodeSandbox、codesandbox执行器 | 明确verdict与系统状态；死循环/双流/输出超限/超时回收均有证据 |
| J2 可靠判题 | question-service拟新增submission/reliability包、judge worker、模型与SQL迁移 | DB+Outbox原子；两消费者抢占；租约恢复；旧结果拒绝；有限重试与异常列表 |
| J3 比赛主链 | question-service拟新增contest包、快照/资格表、前端比赛页 | 普通容量受控报名到比赛提交可完整演示；时间/权限/版本不能绕过 |
| J4 限量报名 | 拟新增registration包、Lua、独立MQ队列、对账任务 | 迁移机制而非订单实体；预留窗口补建、未知结果、回收失败与取消竞争通过 |
| J5 成绩结算 | 拟新增scoreboard包、事件投影、重建/结算入口 | 重复/乱序/更早AC晚到/赛后完成、并列和零分规则全部准确 |
| J6 系统证据 | 隔离环境脚本、监控、故障演练、前端闭环、部署与学习文档 | 真实MySQL/Redis/RabbitMQ、多实例、执行隔离与完整业务负载有可复现记录 |

J1与J2可交错，J3页面可同步推进；J4依赖J2的发布/状态基础；J5依赖J3的规则与快照；J6贯穿各阶段。每轮提交围绕一个可验证行为，不一次性重写全仓库。

### 10.1 各阶段具体要做什么

#### J0：建立可复现基线和契约

1. 启动隔离开发环境，记录实际Java/Maven/MySQL/Redis/RabbitMQ版本、镜像和配置；检查测试发现机制，保存真实测试计数。
2. 跑通Java普通练习的AC、WA、CE和状态查询，画出当前请求→DB→MQ→沙箱→结果链。
3. 固定执行状态/verdict、时间与内存单位、比较器、消息代次、幂等键和数据库迁移约定。
4. 建立状态转换表、故障目录和小型手算比赛数据；保护原仓库未跟踪文件与原有数据。

完成条件：新环境能复现基线；每个现有能力/缺口均有源码或运行证据；契约能支持后续测试。此阶段不靠空contextLoads测试证明业务正确。

#### J1：把单次判题做正确

1. 修复并发读流、受限写入、总期限、输出限制和进程树回收，移除固定扣时。
2. 完成Java编译/运行的结构化结果和明确错误归因；加入换行、边界输出与多用例隔离样例。
3. 建立提交级容器执行、资源限制、本地看门狗和有限slot；保护执行管理接口与隐藏用例。
4. 修改判题策略、远程超时、前端判定展示，使用户错误与平台故障可区分。

完成条件：JV07/JV08及协议样例有真实隔离执行证据；进程/容器/临时文件能回收；资源缺失不会伪装成0或AC。

#### J2：把判题变成可恢复任务

1. 增量加入提交字段、judge_attempt、Outbox与publish_attempt，完成私有判题规格固化及提交+事件本地事务。
2. 实现claim/heartbeat/complete/fail内部命令，携带代次和token；替换旧通用状态更新入口。
3. 实现发布确认/Return记录、消费者分类ACK、RETRY_WAIT调度、长QUEUED扫描、租约接管和NEEDS_ATTENTION。
4. 增加异常查询及有审计的人工重试；验证双消费者、提交后发送失败、结果后ACK失败与旧token写回。

完成条件：JV01—JV06、JV19/JV20/JV21/JV25/JV29通过对应真实依赖验证；任务可恢复或进入可定位人工态，无无限重试。迁移切换演练包含旧队列在途消息。

#### J3：建立完整比赛业务

1. 建赛、快照、发布、取消，固定四个时间和规则；屏蔽旧API对私有赛题的泄露。
2. 先提供数据库容量约束下的普通报名，直接复用最终资格表/确认函数；这是验证业务的过渡入口，不是最终高并发方案。
3. 完成确认资格→赛题列表→编辑器→幂等提交→结果查询；维护registrationReady全局开关。
4. 验证发布时间、报名/参赛时间边界、取消竞争、公开练习与比赛结果分离。

完成条件：一场比赛能端到端演示，JV13—JV15及相关权限/时间用例通过。J4切换后普通报名入口关闭，不能与Redis通道同时分配名额。

#### J4：迁移成熟秒杀机制

1. 按赛事语义重写Lua预留、用户映射、稳定请求身份、发布版本和账本；不复制订单表。
2. 接入Outbox、独立报名队列、同库确认事务、幂等完成标记和预留窗口补建。
3. 实现未知发布结果、MANUAL_REVIEW、终态先落库再回收、取消、初始化/恢复和账本保留。
4. 暂停入口并排空过渡报名，再切换新通道；做容量、重复、时钟、断连、回收和迟到消费者的组合测试。

完成条件：JV09—JV13、JV22—JV24与JV28有真实MySQL/Redis/MQ证据；资格≤容量，重复请求身份稳定，账本可收敛或明确暴露未决。

#### J5：完成成绩、榜单与最终结算

1. 判题结果事务生成计分事件；按用户锁处理重复/乱序、同题和不同题并发。
2. 实现数据库排序、零分参赛者、并列及跨页名次，缓存只按版本投影。
3. 实现FINALIZING、待定列表、全量重建、新旧版本切换与最终版本固定。
4. 用独立手算数据核对成绩，清缓存重建并模拟早期AC结果迟到、消息重复和同用户并发。

完成条件：JV16—JV18、JV26/JV27及小数据手算一致；平台故障不会被记成WA；最终榜单可从原始事实重建。

#### J6：形成可交付系统与个人能力

1. 合并真实API前端闭环、管理员异常处理、监控、部署、备份恢复和迁移回滚说明。
2. 对报名、判题及混合业务分别压测，区分入口吞吐、确认吞吐、队列等待和执行能力。
3. 运行故障矩阵，保留命令、数据、环境、失败、未测项与复现步骤；不能只挑最好结果。
4. 编写演示脚本和源码阅读地图，亲自解释至少一次容量竞争、一次租约恢复、一次乱序计分。

完成条件：全部关键用例有对应证据与明确状态，文档能让另一人复现；简历描述只覆盖实际实现和测得结果。

## 11. 验收用例与判定

| ID | 输入/故障 | 预期不变量 |
| --- | --- | --- |
| JV01 | 同幂等键重发提交；再换代码重发 | 同内容返回原ID；不同内容409；只有一条受理事实 |
| JV02 | 提交事务提交前/后分别杀API | 前者无提交无事件；后者能恢复发送，无永久孤儿任务 |
| JV03 | MQ断连、Confirm丢失、不可路由 | 分别记录证据并重试；重复发送不产生重复有效结果 |
| JV04 | 两消费者同时处理同一消息 | 同一有效租约只授予一个执行者；重复消息不写FAILED |
| JV05 | RUNNING时杀worker并重启 | 过期后接管；次数/期限有界；旧token续租和结果均被拒绝 |
| JV06 | 结果事务后、ACK前杀worker | 重投读取终态，不重复计分、不覆盖结果 |
| JV07 | 无输出死循环、stderr刷屏、stdin不读 | 均受总期限/输出预算约束，执行资源最终被回收 |
| JV08 | 内存、PID、网络、文件越界样例 | 在隔离环境按所配置约束被阻止/终止，有真实执行证据 |
| JV09 | M名额、N>M个用户、重复请求混合 | MySQL有效报名≤M；每用户≤1；处理完后无不明悬挂预留 |
| JV10 | Redis预留后、DB落盘前杀API | 持久Redis账本可恢复时补建同ID事件；完整数据丢失单列边界 |
| JV11 | DB确认后Redis标记失败；回收时Redis失败 | 重试不重复扣减；回收操作持久且幂等 |
| JV12 | 报名关闭前预留、关闭后消费 | 按原有效预留规则确认；关闭后新请求拒绝 |
| JV13 | 取消与报名消费、取消与提交并发 | 同比赛锁/版本顺序裁决；取消之后无新资格/提交被受理 |
| JV14 | 未报名/他人账号/普通API绕过赛题 | 服务端拒绝；不能读取隐藏用例与他人代码 |
| JV15 | 赛中编辑原题，再执行既有提交 | 按固定快照判定，不读取被改动原题作为标准 |
| JV16 | 同题多次AC，较早提交结果更晚到 | 题目只计1分，firstAcSubmittedAt正确向前更新 |
| JV17 | 截止前受理、截止后完成 | 有效计分；截止后新受理拒绝；无待定时才最终结算 |
| JV18 | 清空榜单缓存，重建投影 | 与权威数据重算一致；并列/零分及时间口径正确 |
| JV19 | 基础设施反复失败到重试上限 | NEEDS_ATTENTION可定位、可审计处理；不冒充WA或自动无限重试 |
| JV20 | 旧dispatchGeneration消息在新代次到达 | 不能领取新执行权或覆盖当前状态；恢复代次只创建一次 |
| JV21 | Broker已Confirm但任务长期QUEUED未领取 | 业务扫描可发现并有界重派；不把发布成功当执行完成 |
| JV22 | 终止/回收与迟到确认消费者竞争 | DB终态先提交；已释放请求不可确认；CONFIRMED不会普通回收 |
| JV23 | 取消已提交但Redis关闸失败 | 权威确认与提交入口拒绝新业务，Redis补偿有据可查 |
| JV24 | 重复初始化、恢复时存在未决预留/时钟偏差 | 不重置/超发库存；偏差超阈值停止预留，未知账本保持暂停 |
| JV25 | slot耗尽、旧沙箱失联后仍运行 | 物理执行数受限，旧结果无效，残留运行最终回收 |
| JV26 | 同用户两题同时AC、并列跨页与零分用户 | 总分不丢更新，跨页名次正确，零分参赛者完整 |
| JV27 | 最终重建期间迟到消息和旧版本缓存 | 仅在权威结果收敛后切换；读版本一致，重投不重复加分 |
| JV28 | 报名在途账本到达普通缓存TTL | 未决记录不被自动清空；归档清理只发生在规定条件后 |
| JV29 | 普通练习提交排队后管理员修改题目 | 已受理提交按原规格执行，新提交按新版本；不读取悬空用例引用 |

验收记录统一为 PASS、FAIL、BLOCKED/UNTESTED。单元替身验证不提升为真实Redis/MQ/沙箱证据；本地Docker联调不提升为生产验收。

本次1.1复审另做了设计级演算：手算榜单样例的120种AC事件顺序及各自一次重复重放结果一致；报名与判题简化状态模型分别检查720种动作串行化顺序，容量/回收互斥与有效结果只提交一次的不变量成立。这仅验证文档约定的有限模型，不执行现有业务代码，不覆盖数据库隔离、网络或容器行为；上述29条实现验收仍为UNTESTED。

测试命令从仓库根进入 codeoj-backend 后执行（本轮未执行）：

~~~sh
mvn -B -DskipTests package
mvn -B test
~~~

第一条只证明构建，不证明测试。J0须检查父POM是否实际运行JUnit5及各模块测试计数；出现Tests run:0不得记PASS。J2/J4需要新增真实依赖集成测试与明确的profile/环境隔离，命令在该任务完成时补入README；本文不把尚未存在的集成入口写成现成脚本。

前端现有项目位于 codeoj-frontend，先按其 package.json 核对可用命令；新增浏览器用例必须走真实测试API，不用全量mock冒充链路验收。

## 12. 压测、可观测与交付证据

报名分别报告：入口受理延迟、最终确认延迟、成功/售罄/重复/未知/失败比例、名额与账本收敛时间。判题分别报告：提交受理吞吐、排队时间、执行时间、完成吞吐、重试与资源峰值。榜单报告投影延迟与重建一致性。

负载梯度先小样本再增加，固定代码题目/硬件/容器配额；至少包含报名单独、判题单独、报名和判题同时进行。报名压测不能在客户端随机用户不足时把大量重复拒绝算作有效处理能力。输出P50/P95/P99与样本量，未完成任务不从统计中静默删掉。

每批证据目录建议 outputs/validation/<runId>/：commit.txt、environment.json（脱敏）、commands.log、test-results/、fault-timeline.json、metrics.json、state-reconciliation.json、browser/、known-limitations.md。这是实施后拟生成的结构，本轮没有这些验收结果。

日志贯穿 traceId、contestId、requestId/submissionId、eventId、attempt、executionToken的安全摘要。指标至少包含队列积压、最老等待时长、租约过期、状态悬挂、重复消息、死信、预留差异、执行资源与榜单延迟。日志不输出代码、隐藏用例或密钥。

完成定义：J0—J6要求逐项有证据，关键不变量无未解释FAIL；环境缺失的项目明确BLOCKED/UNTESTED，不以文档、CI配置或绿色替身用例替代。

## 13. 学习与求职交付

每阶段由本人复述：业务问题、入口调用链、表与状态、故障窗口、方案比较、验证方法、限制。至少亲自修改一个并发场景、一个故障恢复场景、一个端到端业务功能，并能在源码中定位。

Java项目的主要解释主线是报名一致性、判题可靠执行与成绩投影。commerce Agent 单独证明模型/工具/诊断/评测，不在OJ中强行加入同质聊天模块。

简历仅在证据完成后写“实现/验证了什么”。方案中的Outbox、容器隔离、压测指标都不能提前写成已有成果。保留教程来源与DianPing复用说明，准确说明新增设计和个人贡献。

## 14. 源码定位与官方参考

源码定位命令（只读）：

~~~sh
rg --files codeoj-backend -g '*QuestionSubmitServiceImpl.java' -g '*JudgeServiceImpl.java' -g '*MyMessageConsumer.java' -g '*JavaNativeCodeSandbox.java' -g '*JavaLanguageJudgeStrategy.java' -g '*RemoteCodeSandbox.java' -g 'create_table.sql'
~~~

DianPing重点核对：src/main/java/com/dish/review/service/impl/VoucherOrderServiceImpl.java、service/VoucherOrderHandler.java、service/SeckillVoucherLuaExecutor.java、mq/SeckillOrderPublisher.java、mq/SeckillOrderReconciliationTask.java，以及对应Lua、SQL迁移和测试。复用前重新检查其当前版本；本文没有把该仓库测试记录作为OJ成绩。

- [RabbitMQ：发布确认与消费确认](https://www.rabbitmq.com/docs/confirms)：确认边界、重投和NACK语义；不能推导应用端exactly-once。
- [Java 8 Process API](https://docs.oracle.com/javase/8/docs/api/java/lang/Process.html)：进程流与等待接口；实现仍需处理阻塞和回收。
- [Docker资源限制](https://docs.docker.com/engine/containers/resource_constraints/)：资源配置依据；配置存在不等于隔离验收。
- [MySQL锁定读](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)：显式事务和锁定读的依据；按实际MySQL版本验证锁范围与索引，不跨网络持锁。

实施前按实际依赖版本核对API，不照抄最新网页示例跨版本升级。该文档是开发与验收契约，不是安全认证、线上容量或生产可用性的证明。
