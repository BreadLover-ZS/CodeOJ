# CodeOJ 关键源码与判题主链

本文不做全仓库导读，只保留 Java 后端面试最可能追问的异步判题链。每个类只看指定方法，理解输入、状态变化、外部调用和失败后果即可。

## 1. 一张图讲清主链

```text
POST /question/question_submit/do
  |
  v
Question Service
  校验用户/题目/语言 -> INSERT question_submit(WAITING)
  -> RabbitTemplate.convertAndSend(submissionId)
  |
  v
RabbitMQ: code_exchange -> code_queue
  |
  v
Judge Service
  消费 submissionId -> 查提交与题目 -> WAITING 改为 RUNNING
  -> HTTP 调用 Code Sandbox -> 判题策略比对输出
  -> 写 SUCCEED + judgeInfo
  -> 成功后 consumer ACK
  |
  v
Code Sandbox
  鉴权 -> 临时目录 -> javac -> 逐用例 java -> 返回执行响应
```

RabbitMQ 只传 `submissionId`，业务事实仍在数据库。好处是消息小、消费端可按 ID 查询状态；代价是判题读取的是消费时的题目数据，如果题目在提交后被修改，可能破坏提交时语义。后续可用题目版本或用例快照解决。

## 2. 只看这 10 个关键类

| 顺序 | 只看哪里 | 你要讲出的结论 | 面试追问 |
|---:|---|---|---|
| 1 | [`QuestionController#doQuestionSubmit`][question-controller] | 校验请求、取得登录用户、进入提交服务 | 为什么 Controller 不直接判题？ |
| 2 | [`QuestionSubmitServiceImpl#doQuestionSubmit`][submit-service] | 校验语言和题目，先保存 `WAITING`，再发布 MQ | DB 成功、MQ 失败怎么办？ |
| 3 | [`MyMessageProducer#sendMessage`][producer] | 当前只是一次 `convertAndSend` | Confirm 和 Return 在哪里？ |
| 4 | [`MyMessageConsumer#receiveMessage`][consumer] | 手动 ACK；异常时尝试写 FAILED，再 `nack(requeue=false)` | 会不会丢、重、无限重试？ |
| 5 | [`JudgeServiceImpl#doJudge`][judge-service] | 查数据、改 RUNNING、调用沙箱、策略判题、写终态 | 两个消费者如何竞争执行权？ |
| 6 | [`RemoteCodeSandbox#executeCode`][remote-sandbox] | HTTP 调沙箱并传共享密钥 | HTTP 超时和空响应如何处理？ |
| 7 | [`ExecuteCodeController#executeCode`][sandbox-controller] | 校验共享密钥后调用沙箱服务 | 鉴权等于安全隔离吗？ |
| 8 | [`JavaNativeCodeSandbox#executeCode`][native-sandbox] | 写源码、编译、逐用例运行、采集输出、清理 | 如何限制 CPU、内存、进程和网络？ |
| 9 | [`JudgeManager#doJudge`][judge-manager] | 按语言选择策略 | 目前是否真的支持多语言？ |
| 10 | [`JavaLanguageJudgeStrategy#doJudge`][java-strategy] | 比对输出，再判断内存和时间 | CE/RE/TLE 从哪里进入？ |

辅助文件只需扫一眼：`QuestionSubmitStatusEnum`、`QuestionSubmitLanguageEnum`、`JudgeInfoMessageEnum`、`ExecuteCodeResponse`。不用逐字段背实体和 DTO。

## 3. 逐段讲法

### 3.1 接口到入库

`QuestionController` 只负责接口边界；核心写入在 `QuestionSubmitServiceImpl`：

```text
校验 language -> 查询题目 -> 组装 QuestionSubmit
-> status=WAITING, judgeInfo={}
-> save
-> sendMessage(submissionId)
```

最重要的缺口是 `save` 与 `sendMessage` 不在同一个原子边界。若数据库成功、进程在发送前崩溃，提交会永久停在 `WAITING`。目标方案是同一数据库事务写提交记录与 Outbox 事件，再由发布器重试投递；Publisher Confirm/Return 只能提供 Broker 接收和路由证据，不能让数据库与 RabbitMQ 形成一个本地事务。

### 3.2 消费与 ACK

`MyMessageConsumer` 在 `doJudge` 正常返回后执行 `basicAck`。出现异常时：

1. 查询提交当前状态；
2. 非终态则尽力改为 `FAILED`；
3. 执行 `basicNack(requeue=false)`。

当前队列没有配置 DLX，因此 `requeue=false` 的失败消息没有项目内可见的重试、DLQ 或人工重放闭环。即使记录已经是终态，代码也走 `basicNack(requeue=false)`，效果是丢弃该次投递，而不是把重复消息作为幂等成功明确 ACK。

面试时要区分：

- Publisher Confirm：生产者确认 Broker 是否接收发布；
- Return：发现消息到达交换机但无法路由；
- Consumer ACK：消费者告诉 Broker 该投递可以删除；
- 业务状态与幂等：由数据库保证，前三者不能替代。

### 3.3 判题抢占和状态回写

`JudgeServiceImpl` 先读取 `WAITING`，再用普通 `updateById` 写 `RUNNING`。两个消费者可能同时读到 `WAITING`，随后都更新成功并执行用户代码。这是“先查后改”竞态。

目标改造应使用条件更新取得执行权：

```sql
update question_submit
set status = RUNNING
where id = ? and status = WAITING;
```

只有影响行数为 1 的 worker 执行。这里不必先上分布式锁：数据库状态本来就是任务事实来源，CAS 更直接，也避免锁租约与数据库提交不一致。

但 CAS 只解决“谁获得执行权”。worker 写成 `RUNNING` 后宕机，任务仍会卡住，因此还需要执行租约、超时扫描和带 worker/version 条件的结果写回，防止旧 worker 覆盖新结果。

### 3.4 三类状态不能混在一起

| 层次 | 当前字段/对象 | 含义 |
|---|---|---|
| 任务生命周期 | `question_submit.status` | WAITING / RUNNING / SUCCEED / FAILED |
| 沙箱调用状态 | `ExecuteCodeResponse.status` | 沙箱本次调用成功或失败 |
| 判题结论 | `JudgeInfo.message` | Accepted / Wrong Answer / Compile Error 等 |

`SUCCEED` 表示判题流程完成，不等于答案 Accepted；WA、CE、RE、TLE 都应该是“任务完成后的业务 verdict”。只有基础设施或系统处理失败才应该进入任务失败/重试语义。

### 3.5 当前结果契约的关键缺陷

`JavaNativeCodeSandbox#fail` 在编译错误、运行错误和运行超时时返回 `status=2`，但不构造 `judgeInfo`。`JudgeServiceImpl` 没有先检查沙箱状态，直接把 `executeCodeResponse.getJudgeInfo()` 交给策略；`JavaLanguageJudgeStrategy` 又立即读取其内存和时间，因此可能空指针，最终由消费者把任务改成 `FAILED`。

所以当前不能声称 CE、RE、TLE 已被完整识别。正确方向是冻结统一响应契约：

- 用户代码问题：任务完成，保存明确 verdict；
- 临时基础设施故障：进入有界重试；
- 永久系统错误：记录原因并进入可观察终态。

### 3.6 沙箱只看四件事

`JavaNativeCodeSandbox` 已有共享密钥、代码/输出长度限制、编译/运行超时和临时目录清理，但不能称为生产级安全沙箱：

1. 用户代码直接作为宿主机子进程运行；
2. 没有提交级 CPU、内存、PID、网络和文件系统隔离；
3. `memory` 固定返回 `0L`，MLE 结论没有测量依据；
4. 代码先同步读 stdout，再调用带超时的 `waitFor`，持续输出程序可能让读流阶段阻塞，绕过预期超时。

另外，提交枚举允许 `java/cpp/go`，但执行端始终走 `javac/java`。目前只能说 Java 主链，不能声称三种语言都可用。

## 4. 六个必须掌握的故障点

| 故障点 | 当前后果 | 二开方向 | 最小验证 |
|---|---|---|---|
| DB 成功、MQ 发送前崩溃 | 永久 WAITING | Transactional Outbox | 杀发布进程后可恢复发送 |
| MQ 无法路由或 Broker NACK | 当前无可靠闭环 | Confirm + Return + 发布重试 | 错 routing key、关闭 Broker |
| 同一 ID 重复投递 | 可能重复执行 | 条件更新/CAS + 终态幂等 | 并发投递同一 ID |
| RUNNING 后 worker 宕机 | 长期卡住 | 租约 + 恢复扫描 | RUNNING 后杀进程 |
| 沙箱返回失败响应 | 可能变系统 FAILED | 统一结果契约 | CE、RE、TLE 用例 |
| 恶意或持续输出代码 | 资源与超时边界不足 | 并发读流、进程树回收、容器限额 | 无限循环、刷输出、fork/联网样例 |

实施优先级：**结果契约 → CAS 幂等 → RUNNING 恢复 → Outbox/Confirm/DLQ → 沙箱安全 → 故障注入与压测。**

## 5. 面试时画到这里就够了

能说清以下三条，就不要继续扩散源码：

- 正常链：提交如何从 HTTP 走到最终 verdict；
- 异常链：消息可能在哪里丢、重或卡住；
- 个人主线：为什么按结果契约、幂等、恢复、可靠发布、安全的顺序二开。

需要更细的旧版设计、表结构和测试矩阵时，再查 `archive/`。

## 6. 语义参考

- [RabbitMQ Consumer Acknowledgements and Publisher Confirms](https://www.rabbitmq.com/docs/confirms)
- [Spring AMQP Publisher Confirms and Returns](https://docs.spring.io/spring-amqp/reference/amqp/connections.html#publisher-confirms-returns)
- [Java 8 Process](https://docs.oracle.com/javase/8/docs/api/java/lang/Process.html)

官方资料用于解释中间件/API 语义，不是本项目已经实现可靠性的证据。

[question-controller]: ../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/controller/QuestionController.java
[submit-service]: ../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/service/impl/QuestionSubmitServiceImpl.java
[producer]: ../../codeoj-backend/codeoj-backend-question-service/src/main/java/com/codeoj/codeojbackendquestionservice/rabbitmq/MyMessageProducer.java
[consumer]: ../../codeoj-backend/codeoj-backend-judge-service/src/main/java/com/codeoj/codeojbackendjudgeservice/rabbitmq/MyMessageConsumer.java
[judge-service]: ../../codeoj-backend/codeoj-backend-judge-service/src/main/java/com/codeoj/codeojbackendjudgeservice/judge/JudgeServiceImpl.java
[remote-sandbox]: ../../codeoj-backend/codeoj-backend-judge-service/src/main/java/com/codeoj/codeojbackendjudgeservice/judge/codesandbox/impl/RemoteCodeSandbox.java
[sandbox-controller]: ../../codeoj-backend/codeoj-backend-codesandbox/src/main/java/com/codeoj/codeojbackendcodesandbox/controller/ExecuteCodeController.java
[native-sandbox]: ../../codeoj-backend/codeoj-backend-codesandbox/src/main/java/com/codeoj/codeojbackendcodesandbox/service/impl/JavaNativeCodeSandbox.java
[judge-manager]: ../../codeoj-backend/codeoj-backend-judge-service/src/main/java/com/codeoj/codeojbackendjudgeservice/judge/JudgeManager.java
[java-strategy]: ../../codeoj-backend/codeoj-backend-judge-service/src/main/java/com/codeoj/codeojbackendjudgeservice/judge/strategy/JavaLanguageJudgeStrategy.java
