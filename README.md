# CodeOJ 在线判题系统

基于 Spring Cloud 微服务 + Vue3 的前后端分离在线判题系统，支持题目管理、代码提交、远程沙箱判题，判题异步化（RabbitMQ）。

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 | Java、Spring Boot、Spring Cloud Alibaba（Nacos 注册中心）、MyBatis-Plus、Redis（登录态）、RabbitMQ（判题异步化） |
| 前端 | Vue 3、TypeScript、Arco Design、Monaco Editor、OpenAPI 代码生成 |
| 基础设施 | MySQL 8、Redis、RabbitMQ、Nacos，Docker Compose 编排 |

## 目录结构

```
codeoj-backend/         后端（多模块 Maven 工程）
  ├── codeoj-backend-gateway          网关（统一入口：认证、CORS、inner 拦截）
  ├── codeoj-backend-user-service     用户服务（登录注册、用户信息）
  ├── codeoj-backend-question-service 题目服务（题目 CRUD、提交）
  ├── codeoj-backend-judge-service    判题服务（消息消费、沙箱调用、策略判题）
  ├── codeoj-backend-model / common / service-client  共享模型与内部接口
  ├── docker-compose.env.yml          基础设施编排（MySQL/Redis/RabbitMQ/Nacos）
  ├── docker-compose.service.yml      业务服务编排
  └── mysql-init/create_table.sql     建表脚本（含索引）
codeoj-frontend/       前端（Vue3 + TS）
```

## 快速开始

### 1. 启动基础设施

```bash
cd codeoj-backend
docker compose -f docker-compose.env.yml up -d
```

### 2. 启动业务服务

```bash
docker compose -f docker-compose.service.yml up -d
```

> 业务服务通过 Nacos 注册，统一经网关 `http://localhost:8101/api/**` 访问；
> 判题依赖独立的代码沙箱服务，请先部署并配置 `CODESANDBOX_URL` 指向沙箱。

### 3. 启动前端

```bash
cd codeoj-frontend
npm install
npm run serve   # 默认 http://localhost:8000
```

### 4. 本地开发（不使用 Docker）

按序启动 Nacos、MySQL（执行 `mysql-init/create_table.sql`）、Redis、RabbitMQ，
再依次启动 gateway -> user-service -> question-service -> judge-service，最后运行前端。

## 环境变量配置

所有敏感配置支持环境变量注入，默认值仅用于本地开发，**生产部署务必覆盖**：

| 变量 | 说明 | 默认值 |
|---|---|---|
| `MYSQL_PASSWORD` | 数据库密码 | `123456`（本地）/ `Mysql@2026Code`（Compose/Prod） |
| `REDIS_PASSWORD` | Redis 密码 | 空（本地）/ `Redis@2026Code`（Compose/Prod） |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | MQ 账号密码 | `guest/guest`（本地）/ `codeoj/Rabbit@2026Code`（Compose/Prod） |
| `INNER_AUTH_SECRET` | 服务间内部接口鉴权密钥 | `codeoj-default-inner-secret` |
| `CODESANDBOX_URL` / `CODESANDBOX_SECRET` | 沙箱地址与鉴权密钥 | `http://localhost:8090/executeCode` / `secretKey` |
| `CORS_ALLOWED_ORIGINS` | 前端跨域白名单（逗号分隔） | `http://localhost:8000` |

## 注意事项

- 内部接口（`/inner/**`）仅允许服务间调用：网关 + 各服务双重校验 `X-Inner-Secret`，请勿将业务服务端口对外发布。
- 生产环境默认关闭 Swagger/Knife4j 文档（`knife4j.enable=false`）。
- 登录态基于 Redis 会话，业务鉴权位于服务端切面（`@AuthCheck`）。
- 代码沙箱不在本仓库内，判题能力依赖独立沙箱服务的可用性。