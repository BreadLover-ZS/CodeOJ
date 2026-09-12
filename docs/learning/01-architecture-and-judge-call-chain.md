# CodeOJ 当前架构与真实判题调用链

> 文档状态：静态源码审计 v1.0
>
> 审计基线：`master` / `53e57c8`
>
> 证据边界：本文说明当前代码实际写了什么，不代表构建、联调、安全或性能已经通过验证。

## 1. 系统地图

```text
Browser
  |
  | HTTP + Session Cookie
  v
Gateway :8101
  |-- /api/user/**     -> User Service :8102 -> MySQL / Redis Session
  |-- /api/question/** -> Question Service :8103 -> MySQL / RabbitMQ
  `-- /api/judge/**    -> Judge Service :8104 -> RabbitMQ / OpenFeign
                                                    |
                                                    | HTTP + auth header
                                                    v
                                             Code Sandbox :8090
                                                    |
                                                    `-> javac/java child process

Nacos：服务注册与发现
Redis：Spring Session 共享登录态
RabbitMQ：只传递判题任务 ID
MySQL：用户、题目、提交记录与结果
```

Gateway 的 `GlobalAuthFilter` 当前只对 `/**/inner/**` 校验 `X-Inner-Secret`，随后直接放行其他请求，源码仍保留“统一权限校验” TODO。登录和角色权限主要由业务服务读取 Session、`@AuthCheck` 和 `AuthInterceptor` 完成。

## 2. 用户与权限链路

### 2.1 注册与登录

`UserServiceImpl` 的当前行为：

1. 注册校验账号和两次密码；
2. 在 `synchronized(userAccount.intern())` 中先查账号再插入；
3. 新密码用 BCrypt 保存；
4. 登录时兼容历史 `md5(SALT + password)`，校验通过后升级为 BCrypt；
5. 把完整 `User` 对象放入 Spring Session；
6. 用户服务自己的 `getLoginUser` 会根据 Session 中 ID 再查数据库。

并发边界：Java 锁只覆盖单个 User Service JVM；多实例注册同一账号仍可能同时“查无记录”。`create_table.sql` 对 `userAccount` 只有普通索引，没有唯一约束。因此真正的唯一性必须由数据库唯一索引兜底，并把冲突转换为业务错误。

### 2.2 登录态与角色鉴权

- 标注 `@AuthCheck` 的接口由 `AuthInterceptor` 读取 Session 用户并校验登录或角色；
- 被封禁用户会在该切面中被拒绝；
- 未标注 `@AuthCheck` 的接口不经过这层检查；
- `UserFeignClient#getLoginUser` 是接口默认方法，只读取 Session 对象，不重新查库；
- `/inner/**` 依赖共享密钥请求头，适合当前演示环境，但不是细粒度服务身份认证。

不能把这套实现描述为“Gateway 统一 JWT 鉴权”。更准确的说法是“基于 Spring Session Redis 共享登录态，服务内切面完成接口角色校验，Gateway 拦截外部 inner 路径”。

## 3. 一次提交的真实调用链

### 3.1 HTTP 入口

前端请求：

```http
POST /api/question/question_submit/do
Content-Type: application/json

{
  "questionId": 1,
  "language": "java",
  "code": "public class Main { ... }"
}
```

调用路径：

```text
QuestionController#doQuestionSubmit
  -> UserFeignClient#getLoginUser
  -> QuestionSubmitServiceImpl#doQuestionSubmit
```

### 3.2 入库与发布

`QuestionSubmitServiceImpl#doQuestionSubmit` 依次：

1. 用 `QuestionSubmitLanguageEnum` 校验语言；
2. 查询题目是否存在；
3. 构造提交记录，初始化 `WAITING(0)` 和空 JSON 判题信息；
4. 插入 `question_submit`；
5. 通过 `MyMessageProducer` 向 `code_exchange` 发布 submission ID；
6. 尝试单独累加 `submitNum`，失败只记 warning。

代码注释写着“每个用户串行提交题目”，但方法中没有锁、队列、幂等键或串行化机制。该注释不是实现证据。

MQ 只传 ID 的好处是消息小、代码和用例不重复复制；代价是消费者依赖题目服务可用，而且执行时读取的是题目当前内容。题目或隐藏用例在提交后变化时，缺少提交时快照会影响可重复判题。

### 3.3 入库和 MQ 之间的丢失窗口

当前顺序是：

```text
INSERT question_submit 成功
  -> RabbitTemplate.convertAndSend
  -> 返回 submissionId
```

这不是同一个事务。生产者没有 publisher confirm、return callback 或 Outbox：

- 数据库成功、发布抛异常：接口失败，但记录可能永久停在 WAITING；
- 发布调用返回：只代表客户端调用返回，不证明消息已持久化并路由到目标队列；
- 用户重试：可能再生成一条提交记录。

因此当前只能说“使用 RabbitMQ 异步解耦提交和判题”，不能说“保证任务不丢”。

### 3.4 RabbitMQ 拓扑

目标拓扑在代码和文档中名为：

```text
direct exchange: code_exchange
        |
        | routing key: my_routingKey
        v
durable queue: code_queue
```

但 `InitRabbitMqBean` 存在配置缺口：

- 只给 Rabbit Java Client 设置 host，忽略 Spring 配置中的 username、password、port 和 vhost；
- Compose 使用 `codeoj/Rabbit@2026Code`，而 Java Client 默认 guest/guest；
- `exchangeDeclare(name, "direct")` 的默认 durable 语义与 README 中 durable 目标不一致；
- 初始化异常只记录日志，服务仍可能继续启动；
- connection/channel 没有显式关闭。

应以 Spring AMQP 的 `Exchange`、`Queue`、`Binding` Bean 统一声明，不保留多套初始化方式。

### 3.5 消费与 ACK

`MyMessageConsumer#receiveMessage` 使用手动确认：

```text
收到字符串消息
  -> Long.parseLong(message)
  -> judgeService.doJudge(id)
      -> 成功：basicAck
      -> 异常：查询提交终态、尝试标记 FAILED
                -> basicNack(requeue=false)
```

当前有四个关键问题：

1. `Long.parseLong` 位于 `try` 外，毒消息不会进入显式 ACK/NACK 分支；
2. catch 中查询提交记录本身没有被保护，Feign 失败可能再次跳出确认逻辑；
3. 结果已是终态时仍执行 `basicNack(requeue=false)`，语义上应把重复消息视为幂等成功并 ACK；
4. 没有 DLX/DLQ、重试次数和重放入口，`requeue=false` 的失败消息没有业务审计闭环。

手动 ACK 只控制 Broker 何时删除消息，不保证业务恰好执行一次。

## 4. 判题服务的状态与并发

`JudgeServiceImpl#doJudge` 当前流程：

1. 通过 Feign 查询提交和题目；
2. 检查提交状态必须为 WAITING；
3. 普通 `updateById` 把状态改为 RUNNING；
4. 调远程沙箱；
5. 调判题策略；
6. 写回 `SUCCEED(2)` 与 `judgeInfo`；
7. Accepted 时尝试累加 `acceptNum`。

### 4.1 先查后改不是抢占

两个消费者可能同时读到 WAITING，然后都成功执行普通 update，最终重复运行用户代码。取得执行权至少需要数据库条件更新：

```sql
update question_submit
set status = 1
where id = ? and status = 0;
```

只有影响行数为 1 的消费者获得执行权。这个 CAS 仍不能单独解决 RUNNING 后宕机，后者还需要租约、重试计数或恢复扫描。

### 4.2 三种状态不能混用

| 层级 | 字段 | 含义 |
|---|---|---|
| 提交任务 | `question_submit.status` | WAITING / RUNNING / SUCCEED / FAILED |
| 沙箱调用 | `ExecuteCodeResponse.status` | 当前约定 1 成功、2 失败 |
| 题目判定 | `judgeInfo.message` | Accepted / Wrong Answer / Time Limit Exceeded 等 |

`SUCCEED` 应表示判题流程正常结束，WA/CE/RE/TLE 也应是可查询的业务终态；`FAILED` 应留给基础设施或内部系统无法完成判题的情况。

## 5. 当前最严重的结果契约缺陷

`JavaNativeCodeSandbox#fail` 对编译错误、运行错误和超时返回：

```text
status = 2
message = 错误详情
outputList = []
judgeInfo = null
```

但 `JudgeServiceImpl`：

- 没有分支处理 `ExecuteCodeResponse.status`；
- 把 null `judgeInfo` 交给策略；
- `JavaLanguageJudgeStrategy` 立即调用 `judgeInfo.getMemory()`。

这会触发空指针异常，随后消费者把提交尝试标记为系统 `FAILED`。所以当前不能声称编译错误、运行错误、超时能稳定映射为 CE、RE、TLE。

正确方向是先定义明确契约，例如：

```text
SandboxOutcome
  kind: SUCCESS | COMPILE_ERROR | RUNTIME_ERROR | TIMEOUT | SYSTEM_ERROR
  outputs: [...]          仅 SUCCESS 必需
  timeMs / memoryKb       可获取时填写
  detail                  对用户脱敏后的错误
```

Judge Service 先映射沙箱 outcome，再决定是否需要做答案、时间和内存比较；用户代码失败不应抛成基础设施异常。

## 6. 判题策略的当前边界

正常成功响应进入 `JavaLanguageJudgeStrategy` 后，顺序是：

1. 输出数量是否与输入数量相等；
2. 每项输出字符串是否完全相等；
3. 内存是否超过题目限制；
4. 时间是否超过题目限制。

仍有两个无法成立的结论：

- 沙箱把 memory 固定为 `0L`，MLE 没有真实测量基础；
- Java 策略从实际毫秒耗时中固定减去 10,000ms，而单用例运行超时默认 5,000ms，当前 TLE 判断缺少依据且很可能不可达。

此外，`JudgeInfo.time` 的注释写成“KB”，属于单位文档错误。二开时应统一时间和内存单位，并用边界测试锁定语义。

`JudgeInfoMessageEnum` 的 `text/value` 也不一致：Accepted、Wrong Answer 的 value 是英文，而 Compile Error、Runtime Error、TLE 等 value 是中文。API 和数据库若直接保存 `getValue()`，客户端将收到混合语言且不稳定的机器值。目标契约应使用固定 code，展示文案由前端或国际化层处理。

## 7. 语言支持的真实范围

`QuestionSubmitLanguageEnum` 包含 java/cpp/go，前端也可能允许选择这些值；但 `JavaNativeCodeSandbox` 无论 language 是什么，都：

- 提取 Java public class 名；
- 写入 `.java`；
- 调用 `javac`；
- 调用 `java`。

因此当前是“接口层接受三种字符串，执行层只实现 Java”。在补齐编译器、运行命令、镜像、资源限制和每种语言的测试前，不得写“支持 Java/C++/Go 判题”。

## 8. 沙箱实现与安全边界

### 8.1 已有机制

- HTTP 共享密钥；
- 最大代码长度 256 KiB；
- 单次读取最大输出长度 64 KiB；
- 编译/运行超时配置；
- 每次请求创建临时目录并在 finally 清理；
- 每个用例启动独立 Java 子进程。

### 8.2 超时实现仍可能失效

当前顺序是先同步读取流，再 `waitFor(timeout)`：

```text
readOutput(process stdout/stderr)
  -> waitFor(timeout)
```

如果进程不关闭流，`readOutput` 可先阻塞，代码根本到不了带超时的 waitFor。stdout/stderr 也没有并发排空；向 stdin 写入发生在计时等待之前，也可能阻塞。输出截断只是停止本次读取，不能阻止子进程继续写管道。

### 8.3 缺失的隔离

- 无提交级短生命周期容器；
- 无 CPU、内存、PID 和总墙钟限制；
- 无禁网；
- 无只读根文件系统和受控工作目录；
- Dockerfile 默认 root；
- 没有处理后代进程和完整进程树；
- 没有限制用例数量与输入总大小；
- `CodeSandboxProxy` 记录完整请求/响应，会泄露用户源码和隐藏用例；
- `RemoteCodeSandbox` 未显式设置连接/读取超时，也未先校验 HTTP 状态。

准确称谓应是“带部分限制的 Java 进程执行器”。容器化部署服务本身不等于每次提交都被安全隔离。

## 9. 失败时间线

| 失败点 | 当前可能结果 | 目标能力 |
|---|---|---|
| 提交 INSERT 前失败 | 无记录、无消息 | 明确接口错误即可 |
| INSERT 成功、MQ 发布失败 | 永久 WAITING | Outbox + 重试发布 |
| 消息不可路由 | 当前无法确认 | mandatory return + 告警 |
| 重复消息/并发消费 | 可能重复执行 | CAS 抢占 + 幂等终态 ACK |
| RUNNING 后宕机 | 卡住或重投后转 FAILED | 租约/超时恢复 |
| 沙箱 CE/RE/TLE | 可能 NPE 后系统 FAILED | 结果契约与业务终态映射 |
| 结果写回后、ACK 前崩溃 | 重投后异常再 NACK | 识别终态并 ACK |
| 依赖暂时不可用 | 直接 NACK 丢弃 | 有界重试 + DLQ |
| 毒消息 | 可能绕过显式确认 | 解析保护 + 隔离队列 |

## 10. 当前测试与运行证据

仓库只发现少量空的 Spring Boot `contextLoads()`，没有：

- 判题策略单元测试；
- 消费幂等和状态迁移测试；
- RabbitMQ/Testcontainers 集成测试；
- 沙箱编译、运行、超时和恶意样例测试；
- 前端测试；
- E2E、故障注入或压测报告。

项目声明 Java 8；本机 Java 26 下的 Maven 失败不能证明源码本身不可构建。先冻结工具链，再建立基线。

## 11. 源码阅读索引

| 主题 | 入口 |
|---|---|
| Gateway | `GlobalAuthFilter`、gateway `application.yml` |
| 登录与权限 | `UserServiceImpl`、`AuthInterceptor`、`UserFeignClient` |
| 提交 | `QuestionController`、`QuestionSubmitServiceImpl` |
| MQ 发布 | `MyMessageProducer`、`InitRabbitMqBean` |
| MQ 消费 | `MyMessageConsumer` |
| 判题编排 | `JudgeServiceImpl`、`JudgeManager` |
| 沙箱客户端 | `CodeSandboxFactory`、`CodeSandboxProxy`、`RemoteCodeSandbox` |
| 沙箱服务 | `ExecuteCodeController`、`JavaNativeCodeSandbox` |
| 策略 | `JavaLanguageJudgeStrategy`、`DefaultJudgeStrategy` |
| 数据契约 | `QuestionSubmit`、`ExecuteCodeResponse`、`JudgeInfo`、相关枚举 |
| 数据库 | `mysql-init/create_table.sql` |

## 12. 外部语义参考

- Spring AMQP MANUAL 确认模式：<https://docs.spring.io/spring-amqp/reference/amqp/containerAttributes.html>
- Spring AMQP 发布确认与 returns：<https://docs.spring.io/spring-amqp/reference/amqp/template.html>
- Java 8 `Process` 与超时等待：<https://docs.oracle.com/javase/8/docs/api/java/lang/Process.html>

这些参考解释框架/API 语义，不是本项目已经正确实现这些机制的证据。
