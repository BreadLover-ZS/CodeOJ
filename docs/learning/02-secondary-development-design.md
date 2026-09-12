# CodeOJ 可靠判题二次开发设计

> 文档类型：目标设计，不是完成证明。
>
> 主线：把“提交后发一条 MQ 消息”升级为可恢复、可观测、可测试的判题任务闭环。
>
> 时间约束：约 70 小时；按 Gate 前进，不按日期伪造完成度。

## 1. 目标与非目标

### 1.1 本轮目标

1. AC、WA、CE、RE、TLE 与系统失败的契约清晰；
2. 数据库保存成功的提交最终能够发布或被扫描恢复；
3. 重复消息不会造成同一任务并发执行；
4. 暂时性故障有界重试，永久故障进入可查询、可重放的失败通道；
5. RUNNING 任务在消费者宕机后能够恢复；
6. 日志、指标和测试可以证明上述语义；
7. 修复当前进程执行器的明显超时、日志泄露和进程回收问题。

### 1.2 本轮非目标

- 不承诺完整多语言支持；先把 Java 闭环做实。
- 不承诺生产级恶意代码隔离；若没有提交级容器和资源限制，只称进程执行器。
- 不承诺 exactly-once；目标是 at-least-once 投递下的业务幂等和最终可恢复。
- 不为“微服务数量”继续拆服务；优先减少不一致窗口。

## 2. 先冻结业务语义

### 2.1 提交任务状态

建议把任务状态与题目 verdict 分开：

```text
WAITING -> RUNNING -> FINISHED
   |          |
   |          `-> RETRY_WAIT -> WAITING
   |
   `-------------------------> DEAD
```

- `FINISHED`：判题流程正常结束，包括 AC、WA、CE、RE、TLE、MLE；
- `RETRY_WAIT`：暂时性基础设施异常，等待下一次尝试；
- `DEAD`：超过重试上限或遇到不可自动恢复的系统错误；
- verdict 单独存入 `judgeInfo` 或结构化列，不用 `FAILED` 同时表达用户代码错误与系统错误。

如果为了控制改动量保留现有 0/1/2/3 枚举，也必须把迁移规则和 `FAILED` 的唯一含义写入测试，并增加重试元数据。

### 2.2 沙箱响应契约

建议先重构共享模型：

```text
ExecuteCodeResponse
  outcome: SUCCESS | COMPILE_ERROR | RUNTIME_ERROR | TIMEOUT | SYSTEM_ERROR
  outputList: List<String>
  timeMs: Long?
  memoryKb: Long?
  errorDetail: String?
```

映射规则：

| 沙箱 outcome | 提交终态 | verdict | 是否自动重试 |
|---|---|---|---|
| SUCCESS | FINISHED | 再比较得 AC/WA/TLE/MLE | 否 |
| COMPILE_ERROR | FINISHED | CE | 否 |
| RUNTIME_ERROR | FINISHED | RE | 否 |
| TIMEOUT | FINISHED | TLE | 否 |
| SYSTEM_ERROR | RETRY_WAIT/DEAD | System Error | 按分类 |

不要用 `outputList.size() != inputList.size()` 把所有执行失败降级为 WA，也不要让 null `judgeInfo` 进入策略。

## 3. 数据模型

### 3.1 `question_submit` 最小扩展

建议字段：

| 字段 | 用途 |
|---|---|
| `status` | 任务状态 |
| `attemptCount` | 已开始执行次数 |
| `nextRetryTime` | 下次可重试时间 |
| `leaseExpireTime` | RUNNING 租约截止时间 |
| `workerId` | 当前执行者，便于审计 |
| `failureType` | USER_CODE / TRANSIENT_SYSTEM / PERMANENT_SYSTEM |
| `lastError` | 脱敏且截断后的错误摘要 |
| `version` | 可选，乐观锁/防旧结果覆盖 |

字段名最终应服从现有 MyBatis-Plus 命名和数据库规范。错误详情要设长度上限，不能存入隐藏用例或完整系统堆栈。

### 3.2 Outbox 表

建议同库新增：

```text
judge_outbox
  id
  aggregateId        submissionId
  eventType          JUDGE_REQUESTED
  payload            最小消息体
  status             NEW / PUBLISHING / SENT / RETRY / DEAD
  retryCount
  nextRetryTime
  lastError
  createdTime
  updateTime
```

提交记录和 Outbox 事件在**同一个本地数据库事务**中写入。这样只要提交对用户可见，就一定存在可被扫描的发布意图。

## 4. 可靠发布设计

### 4.1 提交事务

```text
BEGIN
  INSERT question_submit(status=WAITING)
  INSERT judge_outbox(status=NEW, aggregateId=submissionId)
  UPDATE question.submitNum
COMMIT
```

是否把统计字段放在同一事务要明确取舍：如果 `submitNum` 只是可重算统计，可以异步更新；如果写在主事务，就减少“记录存在但统计丢失”的不一致。

### 4.2 发布器

独立发布任务按 `nextRetryTime` 扫描 NEW/RETRY 事件：

1. CAS 抢占 Outbox 行，防多实例重复扫描；
2. 使用唯一 `eventId` 作为 `messageId`；
3. 启用 correlated publisher confirm；
4. 启用 mandatory returns，区分“Broker NACK”和“不可路由”；
5. confirm ACK 后标记 SENT；
6. 超时、NACK、Return 均记录原因并有界退避；
7. 超过阈值进入 DEAD 并告警。

边界：确认回调与数据库更新之间仍可能崩溃，所以发布可能重复。消费者必须按 submission ID 幂等，不能依赖“只发布一次”。

### 4.3 MQ 拓扑

用 Spring AMQP 声明式 Bean 统一：

```text
code_exchange
  -> code_queue
  -> code_retry_exchange / retry queue（可选 TTL 分级）
  -> code_dead_exchange
  -> code_dead_queue
```

删除或停用 `InitRabbitMqBean` 和独立 main 等重复初始化路径。exchange、queue、binding 的 durable、routing key、DLX 参数在代码中只保留一份事实来源。

## 5. 幂等消费与状态迁移

### 5.1 消息入口

消费者先做纯输入校验：

1. 校验消息非空、格式和 ID；
2. 读取消息头中的 `messageId`、重试次数；
3. 毒消息直接记录并路由 DLQ，不能在 `try` 外抛出；
4. 所有路径最终必须显式 ACK/NACK，且行为可测试。

### 5.2 取得执行权

```sql
update question_submit
set status = 'RUNNING',
    attemptCount = attemptCount + 1,
    workerId = ?,
    leaseExpireTime = ?
where id = ?
  and status in ('WAITING', 'RETRY_WAIT')
  and (nextRetryTime is null or nextRetryTime <= now());
```

处理受影响行数：

- `1`：获得执行权；
- `0` 且已 FINISHED/DEAD：重复消息，ACK；
- `0` 且 RUNNING 租约未过期：另一个 worker 正在执行，ACK 或延迟重投，选择一种并写测试；
- `0` 且状态异常：记录审计并进入明确分支。

### 5.3 防旧结果覆盖

沙箱调用是长耗时外部操作，不能持有数据库事务。写回时用 `id + status + workerId/version` 条件更新：

```sql
update question_submit
set status = 'FINISHED', judgeInfo = ?
where id = ? and status = 'RUNNING' and workerId = ?;
```

如果影响行数为 0，说明租约被恢复或任务已被其他流程处理，旧 worker 不能覆盖新结果。

## 6. 重试、DLQ 与恢复

### 6.1 失败分类

| 类型 | 例子 | 动作 |
|---|---|---|
| 用户代码失败 | CE、RE、TLE | 写 FINISHED，ACK |
| 暂时性依赖失败 | HTTP 超时、服务发现失败、数据库瞬断 | 写 RETRY_WAIT，延迟重试 |
| 永久系统失败 | 数据损坏、题目配置非法 | 写 DEAD，进入 DLQ/人工处理 |
| 毒消息 | 非数字 ID、缺字段 | DLQ，ACK 原消息 |

重试必须有最大次数、退避和总时限；不能无限 requeue 热循环。

### 6.2 RUNNING 恢复扫描

周期任务扫描 `status=RUNNING and leaseExpireTime < now()`：

- 未超过重试上限：转 RETRY_WAIT，清理 worker/lease，重新生成发布意图；
- 超过上限：转 DEAD 并告警；
- 每次迁移用条件更新，避免与刚完成的 worker 竞争。

### 6.3 人工重放

重放入口只接受 DEAD 任务 ID，记录操作人、原因和旧状态。重放不是直接改 WAITING 后“碰碰运气”，而是创建新的可追踪 attempt/outbox 事件。接口应要求管理员权限。

## 7. 沙箱最小改造

### 7.1 在本轮 70 小时内必须完成

1. stdout/stderr 并发排空；
2. 从进程启动开始计时，带时限等待不被同步读流阻塞；
3. 输出超过上限后终止进程，而不是仅截断读取；
4. 超时后终止进程树并等待退出；
5. 限制用例数、单输入和总输入大小；
6. `RemoteCodeSandbox` 设置连接和读取超时、检查 HTTP 状态；
7. 日志只记 requestId、语言、用例数、耗时和 outcome，不记源码/隐藏输入；
8. 统一 `timeMs`、`memoryKb` 单位，测不到就返回 null，不伪造 0；
9. 删除固定减 10 秒规则，用测试验证题目时间限制。

### 7.2 若做提交级容器隔离

每次提交使用短生命周期容器或受控 worker，至少配置：

- CPU 和内存硬限制；
- PID 限制；
- 禁止网络；
- 非 root 用户；
- 只读根文件系统和独立临时卷；
- seccomp/capability 最小化；
- 总墙钟时限和外部强制清理。

只有实际运行恶意样例并保存资源/逃逸验证记录后，才能把这些能力写入简历。仅把沙箱服务放入 Compose 不算提交级隔离。

## 8. 分阶段 Gate

### Gate A：基线可运行

通过条件：

- JDK 8 全量构建与测试通过；
- 基础设施和五个服务健康；
- Java AC/WA 可完成并落库；
- 保存环境版本、命令、日志和数据库结果。

### Gate B：结果契约正确

通过条件：

- AC、WA、CE、RE、TLE 均有自动化测试；
- 用户代码错误都落业务终态，不进入系统 FAILED；
- null、空输出、输出数量不一致和边界时限均被覆盖；
- 单位写入接口契约并由测试锁定。

### Gate C：幂等与恢复

通过条件：

- 并发投递同一 ID，只有一个 worker 获得执行权；
- 终态重复消息直接 ACK；
- RUNNING 宕机后可由租约扫描恢复；
- 旧 worker 不能覆盖恢复后的新结果。

### Gate D：可靠发布与失败通道

通过条件：

- 提交与 Outbox 同事务；
- Broker NACK、不可路由、确认超时均可复现并进入重试；
- 重复发布不造成重复业务执行；
- 超限失败进入 DLQ/DEAD，可查询、可授权重放。

### Gate E：安全与压测

通过条件：

- 死循环、刷屏、异常退出、大输入和进程树样例不会拖死服务；
- 日志不出现源码和隐藏用例；
- 压测报告分离提交吞吐、排队时延和真实判题吞吐；
- 能解释瓶颈、失败拐点和未覆盖范围。

## 9. 建议测试矩阵

| 层级 | 重点 |
|---|---|
| 单元测试 | outcome 映射、判题策略、状态迁移、退避计算 |
| Repository 测试 | CAS 受影响行数、Outbox 抢占、租约恢复、防旧写 |
| MQ 集成测试 | confirm/return、重复投递、NACK、DLQ、重放 |
| 沙箱集成测试 | AC/CE/RE/TLE、刷屏、大输入、进程树、清理 |
| E2E | 登录、建题、提交、终态查询、故障恢复 |
| 压测 | API 吞吐、队列积压、排队时延、执行时延、错误率 |

优先使用真实链路和真实 RabbitMQ/MySQL；Mock 适合锁定分支语义，但不能证明中间件行为。

## 10. 实施顺序

```text
契约与测试
  -> CAS 状态迁移
  -> RUNNING 恢复
  -> Outbox 发布
  -> Confirm/Return + Retry/DLQ
  -> 沙箱最小修复
  -> E2E / 故障注入
  -> 压测与简历证据
```

依赖关系很重要：如果结果契约尚未正确，先堆 MQ 重试只会更可靠地重复错误；如果消费不幂等，先做 Outbox 会放大重复执行风险。
