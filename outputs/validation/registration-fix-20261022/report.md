# CodeOJ 报名恢复修复验证

日期：2026-10-22（Asia/Shanghai）。基线 `f10341721a1d8c5c7342bab671b607063338222e` 加本轮工作区变更。本报告保存提交前的验证快照；发布状态以仓库提交与远端记录为准。对应[原审查](../registration-review-20261022/report.md)的 R1/R2，以及用户追加的 Lua 执行失败窗口。

**结论：R1/R2 已修复，Lua 部分写入与丢响应恢复已实现并有运行证据。全部后端 248 项通过；真实 MySQL/Redis/RabbitMQ 故障联调通过；前端最终构建通过。** 不将这些结果描述为生产、压测、Redis Cluster 或本轮全套 JV/容器隔离重新验收。

## 最终实现

1. V005 新增 reservationSequence，按比赛/epoch/序号唯一。Redis 在新准入及维护时对照数据库持久最大序号；旧快照回退立即关闸。旧查询样本下复用序号仍被数据库唯一约束拦截。
2. Lua 在初始化、预留及完成/释放前记录原操作意图，异常恢复核验原身份和修改前/目标值，用确定目标补齐。重复修复不重复扣减、退款；响应丢失沿用原 request/event/key。错误类型、冲突证据和无法访问的 Redis 保持不可判断/暂停。
3. 维护任务会依据数据库持久终态补写旧快照回退的 Redis 完成/释放标记，即便数据库本地标记已是 COMPLETE/RELEASED。
4. 新比赛容量及累计报名账本上限均为 10000；累计包含已释放身份。满账本拒绝新身份，保留原键重试、查询和后续确认。
5. 旧格式账本先关闸、验证完整身份与容量，再稳定分配序号并升级；原请求/资格不变。升级中断沿用已提交序号继续，不重置库存。

实现与迁移步骤见[报名恢复说明](../../../docs/contest/registration-recovery.md)；学习链路和面试第 13 题同步更新。

## 后端及真实 Redis 故障测试

命令使用 Java 8、固定 Redis 6.2.24 和显式 native observation：

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home \
  PATH="/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home/bin:$PATH" \
  mvn -B -o -f codeoj-backend/pom.xml \
  -Dcodeoj.real-redis=true -Dcodeoj.redis-server=/absolute/path/to/private/redis-server \
  -Dcodeoj.native-observation=true package
```

[完整日志](backend-regression.log)：27 个测试类，248 tests，0 failures/errors/skipped；默认 infrastructure 标签的空上下文测试不计入。与原 230 项相比新增 18 个报名测试，本轮报名 Redis/H2 测试共 48 项，消息契约测试 7 项。消息契约为 mock，不计作实际 Broker。

新增验证包括：

- 原审查“旧快照含未落库 A，掩盖丢失已确认 B”的反例变为暂停，新 C 无法受理；原身份保留。
- 旧数据库样本下人为重新分配已持久序号，事务回滚、比赛暂停。
- 容量 10001 在建赛阶段拒绝；低容量比赛累积 10000 个已释放身份后，第 10001 个新身份拒绝，原身份重试正常，10000 条关闸快照可读。本项为真实 Lua 合约夹具，不是 10000 个完整 HTTP/MQ 账号验收。
- 真实生产 Lua 源码分别在预留的 7 个写入位置、释放的 6 个位置和初始化的 3 个位置后注入运行时错误。断言原身份、准确库存、无重复释放和可继续恢复；生产代码不含故障开关。
- API 部分写入后退出、完成标记失败不退款、成功响应丢失、执行前异常不伪造受理、SCRIPT FLUSH、错误键类型、冲突修复证据。
- 旧格式关闸升级、升级中断后沿用持久序号，以及旧快照回退终态标记后的幂等重放。

首轮 37 项旧回归通过；加入大账本后，旧 AOF 重启测试暴露夹具只等端口、未等加载完成，出现 LOADING。已改为认证 PING 就绪检查，后续故障回归及全量回归通过。原失败日志 [fault-tests.log](fault-tests.log) 保留，不把失败算作通过。

## 真实 MySQL/Redis/RabbitMQ 联调

最终命令：

```bash
python3 -B scripts/local/check-registration-recovery.py \
  --runtimes /private/path/from-prepare-runtimes \
  --evidence outputs/validation/registration-fix-20261022/real-integration-final
```

最终证据：[运行日志](real-integration-final.log)、[业务事实](real-integration-final/registration-facts.json)、[运行与依赖](real-integration-final/run.json)、[进程端口清理](real-integration-final/cleanup-check.json)。版本：MySQL 8.0.46、Redis 6.2.24、RabbitMQ 4.3.6、Erlang 28.5.0.8。五个服务、Spring Session、Gateway、静态服务发现。

| 场景 | 结果及边界 |
| --- | --- |
| V001—V005、唯一序号索引 | 真实 InnoDB 迁移成功，重复序号实际报 MySQL 1062 |
| 正常报名 | 实际注册登录、HTTP 受理、Outbox、Broker ACK、消费者确认，序号落库 |
| R1 旧快照 | 数据库保持最新 B、Redis 恢复 A 的原 DUMP；服务重启后暂停，新 C 拒绝，保留原 A、既有资格不超额 |
| Lua 扣库存后中断 | 写入意图后、库存更新后注入实际脚本错误；重启题目服务，按原 request/event 补建并经真实 MQ 确认；原键重试同 ID |
| 释放中断 | 数据库已有 FAILED 终态，Lua 写库存后中断；重启后完成 RELEASED，新用户只能拿到一个释放名额 |
| 旧账本升级 | 原已确认身份保持，关闭旧格式后完整核对并升级序号，新报名经真实 MQ 确认 |
| 容量 10001 | 实际 HTTP 400，在新建阶段拒绝 |
| 最终标记收敛 | 等待所有 CONFIRMED 请求的数据库 reclaimState=COMPLETE；没有把刚确认未标记的瞬间当最终收敛 |

旧快照和释放的故障准备明确使用私有 SQL/Redis 夹具；B 的确认事实由 SQL 构造，不称为 B 经消费者产生。正常报名、部分预留后重启及升级后的新报名则经过实际 Broker/消费者。沙箱显式 native 启动但未执行参赛代码，不重新证明隔离执行器安全。

首轮原生故障客户端误连 Redis DB0，应用实际使用 DB1，因此在读取空命名空间时失败。已修正客户端认证后 SELECT 1；首轮 [real-integration.log](real-integration.log) 和清理记录保留。第二轮核心场景通过，摘要曾捕获一条 CONFIRMED/RESERVED 的瞬时状态；最终脚本新增全部终态标记收敛断言并再次通过。以 final 目录为最终收敛证据。

## 前端与文档

最终 `npm run build` 通过：[frontend-build-verified.log](frontend-build-verified.log)。页面容量最大值改为 10000，解释累计受理上限，满账本错误转换为中文；无浏览器重验。修改说明时引入的 Prettier 报错已按项目 formatter 修正，失败日志保留在 frontend-build-final.log。依赖、Browserslist 和包体积既有警告未被抹去。

新增迁移、修复说明、学习链路和面试解释均保留。未改变历史审查、JV 或原启动报告。新增 [summary.json](summary.json)、[源码指纹](source-sha256.json)及源码补丁用于核对本轮构建来源。

## 用户提到的点评代码

只读核对 DianPing `8bec9928f853ffb0999787b62e4890fc84864fb4`：异常后查原映射已经存在；但其 Lua 先 DECR、后写用户映射，映射缺失不能排除脚本中途失败。该判断仍不足以覆盖部分写入。本轮修复和运行证据属于 CodeOJ，未修改或验收 DianPing，不能宣称点评也已修复。

## 清理与限制

任务临时 Redis 源码/编译运行时、原生 MySQL/Redis/MQ 的私有数据及配置均清理；无所属端口或进程残留，见 [cleanup.json](cleanup.json)。正常 Maven 缓存、项目 target 和构建 dist 保留，不删除用户原有文件。

旧账本损坏且没有操作意图、超过原支持规模的数据，以及 Redis 与数据库同时丢失仍需独立恢复处理；不会自动猜库存。Redis Cluster、真实主从切换、生产部署、Nacos、压测与内核/隔离验收没有在本轮执行。
