# CodeOJ 架构与判题调用链学习手册

> 文档状态：静态源码审计 v0.1
>
> 审计基线：`master` / `33a56d9`
>
> 证据边界：本文只证明当前源码“写了什么”，不证明服务已经成功启动、完整联调或达到生产可用。运行、故障注入和压测证据将在后续阶段补充。

## 0. 先记住这张项目地图

CodeOJ 是一个前后端分离的微服务 OJ。核心业务不是题目 CRUD，而是把一次不可信的用户代码提交，可靠地转化为可查询的判题结果。

```text
Vue 前端
   |
   | HTTP /api/**
   v
Gateway :8101
   |-- /api/user/**     -> User Service :8102
   |-- /api/question/** -> Question Service :8103
   `-- /api/judge/**    -> Judge Service :8104
                                |
Question Service               | HTTP + auth header
   |                            v
   | RabbitMQ code_queue   Code Sandbox :8090
   v                            |
Judge Service -----------------'
   |
   | OpenFeign 内部调用
   v
Question Service -> MySQL

Redis：Spring Session 登录态
Nacos：服务注册与发现
RabbitMQ：提交任务异步化
MySQL：题目、提交记录、判题结果
```

模块职责：

| 模块 | 当前职责 | 核心入口 |
|---|---|---|
| `codeoj-backend-gateway` | 路由、CORS、外部访问内部接口的第一层拦截 | `GlobalAuthFilter`、`application.yml` |
| `codeoj-backend-user-service` | 注册、登录、用户与会话 | `UserController`、`UserServiceImpl` |
| `codeoj-backend-question-service` | 题目 CRUD、提交记录、发送判题消息、保存结果 | `QuestionController`、`QuestionSubmitServiceImpl` |
| `codeoj-backend-judge-service` | 消费任务、编排沙箱、匹配答案、写回结果 | `MyMessageConsumer`、`JudgeServiceImpl` |
| `codeoj-backend-codesandbox` | 编译和执行 Java 用户代码 | `ExecuteCodeController`、`JavaNativeCodeSandbox` |
| `codeoj-backend-service-client` | 声明 OpenFeign 内部接口和内部鉴权头 | `QuestionFeignClient`、`FeignInnerAuthConfig` |
| `codeoj-backend-model` | 跨服务 DTO、实体、枚举 | `QuestionSubmit`、`ExecuteCodeRequest/Response` |
| `codeoj-backend-common` | 响应、异常、注解和工具 | `BaseResponse`、`GlobalExceptionHandler` |

## 1. 一次提交的完整调用链

### 1.1 请求进入题目服务

前端调用：

```http
POST /api/question/question_submit/do
Content-Type: application/json

{
  "questionId": 题目ID,
  "language": "java",
  "code": "用户代码"
}
```

源码入口：

- `QuestionController#doQuestionSubmit` 检查请求和题目 ID。
- `UserFeignClient#getLoginUser` 从会话取得当前用户。
- `QuestionSubmitService#doQuestionSubmit` 进入提交业务。

注意：Gateway 当前主要做路由和 `/inner/**` 拦截，登录校验仍落在业务服务，不应把 Gateway 描述成完整的统一认证中心。

### 1.2 保存提交记录

`QuestionSubmitServiceImpl#doQuestionSubmit` 当前依次执行：

1. 用 `QuestionSubmitLanguageEnum` 校验语言。
2. 查询题目是否存在。
3. 构造 `question_submit` 记录：用户、题目、源码、语言。
4. 初始化 `status = WAITING(0)`，`judgeInfo = {}`。
5. 插入 MySQL，取得 `questionSubmitId`。
6. 向 `code_exchange` 发送消息，routing key 为 `my_routingKey`，消息体只有提交 ID。
7. 单独累加题目提交数；统计失败只记录 warning，不影响主流程。

为什么 MQ 里只放提交 ID：

- 消息更小，不复制大段源码和用例。
- 判题服务以数据库当前记录为准。
- 代价是判题服务强依赖题目服务和数据库可用性，并且题目/用例后续被修改时存在“提交时快照”问题。

### 1.3 RabbitMQ 路由

当前拓扑：

```text
direct exchange: code_exchange
        |
        | routing key: my_routingKey
        v
durable queue: code_queue
```

`InitRabbitMqBean` 在判题服务启动时声明 exchange、queue 和 binding。生产者使用 `RabbitTemplate#convertAndSend`，当前没有看到 publisher confirm、return callback 或 Outbox。

### 1.4 消费与手动 ACK

`MyMessageConsumer#receiveMessage` 使用：

```java
@RabbitListener(queues = {"code_queue"}, ackMode = "MANUAL")
```

当前时间线：

```text
收到 submissionId
  -> judgeService.doJudge(id)
      -> 成功：basicAck
      -> 异常：检查数据库是否已有终态
          -> 非终态：尝试标记 FAILED
          -> basicNack(requeue=false)
```

`MANUAL` 只表示确认动作由业务代码负责，并不自动获得“恰好一次”。当前能够合理描述为：项目尝试用手动 ACK 控制消费完成点，但幂等、重试与死信闭环尚未建立。

### 1.5 判题编排

`JudgeServiceImpl#doJudge` 的源码流程：

1. 通过 `QuestionFeignClient` 读取提交记录。
2. 读取对应题目与测试用例。
3. 只允许 `WAITING` 状态继续处理。
4. 把提交状态更新为 `RUNNING(1)`。
5. 根据 `codesandbox.type` 从 `CodeSandboxFactory` 选择沙箱。
6. 通过 `CodeSandboxProxy` 包装调用。
7. 把代码、语言、输入用例组成 `ExecuteCodeRequest`。
8. 调用远程沙箱。
9. 把输出、资源数据和预期用例交给 `JudgeManager`。
10. 写回 `SUCCEED(2)` 与 `judgeInfo`。
11. 如果 verdict 是 Accepted，累加题目通过数。

这里有两个容易讲错的概念：

- `QuestionSubmitStatusEnum.SUCCEED` 表示判题流程完成，不等于答案通过；WA/TLE 等也可能处于该状态。
- 真正的题目结果位于 `judgeInfo.message`，例如 Accepted、Wrong Answer、Time Limit Exceeded。

### 1.6 沙箱调用

默认配置选择 `remote`：

```text
Judge Service
  -> RemoteCodeSandbox
  -> POST http://codeoj-codesandbox:8090/executeCode
  -> auth: CODESANDBOX_SECRET
  -> ExecuteCodeController
  -> JavaNativeCodeSandbox
```

当前 Java 沙箱流程：

1. 检查共享密钥、代码非空和代码长度。
2. 创建临时目录并写入 `.java` 文件。
3. 使用 `javac` 编译。
4. 为每个用例单独启动 `java` 子进程，通过 stdin 输入。
5. 收集 stdout，计算最大执行时间。
6. 返回 `ExecuteCodeResponse`。
7. `finally` 清理临时目录。

当前实现的“远程”表示 HTTP 服务边界，不等于安全隔离。Compose 只是把整个沙箱服务放进一个长期运行容器，用户代码仍与沙箱服务进程共享该容器的权限、网络和文件系统。

### 1.7 判题策略

`JudgeManager` 对 Java 使用 `JavaLanguageJudgeStrategy`，其他语言走 `DefaultJudgeStrategy`。策略按以下顺序判断：

1. 输出数量是否与输入数量一致。
2. 每个实际输出是否与期望输出字符串完全相等。
3. 内存是否超过限制。
4. 时间是否超过限制。

当前 Java 策略包含固定减去 10 秒的逻辑，但本项目沙箱记录的是进程实际毫秒耗时，因此这条规则缺少当前实现依据，后续应通过测试重构，而不是写进简历当作“语言性能优化”。

## 2. 状态机：当前实现与目标实现

### 2.1 当前状态

```text
WAITING(0) -> RUNNING(1) -> SUCCEED(2)
                  |
                  `------> FAILED(3)  （基础设施或消费异常）
```

当前表只有 `status`，没有任务版本、重试次数、租约截止时间、失败类型和下一次重试时间。

### 2.2 当前并发缺口

当前“判定 WAITING”和“更新 RUNNING”是两个远程调用。两个消费者可能同时读到 WAITING，并分别成功写入 RUNNING，然后重复执行用户代码。

需要验证的目标语义：

```sql
update question_submit
set status = RUNNING
where id = ? and status = WAITING;
```

只有受影响行数为 1 的消费者获得任务执行权。最终设计还必须解决消费者在获得执行权后崩溃、记录长期停在 RUNNING 的恢复问题。

## 3. 失败时间线审计

| 失败点 | 当前结果 | 风险 | 后续验证方向 |
|---|---|---|---|
| MySQL 插入前失败 | 无记录、无消息 | 可重试请求 | 接口错误语义 |
| MySQL 插入成功，MQ 发送失败 | 记录长期 WAITING | 任务丢失 | Transactional Outbox 或补偿扫描 |
| MQ 已路由，响应前接口失败 | 用户可能重提 | 重复提交 | 请求幂等键/业务去重策略 |
| 两个消费者收到同一 ID | 都可能通过先查后改 | 重复执行 | 条件状态迁移 |
| 更新 RUNNING 后进程崩溃 | 消息重投时发现非 WAITING | 任务被判失败或卡住 | 租约、重试次数、恢复扫描 |
| 沙箱临时超时 | 返回失败响应 | 目前可能被流读取阻塞 | 并发排空 stdout/stderr，先受控等待 |
| 判题结果已写回，ACK 前崩溃 | 重投后发现终态 | 当前最终丢弃消息 | 明确幂等 ACK 分支 |
| 异常后 `nack(requeue=false)` | 消息直接丢弃 | 无 DLQ 审计/重放 | DLX、失败分类、人工重放 |

## 4. 沙箱安全边界

### 4.1 当前已有防护

- 沙箱 HTTP 接口有共享密钥。
- 代码大小限制为 256 KiB。
- 单次输出截断为 64 KiB。
- 配置了编译和单用例运行超时。
- 每次请求创建独立临时目录并在结束后清理。
- 业务服务不直接暴露沙箱端口。

### 4.2 当前缺失防护

- 没有每次提交级容器隔离。
- 没有 CPU、内存、进程数限制。
- 没有禁网。
- 没有只读根文件系统和最小文件权限。
- Dockerfile 没有声明非 root 用户。
- 没有限制测试用例数量与输入总大小。
- `CodeSandboxProxy` 会记录完整请求，可能把用户源码和隐藏用例写入日志。
- stdout/stderr 没有并发排空，`readOutput()` 发生在 `waitFor(timeout)` 之前，超时可能不能按预期生效。
- `destroyForcibly()` 后没有继续等待子进程退出，也没有处理用户程序创建的后代进程。
- 内存统计固定为 `0L`，当前 MLE 判定没有真实数据基础。

因此当前只能称为“进程执行器”或“演示级进程沙箱”，不能称为生产安全沙箱。

## 5. 14 天二开候选主线

以下是静态审计后的优先级，不等同于已完成计划。

### P0：先形成可靠判题闭环

1. 补充提交/任务状态字段与合法迁移规则。
2. 用条件更新抢占任务，证明重复投递只执行一次。
3. 解决“数据库成功、MQ 失败”的任务丢失窗口。
4. 区分可重试基础设施异常、用户代码错误和永久失败。
5. 增加 DLQ 或失败任务表、重放入口、超时 RUNNING 恢复机制。
6. 为以上时间线编写单元测试、集成测试和故障注入测试。

### P1：把执行器升级为可证明的安全沙箱

1. 修复进程输出读取与超时控制。
2. 每次提交使用短生命周期隔离环境。
3. 限制 CPU、内存、PID、网络、文件系统和总执行时间。
4. 不记录隐藏用例与完整用户源码。
5. 用恶意样例验证：死循环、内存膨胀、fork、刷屏、读文件、访问网络。

### P2：压测与可观测性

1. 指标至少包含：提交吞吐、队列积压、排队时延、判题时延、成功率、重试数、DLQ 数。
2. 固定机器配置、JVM 参数、数据集、并发阶梯和测试时长。
3. 分开报告“提交 API 吞吐”和“真实判题吞吐”，不能混成一个 QPS。
4. 保存原始报告和失败拐点，不只保存最高数字。

## 6. 建议的源码阅读顺序

每读完一组，应该能独立回答后面的面试问题。

1. `QuestionController#doQuestionSubmit`
2. `QuestionSubmitServiceImpl#doQuestionSubmit`
3. `MyMessageProducer`、`InitRabbitMqBean`、`MyMessageConsumer`
4. `JudgeServiceImpl#doJudge`
5. `QuestionFeignClient`、`QuestionInnerController`
6. `CodeSandboxFactory`、`RemoteCodeSandbox`、`ExecuteCodeController`
7. `JavaNativeCodeSandbox`
8. `JudgeManager`、`DefaultJudgeStrategy`、`JavaLanguageJudgeStrategy`
9. `QuestionSubmitStatusEnum`、`JudgeInfoMessageEnum`
10. `docker-compose.*.yml` 和各服务配置

第一轮必须能回答：

- 为什么 MQ 只传 submission ID？代价是什么？
- 手动 ACK 是否意味着消息不会重复？
- 消费者在哪个时刻取得任务执行权？当前为什么不可靠？
- WA、TLE、CE 与系统 FAILED 有什么区别？
- 如果 MQ 发送失败、消费者宕机、结果写回后 ACK 前宕机，各发生什么？
- 当前沙箱防住了什么，没防住什么？
- 为什么提交 API QPS 不等于判题吞吐量？

## 7. 当前证据清单

| 结论 | 证据等级 | 状态 |
|---|---|---|
| 后端为 8 模块 Maven 工程 | 静态源码/POM | 已确认 |
| 网关、用户、题目、判题通过 Nacos 组织 | 静态配置 | 已确认 |
| 提交通过 RabbitMQ 异步传递 | 静态源码 | 已确认 |
| 消费端使用手动 ACK | 静态源码 | 已确认 |
| 远程沙箱执行 Java 程序 | 静态源码 | 已确认 |
| Maven 完整构建成功 | 构建 | 未确认；本机 Java 26 下失败 |
| Compose 配置可用 | 配置解析 | 未确认；本机无 Docker |
| 注册到判题完整闭环 | E2E | 未确认 |
| 故障恢复符合设计 | 故障注入 | 未确认 |
| 压测指标 | 压测 | 未开始 |

## 8. 来源与公开发布边界

当前仓库：

- 没有 `LICENSE` 或 `NOTICE`。
- Git 历史从一次整体导入开始，不能证明原始创作过程。
- 多个判题类与公开的 YuOJ 衍生仓库高度相似。
- 编程导航官方项目介绍将 YuOJ 描述为其原创课程项目。

因此，在找到代码的真实获取来源及明确许可证/授权之前：

- 可以用于私人学习和本地分析。
- 不应声称现有基线代码由自己原创。
- 不应默认认为“公开可见”等于“允许复制和再发布”。
- 暂不建议继续公开推送完整基线；README 中也不能把 `CodeOJ Team` 当作来源说明。

后续应记录：原始下载 URL、获取日期、作者、许可证或授权文本、保留署名要求，以及你自己的变更清单。

## 9. 官方语义参考

- Spring AMQP：`MANUAL` 模式要求监听器显式 `basicAck/basicNack`，但不提供业务幂等保证。

  <https://docs.spring.io/spring-amqp/reference/amqp/containerAttributes.html>
- Spring AMQP：发布确认需要显式启用 correlated publisher confirms，并可结合 returns 识别不可路由消息。

  <https://docs.spring.io/spring-amqp/reference/amqp/template.html>
- Java 8 `Process`：`waitFor(timeout, unit)` 才是带时限的进程等待，stdout/stderr 是独立管道。

  <https://docs.oracle.com/javase/8/docs/api/java/lang/Process.html>

## 10. 更新记录

| 日期 | 版本 | 内容 |
|---|---|---|
| 2026-09-10 | v0.1 | 建立架构图、提交判题调用链、状态机、失败时间线、安全边界与证据清单 |
