# CodeOJ 项目导览



## 1. 各模块负责什么

| 模块                                               | 在项目中的职责                            | 先找的入口                                            |
| ------------------------------------------------ | ---------------------------------- | ------------------------------------------------ |
| `codeoj-frontend`                                | 展示题目、编辑代码、提交和查看结果                  | `src/views`、`generated/services`                 |
| `codeoj-backend-gateway`                         | 将 `/api/**` 请求转给对应服务，并过滤未授权的内部接口请求 | `GlobalAuthFilter`、路由配置                          |
| `codeoj-backend-user-service`                    | 注册、登录、用户信息及 Session                | `UserServiceImpl`                                |
| `codeoj-backend-question-service`                | 管理题目和提交记录；提交入库后发布判题消息；提供结果读写接口     | `QuestionController`、`QuestionSubmitServiceImpl` |
| `codeoj-backend-judge-service`                   | 消费消息，读取提交和题目，调用沙箱，形成判题结论并回写        | `MyMessageConsumer`、`JudgeServiceImpl`           |
| `codeoj-backend-codesandbox`                     | 接收执行请求，编译并运行 Java 代码，返回各用例输出和执行信息  | `ExecuteCodeController`、`JavaNativeCodeSandbox`  |
| `codeoj-backend-service-client`                  | 定义服务间的 Feign 调用及内部请求处理             | `QuestionFeignClient`、`UserFeignClient`          |
| `codeoj-backend-model` / `codeoj-backend-common` | 放跨服务数据契约、枚举，以及通用响应和异常等公共代码         | `QuestionSubmit`、`ExecuteCodeResponse`           |

MySQL 保存用户、题目、提交和结果；RabbitMQ 传递待判题的提交 ID；Redis 支撑共享 Session；Nacos 用于服务注册与发现。它们是模块协作依赖的基础设施，不是新的业务模块。

## 2. 一次提交怎样走完

```text
前端提交代码
  → Gateway → Question Service：校验并保存 QuestionSubmit（WAITING）
  → RabbitMQ：发送 submissionId
  → Judge Service：消费 ID，读取提交和题目，改为 RUNNING
  → Code Sandbox：编译、逐用例运行，返回 ExecuteCodeResponse
  → Judge Service：对照标准答案，写回状态和 JudgeInfo
  → 前端查询提交结果
```

提交接口返回的是提交 ID，判题由消息消费者异步进行，因此返回 ID 时通常还没有最终结果。消息只携带 ID；代码、题目和测试用例在消费时通过服务接口查询。关键调用依次见 [`QuestionSubmitServiceImpl#doQuestionSubmit`][submit-service]、[`MyMessageConsumer#receiveMessage`][consumer] 和 [`JudgeServiceImpl#doJudge`][judge-service]。

## 3. 三个“状态”分别回答什么

| 数据位置                         | 含义           | 当前代码中的例子                                                     |
| ---------------------------- | ------------ | ------------------------------------------------------------ |
| `QuestionSubmit.status`      | 整个判题任务进行到哪一步 | `WAITING`（0）→ `RUNNING`（1）→ `SUCCEED`（2）；异常处理可能写 `FAILED`（3） |
| `ExecuteCodeResponse.status` | 这次沙箱执行请求的结果  | Java 沙箱用 `1` 表示执行成功、`2` 表示执行失败                               |
| `JudgeInfo.message`          | 用户代码的判题结论    | `Accepted`、`Wrong Answer` 等                                  |

例如代码运行成功但输出错误，任务仍可进入 `SUCCEED`，而判题结论是 `Wrong Answer`。所以 **`SUCCEED` 不等于 `Accepted`**。提交状态定义在 [`QuestionSubmitStatusEnum`][submit-status]，最终回写在 [`JudgeServiceImpl#doJudge`][judge-service]。

还要留意当前实现的边界：Java 沙箱遇到编译错误或运行错误时返回 `status=2`，但未填 `judgeInfo`；判题服务未先处理这个状态就进入策略，异常可能被消费者记为任务 `FAILED`。因此不要把这些情况描述成已经稳定映射为 CE、RE 或 TLE，细节见[源码阅读路径的状态部分](01-source-reading-paths-and-tests.md#3-路径二任务状态沙箱状态和判题结果)。

## 4. 沙箱与判题规则怎样组织

这里有三处值得认识的设计，它们分别解决不同的变化：

| 代码位置                                                                         | 可以怎样理解 | 当前实际作用                                                                          |
| ---------------------------------------------------------------------------- | ------ | ------------------------------------------------------------------------------- |
| [`CodeSandbox`][sandbox-interface] + [`CodeSandboxFactory`][sandbox-factory] | 接口和工厂  | 判题服务按 `codesandbox.type` 选择 `example`、`remote` 或 `thirdParty` 实现；默认落到 `example` |
| [`CodeSandboxProxy`][sandbox-proxy]                                          | 代理     | 在选中的沙箱外包一层，请求前后记录日志，再转发 `executeCode`                                           |
| [`JudgeStrategy`][judge-strategy] + [`JudgeManager`][judge-manager]          | 策略     | 沙箱只给出执行结果；管理器按语言选择判题规则，Java 策略比较输出及资源限制                                         |

选择 `remote` 时，判题服务的 [`RemoteCodeSandbox`][remote-sandbox] 通过 HTTP 调用独立的沙箱服务；后者的 [`JavaNativeCodeSandbox`][native-sandbox] 才真正写文件、调用 `javac` 和 `java`。**工厂和代理位于判题服务一侧，代码执行位于沙箱服务一侧**。`thirdParty` 目前只是占位实现，不能当作可用的第三方沙箱。
