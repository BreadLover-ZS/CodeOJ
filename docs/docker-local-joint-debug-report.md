# 本机 Docker 全容器联调结果报告

| 项 | 内容 |
|---|---|
| 报告日期 | 2026-09-22 |
| 联调模式 | 全容器模式（基础设施 + 5 个后端服务均运行于 Docker），前端本机 dev server |
| 联调结论 | **通过**：9 容器运行正常，登录 → 建题 → 提交 → MQ 异步判题 → 沙箱执行 → 结果回写全链路验证成功 |
| 工作目录 | `e:\gitStore\ZSOJ` |
| 配套计划文档 | `.trae/documents/docker-local-joint-debug_plan.md` |

## 1. 联调目标

在本机 Docker Desktop 上以「后端服务全部容器化」的方式运行 CodeOJ，验证：

1. MySQL / Redis / RabbitMQ / Nacos 基础设施可用且数据持久化；
2. 网关、用户、题目、判题、代码沙箱 5 个服务在独立容器中经 Nacos 注册发现、经 Compose 网络互通；
3. 网关鉴权、CORS、内部接口密钥拦截生效；
4. 核心判题链路端到端可用：提交代码 → MQ → 沙箱真实编译执行 → AC/WA 判定回写；
5. 前端 dev server 与容器化后端联调正常。

## 2. 本机环境

| 工具 | 版本 |
|---|---|
| Docker | 29.6.1 |
| Docker Compose | v5.3.0 |
| JDK（构建机） | Temurin 11.0.30（编译目标 source/target = 1.8） |
| Maven | 3.9.4 |
| Node / npm | v24.13.1 / 11.8.0 |
| 操作系统 | Windows（Docker Desktop WSL2 后端） |

## 3. 最终运行拓扑

```text
浏览器 http://localhost:8080 (npm run serve，宿主机)
   │  axios withCredentials → http://localhost:8101
   ▼
codeoj-backend-gateway:8101            唯一发布到宿主机的业务端口
   ├─ lb → user-service:8102           仅 expose（容器网络内）
   ├─ lb → question-service:8103       仅 expose
   └─ lb → judge-service:8104          仅 expose
             │  Feign(X-Inner-Secret) → question/user 内部接口
             └─ HTTP(header: auth) → codesandbox:8090（仅 expose，容器内 javac/java 子进程）

question-service ──RabbitMQ(code_exchange/code_queue)──▶ judge-service 异步判题

共享依赖（mynetwork）：
  codeoj-mysql:3306   codeoj-redis:6379(db=1, Spring Session)
  codeoj-rabbitmq:5672   codeoj-nacos:8848(+gRPC 9848/9849)
```

### 3.1 容器状态（联调结束时快照）

| 容器 | 镜像 | 状态 | 宿主机端口 |
|---|---|---|---|
| codeoj-mysql | mysql:8 | Up (healthy) | 3306 |
| codeoj-redis | redis:6 | Up (healthy) | 6379 |
| codeoj-rabbitmq | rabbitmq:3.12.6-management | Up (healthy) | 5672（15672 未发布） |
| codeoj-nacos | nacos/nacos-server:v2.2.0-slim | Up (healthy) | 8848 / 9848 / 9849 |
| codeoj-backend-gateway | 本地构建 | Up，RestartCount=0 | **8101** |
| codeoj-backend-user-service | 本地构建 | Up，RestartCount=0 | 仅 8102 expose |
| codeoj-backend-question-service | 本地构建 | Up，RestartCount=0 | 仅 8103 expose |
| codeoj-backend-judge-service | 本地构建 | Up，RestartCount=0 | 仅 8104 expose |
| codeoj-codesandbox | 本地构建 | Up，RestartCount=0 | 仅 8090 expose |

业务镜像基于 `eclipse-temurin:8-jdk` 构建，单镜像约 590~627 MB。

## 4. 代码与配置改动清单

| 文件 | 改动性质 | 说明 |
|---|---|---|
| `codeoj-backend-judge-service/.../rabbitmq/InitRabbitMqBean.java` | 缺陷修复 | 注入 `spring.rabbitmq.host/port/username/password`，拓扑声明时携带真实凭据（原先硬连 guest/guest 必失败） |
| `docker-compose.env.yml` | 配置增强 | MySQL/Redis/RabbitMQ/Nacos 增加 healthcheck；Nacos 数据卷由 bind mount 改为命名卷 `nacos-data` |
| `docker-compose.service.yml` | 配置增强 | 5 个业务服务增加 `restart: unless-stopped`；网关补 `NACOS_ADDR/INNER_AUTH_SECRET/CORS_ALLOWED_ORIGINS`；沙箱补 `NACOS_ADDR` |
| 5 个服务的 `Dockerfile` | 基础镜像替换 | `openjdk:8-jdk-alpine` → `eclipse-temurin:8-jdk`（原因见 5.4） |
| 前端、application*.yml、SQL 初始化脚本 | 未改动 | CORS 白名单已含 8080；前端 OpenAPI BASE 已指向 `http://localhost:8101` |

## 5. 联调中发现的问题、根因与修复

### 5.1 RabbitMQ 交换机/队列无法初始化（阻断判题链路）

- **现象**：`InitRabbitMqBean` 用原生 `ConnectionFactory` 只设置了 host，默认以 `guest/guest` 连接 `codeoj-rabbitmq:5672`；broker 实际账号为 `codeoj/Rabbit@2026Code`，且 RabbitMQ 3.x 禁止 guest 非 loopback 登录。预期结果是 `code_exchange`/`code_queue` 不被创建，生产者消息静默丢失、消费者报错。
- **根因**：Bean 未读取 Spring 配置中的 MQ 凭据。
- **修复**：`@Value` 注入 host/port/username/password 后再创建连接（dev 默认值仍为 guest，容器内由 `SPRING_RABBITMQ_*` 覆盖）。
- **验证**：judge-service 启动日志输出「消息队列启动成功」；`rabbitmqctl` 可见 `code_exchange`(direct)、`code_queue`(durable) 及 binding `my_routingKey`，消费者在线。

### 5.2 基础设施就绪前业务容器首启失败

- **现象**：`depends_on` 只保证容器创建顺序；Nacos 首次启动约 30~60s，业务服务可能因注册中心/数据库未就绪退出。
- **修复**：业务服务统一加 `restart: unless-stopped`；基础设施增加 healthcheck 供人工确认就绪。
- **验证**：5 个业务容器 RestartCount 均为 0（启动时 Nacos 已就绪），后续手动制造重启场景可由策略自动恢复。

### 5.3 Nacos 在 Windows 绑定挂载上启动失败

- **现象**：Nacos 容器退出码 1，日志 `load derby-schema.sql error` → `java.sql.SQLTimeoutException: Login timeout exceeded`（内嵌 Derby）。
- **根因**：Derby 数据目录经 virtiofs 绑定挂载到 Windows 宿主机，文件锁/IO 延迟导致初始化超时。
- **修复**：`./.nacos-data` 绑定挂载改为 Docker 命名卷 `nacos-data`（WSL2 内 ext4）。
- **附加问题**：初版 healthcheck 用 `CMD-SHELL` 的 `/dev/tcp`，被镜像内 dash 执行报 `Directory nonexistent`；slim 镜像无 curl/wget。改为 `["CMD","bash","-c", ...]` 显式走 bash，探针请求 `/nacos/v1/console/health/readiness`（返回纯文本 `OK`）。

### 5.4 openjdk:8-jdk-alpine 基础镜像无法拉取

- **现象**：构建业务镜像时三个已配置加速器分别返回 403 Forbidden（xuanyuan、daocloud）/ not found（1ms.run），直连 Docker Hub 同样失败。
- **根因**：`openjdk` 官方镜像系列已归档弃用，国内镜像源不再缓存该 tag。
- **修复**：5 个 Dockerfile 统一改用 Adoptium 维护的同版本 `eclipse-temurin:8-jdk`（Java 8、完整 JDK，沙箱编译用户代码所需的 javac 具备）。
- **影响**：功能等价；镜像基线由 Alpine 变为 Ubuntu，体积增大（可接受，联调场景）。

### 5.5 旧数据卷导致 MySQL 密码与 compose 默认值不一致

- **现象**：业务容器以 `Mysql@2026Code` 连接 MySQL 报 `Access denied`。
- **根因**：`.mysql-data` 为 2026-09-10 旧卷，MySQL 初始化环境变量只在空数据目录生效，旧 root 密码为 `123456`。
- **处理（经确认保留旧数据）**：启动业务栈时用会话环境变量覆盖：`$env:MYSQL_ROOT_PASSWORD='123456'`。RabbitMQ 旧卷的 `codeoj/Rabbit@2026Code` 与现配置一致，无需处理。
- **彻底重置方式**：删除 `codeoj-backend/.mysql-data` 后重启 env 栈，即按强密码全新初始化（旧业务数据会丢失）。

## 6. 端到端验证记录

### 6.1 服务注册与中间件拓扑

- Nacos「服务列表」存在 5 个健康实例：gateway、user、question、judge、codesandbox；
- RabbitMQ：`code_exchange` / `code_queue` / binding `my_routingKey` 存在，判题服务消费者已注册到 `code_queue`；
- 判题两次后队列 `messages=0, ready=0, unacknowledged=0`（手动 ACK 正常，无积压、无无限重投）。

### 6.2 网关鉴权

| 用例 | 结果 |
|---|---|
| `GET /api/user/get/login`（未登录） | 业务响应 `{"code":40100,"message":"未登录"}`，网关→用户服务路由正常 |
| `GET /api/user/inner/get/1`（不带 X-Inner-Secret） | **HTTP 403**，内部接口拦截生效 |

### 6.3 核心判题链路（真实提交）

测试数据：

- 账号：`codeojtest / codeoj123456`（已提权 admin）；
- 题目 ID `2102236867951501314`：A+B，用例 `1 2→3`、`10 20→30`，时限 10000ms；
- 提交 1（`System.out.println(a+b)`），ID `2102236868215742465`；
- 提交 2（`System.out.println(a-b)`，故意错误），ID `2102236869167849474`。

数据库终态证据：

```text
id: 2102236868215742465   status: 2(SUCCEED)   judgeInfo: {"message":"Accepted","memory":0,"time":73}
id: 2102236869167849474   status: 2(SUCCEED)   judgeInfo: {"message":"Wrong Answer","memory":0,"time":67}
```

- 提交接口均返回提交 ID（WAITING 已落库后 MQ 投递）；
- 沙箱日志两次出现「沙箱编译完成，className = Main」，证明确实在容器内 javac 编译、java 执行 stdin 用例；
- judge 日志收到对应两条 `receiveMessage`，无「判题失败」错误；
- AC / WA 判定符合预期，`SUCCEED` 表示流程完成、具体结果由 judgeInfo.message 表达。

### 6.4 前端联调

- `npm ci` + `npm run serve` 一次成功，dev server：http://localhost:8080（Node 24 未触发 OpenSSL 兼容问题）；
- 浏览器实测：登录跳转成功并显示用户信息；题目列表可见「A+B JointTest」；提交记录页两条记录分别显示 **Accepted** / **Wrong Answer**、状态判题完成；
- 网络面板无 4xx/5xx，登录 Cookie 随跨域请求携带（CORS 白名单 + allowCredentials 配置正确）。

## 7. 日常启停与重新构建命令（PowerShell）

```powershell
cd e:\gitStore\ZSOJ\codeoj-backend

# 1) 启动基础设施（数据已持久化，秒级恢复）
docker compose -p codeoj -f docker-compose.env.yml up -d

# 2) 启动业务服务（注意旧 MySQL 卷密码）
$env:MYSQL_ROOT_PASSWORD='123456'
docker compose -p codeoj -f docker-compose.service.yml up -d
#   改过 Java 代码时，先重新打包再重建镜像：
#   mvn clean package -DskipTests
#   docker compose -p codeoj -f docker-compose.service.yml up -d --build

# 3) 前端（另开终端）
cd e:\gitStore\ZSOJ\codeoj-frontend
npm run serve   # http://localhost:8080

# 4) 查看状态与日志
docker compose -p codeoj -f docker-compose.env.yml ps
docker logs -f codeoj-backend-judge-service

# 5) 停止 / 彻底重置
docker compose -p codeoj -f docker-compose.service.yml stop
docker compose -p codeoj -f docker-compose.env.yml stop
# 彻底重置：删除 .mysql-data/.redis-data/.rabbitmq-data 及命名卷 codeoj_nacos-data 后重新 up
```

## 8. 已知事项与建议

1. **MySQL 密码双轨**：当前本机旧卷用 `123456`，compose 默认值 `Mysql@2026Code` 仅对新卷生效。团队共用环境时建议统一重置为强密码，并通过 `.env` 文件管理，不要依赖 shell 会话变量。
2. **沙箱为进程级隔离**：容器内以 javac/java 子进程执行用户代码，仅做超时（编译 15s / 运行 5s）、输出 64KB / 代码 256KB 限制与临时目录清理，无提交级容器隔离；当前仅支持 Java 完整链路（镜像基线为 Ubuntu，后续可按需安装 g++/go）。
3. **镜像体积**：Temurin 全量 JDK 使业务镜像约 600MB；如关注体积可评估 `eclipse-temurin:8-jdk-jammy` 与分层缓存，或沙箱外的服务改用 JRE 基线（沙箱必须保留 JDK）。
4. **Nacos 控制台鉴权关闭**（`NACOS_AUTH_ENABLE=false`）且 8848 映射到宿主机，仅限本机联调；接入团队网络前需开启鉴权或取消端口发布。
5. **RabbitMQ 生产者未启用 Publisher Confirm**，极端情况下 broker 未持久化消息时生产端无感知，属既有设计限制，不在本次联调范围内。
