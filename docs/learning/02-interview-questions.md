# CodeOJ 面试问题与回答

本文用于完成源码阅读后的面试复习。每题先自己回答，再看参考答案；回答顺序统一为：**当前实现 → 存在问题 → 二开方向 → 验证边界。**

源码学习和成果测试见[源码阅读路径与成果测试](01-source-reading-paths-and-tests.md)。

## 一、项目与整体架构

### 1. 请用 30 秒介绍 CodeOJ

**参考回答：** CodeOJ 是一个 Spring Cloud 微服务在线判题项目。用户提交代码后，Question Service 先保存提交记录，再通过 RabbitMQ 异步通知 Judge Service；Judge Service 远程调用 Java 代码沙箱执行测试用例，使用判题策略形成结果并回写。我正在基于现有项目做可靠性与安全方向的二次开发，目前完成的是源码审计和方案设计。

**源码依据：** `QuestionSubmitServiceImpl#doQuestionSubmit`、`MyMessageConsumer#receiveMessage`、`JudgeServiceImpl#doJudge`、`JavaNativeCodeSandbox#executeCode`。

**继续追问：** 为什么需要微服务和 MQ？哪些代码是你的？

**回答边界：** 不能说项目从零原创，也不能把待实现方案说成已完成。

### 2. 为什么不在提交接口里同步判题？

**参考回答：** 编译和运行用户代码耗时长且波动大，同步判题会长期占用 Web 线程。RabbitMQ 把快速接收提交和慢速判题解耦，并用队列吸收短时流量。但异步化不自动等于可靠，仍需处理消息丢失、重复投递和任务恢复。

**源码依据：** 提交服务入库后发送 MQ，Judge Service 的消费者异步调用 `doJudge`。

**继续追问：** 队列积压怎么办？为什么不直接使用线程池？

### 3. RabbitMQ 为什么只传 submissionId？

**参考回答：** 数据库是业务事实来源，消息只传 ID，消息体更小，消费端也能按数据库状态做幂等判断。代价是消费时重新读取题目和用例；如果题目被修改，旧提交可能按新用例执行，因此需要题目版本或不可变快照。

**源码依据：** `MyMessageProducer#sendMessage` 发送字符串 ID；`JudgeServiceImpl#doJudge` 再查询提交和题目。

**继续追问：** 为什么不把源码和测试用例全放进消息？

## 二、RabbitMQ 与任务可靠性

### 4. 数据库保存成功、MQ 发送失败怎么办？

**参考回答：** 当前代码先保存提交，再调用 `convertAndSend`，两者不原子；发送前崩溃会留下永久 WAITING。二开方向是同一数据库事务写 submission 和 Outbox，再由发布器重试发送，并记录 Confirm/Return 结果。

**源码依据：** `QuestionSubmitServiceImpl#doQuestionSubmit` 中 `save` 位于 `sendMessage` 之前。

**继续追问：** 为什么数据库事务不能直接包住 RabbitMQ？Outbox 会不会重复发送？

**回答边界：** 当前只有设计，不能说 Outbox 已落地。

### 5. Publisher Confirm、Return 和 Consumer ACK 有什么区别？

**参考回答：** Confirm 说明 Broker 是否接收发布；Return 发现消息到达交换机但无法路由；Consumer ACK 表示消费者处理完本次投递、Broker 可以删除消息。它们处在不同链路，都不能替代数据库业务状态和业务幂等。

**源码依据：** 当前生产者只有 `convertAndSend`；消费者显式调用 `basicAck/basicNack`。

**继续追问：** 收到 ACK 是否代表数据库结果一定已写成功？收到 Confirm 是否代表消费者已处理？

### 6. 已经手动 ACK，为什么消息仍可能丢？

**参考回答：** 手动 ACK 只控制消费确认时机。当前异常分支执行 `basicNack(requeue=false)`，队列又没有项目内可见的 DLX 配置，失败消息缺少重试、DLQ 和人工重放闭环；生产端也没有完整发布确认和补偿。

**源码依据：** `MyMessageConsumer#receiveMessage`、`InitRabbitMqBean#init`。

**继续追问：** 哪些错误应该重试，哪些应该直接进入失败通道？

### 7. RabbitMQ 重复投递时如何保证不重复判题？

**参考回答：** 结果写回成功但 ACK 前宕机，会造成消息重投。当前代码先查询 WAITING，再普通更新 RUNNING，两个消费者可能同时通过检查。应使用 `update ... where status=WAITING` 条件更新抢占执行权，终态重复消息按幂等成功处理。

**源码依据：** `JudgeServiceImpl#doJudge` 的状态检查与 `updateById` 分离。

**继续追问：** 条件更新影响行数为 0 时应该 ACK、重试还是报错？

### 8. 为什么这里优先用数据库 CAS，而不是分布式锁？

**参考回答：** 任务状态本来就在数据库中，条件更新能同时完成状态校验和执行权竞争，影响行数就是结果。分布式锁会额外引入锁过期、续租以及锁与数据库事务不一致问题。

**源码依据：** 当前竞争点就是 `question_submit.status` 的 WAITING → RUNNING。

**继续追问：** CAS 能否解决 worker 已经拿到任务后宕机？

### 9. 已有 CAS，为什么还需要 RUNNING 租约？

**参考回答：** CAS 只保证一个 worker 从 WAITING 进入 RUNNING。worker 随后宕机，记录仍会永久 RUNNING。租约让恢复任务识别过期执行并重新调度，结果写回还需校验 workerId/version，防止旧 worker 覆盖新结果。

**源码依据：** 当前状态枚举和表中没有执行租约，代码也没有 RUNNING 恢复扫描。

**继续追问：** 旧 worker 恢复并写结果时如何阻止它？

## 三、判题结果与沙箱

### 10. SUCCEED 是否代表用户代码 Accepted？

**参考回答：** 不是。`question_submit.status` 表示任务生命周期，SUCCEED 应表示判题流程正常完成；Accepted、Wrong Answer、Compile Error 等属于 `JudgeInfo.message`。`ExecuteCodeResponse.status` 又表示沙箱调用结果，三层状态不能混用。

**源码依据：** `QuestionSubmitStatusEnum`、`ExecuteCodeResponse`、`JudgeInfoMessageEnum`。

**继续追问：** WA、CE 和系统异常分别应该落在哪一层？

### 11. 当前 CE、RE、TLE 为什么可能变成任务 FAILED？

**参考回答：** 沙箱失败时返回 `status=2`，但没有构造 `judgeInfo`。判题服务未先检查沙箱状态，就把空 `judgeInfo` 交给策略读取，可能触发异常；消费者最终把任务写为 FAILED。二开第一步应先统一结果契约。

**源码依据：** `JavaNativeCodeSandbox#fail`、`JudgeServiceImpl#doJudge`、`JavaLanguageJudgeStrategy#doJudge`、`MyMessageConsumer#receiveMessage`。

**继续追问：** 哪些错误是用户代码结果，哪些错误可以重试？

### 12. 当前 TLE 和 MLE 为什么不可信？

**参考回答：** MLE 不可信，因为沙箱固定返回 `memory=0L`。TLE 的问题是代码先同步读取 stdout，再执行带超时的 `waitFor`；不退出且不输出的程序可能卡在读流阶段，根本走不到超时等待。策略中的时间判断还额外减去 10 秒。

**源码依据：** `JavaNativeCodeSandbox#executeCode/readOutput`、`JavaLanguageJudgeStrategy#doJudge`。

**继续追问：** 如何并发消费 stdout/stderr？如何可靠回收整个进程树？

### 13. 为什么当前沙箱不能称为生产级安全沙箱？

**参考回答：** 用户代码直接作为宿主机子进程运行，没有提交级 CPU、内存、PID、网络和文件系统隔离，也缺少可靠的进程树回收。共享密钥只限制谁能调用接口，不能限制用户代码能做什么。

**源码依据：** `ExecuteCodeController#executeCode`、`JavaNativeCodeSandbox#checkAuth/executeCode`。

**继续追问：** Docker 容器是否天然安全？还需要哪些限制？

**回答边界：** 只能称为本地或内网演示级进程执行器。

### 14. 项目是否真正支持 Java、C++、Go？

**参考回答：** 请求枚举允许 `java/cpp/go`，但当前执行端固定生成 Java 文件并调用 `javac/java`，所以只能确认 Java 主链。其他语言需要独立编译运行器和 E2E 证据。

**源码依据：** `QuestionSubmitLanguageEnum`、`JavaNativeCodeSandbox#executeCode`。

**继续追问：** 多语言执行器应该共用哪些契约，隔离哪些实现？

## 四、鉴权、性能与验证

### 15. 登录、业务权限和内部接口鉴权如何分层？

**参考回答：** 登录后 User 保存在 Spring Session；业务接口通过 Session 取用户，并由 `@AuthCheck` AOP 判断登录、角色和封禁状态。Feign 调用通过拦截器添加内部密钥，Gateway 和业务服务过滤器分别保护 `/inner/**`。Gateway 对普通业务请求没有统一登录认证。

**源码依据：** `UserServiceImpl#userLogin`、`UserFeignClient#getLoginUser`、`AuthInterceptor`、`FeignInnerAuthConfig`、`GlobalAuthFilter`、`InnerApiAuthFilter`。

**继续追问：** 绕过 Gateway 直连服务怎么办？共享密钥有哪些局限？

### 16. 提交接口 QPS 为什么不等于判题吞吐？

**参考回答：** 提交接口主要执行鉴权、数据库写入和入队；真实判题包含编译、启动进程、多个测试用例和资源隔离，成本高得多。高提交 QPS 可能只是让队列快速积压，因此要分别报告提交吞吐、排队时间和每秒完成判题数。

**源码依据：** 对比 `QuestionSubmitServiceImpl#doQuestionSubmit` 与 `JavaNativeCodeSandbox#executeCode` 的工作量。

**继续追问：** 队列长度持续增长说明什么？如何寻找瓶颈？

### 17. 你准备怎样验证可靠性改造？

**参考回答：** 至少覆盖数据库事务回滚、发布前杀进程、Broker NACK、错误 routing key、同一 ID 并发投递、写回后 ACK 前宕机、RUNNING 后宕机、连续依赖失败进入 DLQ。单测只证明局部分支，中间件语义要使用真实 RabbitMQ/MySQL 集成和故障注入验证。

**源码依据：** 验证点分别对应提交服务、生产者、消费者、判题状态迁移和恢复任务。

**继续追问：** 哪些场景可以 Mock，哪些必须使用真实中间件？

## 五、个人贡献与证据边界

### 18. 到目前为止你具体做了什么？

**参考回答：** 我对现有异步判题链做了源码审计，定位了结果契约、DB 与 MQ 非原子、消费抢占竞态、RUNNING 无恢复以及沙箱隔离不足等问题，并形成了按结果契约、CAS、租约恢复、Outbox/DLQ、沙箱安全推进的二开方案。目前这些属于静态审计和设计，后续要用代码、测试、故障注入和压测逐项形成证据。

**源码依据：** 能现场定位上述问题的方法，而不是只展示文档。

**继续追问：** 哪一项已经实现？测试在哪里？为什么按这个顺序做？

**回答边界：** 未实现的内容始终使用“设计、准备、计划”，不能使用“完成、保障、提升”。

### 19. 为什么选择可靠判题与沙箱安全作为二开主线？

**参考回答：** 在线判题的核心不只是 CRUD，而是异步任务在重复投递、进程崩溃和不可信代码执行下仍能得到明确、可恢复的结果。这条主线能够同时体现 RabbitMQ、数据库并发控制、状态机、故障恢复和安全意识。

**源码依据：** 当前链路中确实存在 DB/MQ 双写、状态竞争、失败无恢复和进程隔离不足。

**继续追问：** 如果时间有限，你先实现哪一项？为什么？

## 六、面试表述红线

| 当前可以说 | 当前不能说 |
|---|---|
| 基于现有项目梳理真实判题调用链 | 项目由我从零开发 |
| 通过静态审计识别关键问题 | 已保证消息绝不丢或恰好一次 |
| 设计 Outbox、CAS、租约和 DLQ 方向 | 上述机制已落地并通过故障验证 |
| 当前存在 Java 编译运行链路 | 已完整支持 Java/C++/Go |
| 沙箱有鉴权、超时、长度限制和清理 | 已实现生产级安全沙箱 |
| 计划在固定环境下进行压测 | 已达到某个 QPS 或提升比例 |

证据等级必须和动词匹配：

| 证据 | 可以使用的动词 |
|---|---|
| 静态源码 | 梳理、识别、设计 |
| 代码与单测 | 实现、通过单元测试 |
| 中间件集成 | 在指定环境验证 |
| E2E | 跑通完整链路 |
| 故障注入 | 在指定故障点验证恢复 |
| 压测 | 在明确环境与负载下得到数据 |

本地测试、集成环境和生产运行不是同一证据等级。任何性能数字都必须带机器配置、数据集、并发方式、持续时间和原始结果。
