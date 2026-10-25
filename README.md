# CodeOJ 在线判题与赛事平台

CodeOJ 基于课程基础版本继续开发，使用 Java 8、Spring Cloud 和 Vue 3，覆盖用户与题目管理、可靠异步判题、比赛快照、限额报名、成绩榜单与最终结算。MySQL 保存业务事实，RabbitMQ 传递事件，Redis 承载会话、报名预留和榜单缓存。

完成情况、各轮验证范围与后置工作统一见[项目状态](docs/status.md)。学习当前实现，从下方源码路线进入。

## 从哪里开始

| 目的 | 入口 |
| --- | --- |
| 跟读源码和自测 | [学习总览](docs/learning/01-总览.md) → [源码链路](docs/learning/03-链路.md) → [面试练习](docs/learning/04-面试.md) |
| 启动和演示 | [Mac 本机启动](docs/contest/local-startup.md) |
| 查看进度和验收 | [项目状态](docs/status.md) |
| 查全部文档、历史和证据 | [文档导航](docs/README.md) |

## 本机启动

在 macOS arm64 的仓库根目录执行，需要 Java 8、Maven、Python 3.11+、Node/npm、C 编译工具、网络和至少 12GiB 临时空间。

```bash
# 完整构建，启动真实依赖及 Linux 隔离判题，验证后清理。
./scripts/local-stack.sh verify

# 完成启动验证后保留前台演示；Ctrl+C 清理。
./scripts/local-stack.sh serve
```

真实 Chrome 验证的额外依赖、私有演示账号、输出地址及失败清理方式见[启动说明](docs/contest/local-startup.md)。单次启动 smoke 不替代整套 JV；完整复验见[交接文档](docs/contest/handoff.md)。

## 当前业务链

```text
浏览器 → Gateway → 题目服务受理事务
                       ├─ 提交 + 固定规格 + execution + Outbox → MySQL
                       └─ 事务外发布 → RabbitMQ → 判题服务
                                                   ├─ claim / heartbeat → 题目服务
                                                   └─ 隔离沙箱 → 每次编译/用例独立容器

判题结果 → 带代次/token/期限的权威事务 → 结果事件 → 榜单投影 → 重建与最终结算
报名请求 → Redis Lua 预留 → 请求/Outbox 落库 → MQ 消费 → MySQL 确认资格 → 同步账本
```

平台错误进入有限恢复或人工处理，不当作用户 WA。报名资格以 MySQL 确认为准，Lua 报错不表示此前写入已经回滚。源码、时序和失败窗口见[链路学习](docs/learning/03-链路.md)。

## 模块与技术

| 模块 | 职责 |
| --- | --- |
| `codeoj-frontend` | Vue 3、TypeScript、Arco Design、Monaco：题目、练习、赛事管理/参赛、榜单 |
| `codeoj-backend-gateway` | Spring Cloud Gateway：API 路由、CORS、内部请求边界 |
| `codeoj-backend-user-service` | 用户管理、Spring Session Redis、当前账号与角色 |
| `codeoj-backend-question-service` | 题目、提交受理/固定规格、Outbox、执行权威、报名与计分 |
| `codeoj-backend-judge-service` | RabbitMQ 消费、领取/续租、远程沙箱、结果映射 |
| `codeoj-backend-codesandbox` | Linux 容器执行、资源限制、持久 token/slot 与残留回收；另保留可信 native 模式 |
| `codeoj-backend-common` / `model` / `service-client` | 通用响应/异常、DTO/协议、OpenFeign 与内部鉴权 |

后端采用 Java 8、Spring Boot 2.6.13、Spring Cloud Alibaba、MyBatis-Plus；数据与消息使用 MySQL 8、Redis、RabbitMQ。工程包含 Maven 多模块与 OpenAPI TypeScript 客户端。Nacos 配置和旧 Compose 模板保留，当前本机证据使用静态服务发现。

## 运行约束

三个业务开关 `RELIABLE_JUDGE_ENABLED`、`ASYNC_REGISTRATION_ENABLED`、`SCOREBOARD_ENABLED` 默认关闭；新版需按顺序应用 V001—V005 并显式启用相应开关。沙箱默认 `isolated`，要求 Linux Docker/cgroup v2、固定镜像与专用持久状态目录；配置缺失时拒绝启动。现有完整执行链以 Java 为主。

旧版无 token/deadline 的调用仅适用于显式可信 native 演示。旧队列切换、报名旧账本升级与累计 10000 条准入限制见[运行交接](docs/contest/handoff.md)和[报名恢复](docs/contest/registration-recovery.md)。基础版本的 WAITING/RUNNING 流程及图解另见[历史说明](docs/history/legacy-judge-walkthrough.md)。

## 参考与致谢

本项目基于程序员鱼皮的 [OJ 在线判题系统项目实战教程](https://www.codefather.cn/course/1790980707917017089/section/1790992688770232322) 完成。项目的需求设计、整体架构和实现思路参考课程；本人跟随教程逐步完成代码实现，并整理了模块说明、源码阅读路径和核心流程图。

项目代码和相关材料的使用、传播应遵守原课程及相关素材的授权要求。
