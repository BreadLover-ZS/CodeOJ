# CodeOJ：在线判题系统二次开发基线

CodeOJ 是一个 Spring Cloud + Vue 3 的前后端分离在线判题项目。当前仓库可作为理解 OJ 判题链路的源码基线；后续二次开发重点是补齐**异步判题可靠性、失败恢复、沙箱安全和可复现压测**。

> 当前状态：已完成静态源码审计，尚未完成统一 Java 8 环境下的构建、全链路联调、故障注入或压测。README 中的“当前实现”不等于生产可用。

## 项目定位

当前代码已经覆盖：

- 用户注册、登录、Spring Session Redis 共享登录态和角色鉴权；
- 题目管理、代码提交与提交记录查询；
- RabbitMQ 异步投递判题任务；
- 判题服务调用远程代码执行服务并写回结果；
- Java 源码的 `javac` 编译和子进程运行；
- Vue 3 管理端、题目列表、代码编辑和提交页面。

需要明确的边界：

- Gateway 当前负责路由、CORS 和 `/inner/**` 请求头校验，**没有完成统一登录认证**；业务鉴权主要在服务内的 `@AuthCheck` 切面和 Session 读取逻辑中。
- 提交枚举接受 `java`、`cpp`、`go`，但当前执行器固定生成 `.java` 文件并调用 `javac/java`，因此**只有 Java 有实际执行链路**。
- 当前沙箱是长期运行服务中的本机子进程执行器，不具备提交级容器隔离、CPU/内存/PID/网络限制，**不能称为生产安全沙箱**。
- 当前 MQ 链路没有 Outbox、发布确认、可靠重试、死信重放和 RUNNING 超时恢复，**不能声称保证消息不丢或恰好一次执行**。

## 系统结构

```text
Vue 3 :8000
    |
    | /api/**
    v
Gateway :8101
    |-- /api/user/**     -> User Service :8102
    |-- /api/question/** -> Question Service :8103 -> MySQL
    `-- /api/judge/**    -> Judge Service :8104
                                  ^
Question Service -- RabbitMQ ----|
                                  |
                                  `-- HTTP -> Code Sandbox :8090

Redis       Spring Session 共享登录态
Nacos       服务注册与发现
RabbitMQ    判题任务异步传递
MySQL       用户、题目、提交和判题结果
```

| 模块 | 当前职责 |
|---|---|
| `codeoj-backend-gateway` | API 路由、CORS、阻止无内部密钥的 `/inner/**` 请求 |
| `codeoj-backend-user-service` | 注册、登录、用户管理、Session 写入与读取 |
| `codeoj-backend-question-service` | 题目 CRUD、提交入库、MQ 发布、结果持久化接口 |
| `codeoj-backend-judge-service` | MQ 消费、判题编排、沙箱调用、策略判定 |
| `codeoj-backend-codesandbox` | Java 编译与进程执行 |
| `codeoj-backend-service-client` | OpenFeign 内部接口、内部请求头和权限切面 |
| `codeoj-backend-model` | 跨服务实体、DTO、枚举和沙箱契约 |
| `codeoj-backend-common` | 通用响应、异常、注解和工具 |
| `codeoj-frontend` | Vue 3 页面和已生成的 TypeScript API 客户端 |

## 文档导航

面试准备只读以下三份，再沿文档中的关键方法回到源码：

1. [面试阅读入口](docs/learning/00-interview-reading-guide.md)
2. [关键源码与判题主链](docs/learning/01-key-source-and-judge-chain.md)
3. [高频面试问答与证据边界](docs/learning/02-interview-qa-and-evidence.md)

需要开发前端时再查阅[前端说明](codeoj-frontend/README.md)。

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 | Java 8、Spring Boot 2.6.13、Spring Cloud Alibaba、MyBatis-Plus、OpenFeign |
| 数据与消息 | MySQL 8、Redis、Spring Session、RabbitMQ、Nacos |
| 前端 | Vue 3、TypeScript、Vue Router、Vuex、Arco Design、Monaco Editor |
| 工程 | Maven 多模块、Docker Compose、OpenAPI TypeScript Codegen |

## 复现前置条件

仓库暂时没有“已验证的一键启动”。第一次运行应按以下 Gate 逐项记录，而不是只看到进程启动就视为成功。

### Gate 1：冻结工具链

- JDK 8；项目 `pom.xml` 声明 `java.version=1.8`。
- Maven 3.x。
- Docker 与 Docker Compose。
- Node.js/npm；仓库尚未固定 Node 版本。

本机曾在 Java 26 下构建失败，该结果只能说明工具链不匹配，不能直接归因于源码。请先切换 JDK 8，再记录：

```bash
java -version
mvn -version
docker version
docker compose version
node -v
npm -v
```

### Gate 2：构建后端产物

各服务 Dockerfile 使用 `ADD target/*.jar`，因此必须先生成 JAR，再执行 Compose 构建：

```bash
cd codeoj-backend
mvn clean package
```

通过标准：8 个 Maven 模块构建成功，测试没有被跳过，并保留完整日志。当前测试大多只是空的 `contextLoads()`，所以构建成功仍不等于业务正确。

### Gate 3：启动基础设施并初始化 MQ

```bash
cd codeoj-backend
docker compose -f docker-compose.env.yml up -d
```

执行 `mysql-init/create_table.sql` 后，确认 MySQL、Redis、Nacos、RabbitMQ 均健康，再声明：

```text
exchange: code_exchange (direct, durable)
queue:    code_queue (durable)
binding:  my_routingKey
```

注意：判题服务中的 `InitRabbitMqBean` 只读取 host，没有注入 Compose 使用的用户名、密码、端口和 vhost；其 exchange 声明参数也与上述 durable 目标不一致。该 Bean 不能作为“拓扑初始化已可靠完成”的证据，二开时应改为 Spring AMQP 声明式配置。

### Gate 4：启动并验证服务

在 Gate 2、3 通过后，再尝试：

```bash
docker compose -f docker-compose.service.yml up --build
```

至少验证以下真实链路：

1. 注册、登录并通过 Redis Session 访问需登录接口；
2. 管理员创建题目；
3. Java AC、WA、编译错误、运行错误、超时各提交一次；
4. `question_submit` 状态和 `judgeInfo` 与接口返回一致；
5. MQ 中无无法解释的积压，服务日志中无源码或隐藏用例泄露。

当前源码对编译错误、运行错误和超时的返回契约存在缺陷，以上第 4 项预计不会全部通过；详见[关键源码与判题主链](docs/learning/01-key-source-and-judge-chain.md)。

## 前端开发

```bash
cd codeoj-frontend
npm ci
npm run serve
```

仓库同时存在 `package-lock.json` 和 `yarn.lock`。在二开前应选择一个包管理器、删除另一个锁文件并在 CI 中固定；本说明暂按根文档既有 npm 路径书写，尚未形成运行证据。

## 二次开发目标

第一阶段不追求“功能多”，而追求一条可证明的可靠闭环：

```text
提交事务 -> Outbox -> 可靠发布 -> 幂等抢占 -> 受控执行
         -> 分类结果 -> 条件写回 -> ACK / 重试 / DLQ -> 超时恢复
```

完成顺序和面试口径见[关键源码与判题主链](docs/learning/01-key-source-and-judge-chain.md)与[高频面试问答](docs/learning/02-interview-qa-and-evidence.md)。压测必须分开报告“提交 API 吞吐”和“真实判题吞吐”，并保存环境、脚本、原始结果和失败拐点。

## 来源与许可状态

这是一个用于学习和二次开发的现有代码基线，不应表述为从零原创。

当前仓库没有 `LICENSE` 或 `NOTICE`，Git 历史也没有记录原始获取 URL、原作者和授权条款。在来源与许可证核实前：

- 不把基线代码计入个人原创成果；
- 不默认“网上可见”就意味着可以复制、公开分发或商用；
- 不自行补写一个许可证来替代原作者授权；
- 在简历和面试中明确使用了现有项目，并只陈述自己可由提交记录、测试和报告证明的改动。

下一步需要补录：原始获取 URL、获取日期、作者、许可证/授权原文、署名要求和个人变更清单。

## 当前证据等级

| 结论 | 证据 | 状态 |
|---|---|---|
| Maven 多模块、微服务边界和配置存在 | 源码/POM/配置静态审计 | 已确认 |
| RabbitMQ 异步提交和手动 ACK 代码存在 | 源码静态审计 | 已确认 |
| Java 进程执行器代码存在 | 源码静态审计 | 已确认 |
| JDK 8 下完整构建 | 构建日志 | 未确认 |
| Compose 可启动 | 运行日志与健康检查 | 未确认 |
| 注册到判题 E2E | 接口、数据库、MQ 联合证据 | 未确认 |
| 故障恢复语义 | 故障注入测试 | 未确认 |
| 安全隔离能力 | 恶意样例与资源指标 | 未确认 |
| 性能结论 | 可复现压测报告 | 未开始 |
