# 剩余开发与本机隔离链路复验

日期：2026-10-22，Asia/Shanghai。业务基线 `b070aac28bf13b73a1e79723ee74b01a8bc727b7`；本轮源码/文档尚未提交，验证构建和源码指纹单独记录。

**结果：本轮约定的剩余开发及本机验证完成。JV01—JV29：29 PASS / 0 FAIL / 0 BLOCKED / 0 UNTESTED。** 结论限定于下列固定环境与已执行样例，不等于生产/内核逃逸/容量验收。

## 交付与结果

| 内容 | 实际结果 | 主要证据 |
| --- | --- | --- |
| 隔离执行器 | 实现逐 attempt 编译、逐用例新容器、只读产物、禁网络、临时盘/CPU/内存/PID 限额和私有受信收据 | `JavaIsolatedCodeSandbox`、`DockerIsolationEngine`、`isolation/Dockerfile`、`supervisor.py` |
| 持久登记与回收 | 共享 token/slot、结果重放、清理失败保留占用、manager 崩溃后回收及旧身份不重跑 | `DurableExecutionRegistry`、`DurableExecutionRegistryTest` |
| 全量后端回归/打包 | 230 tests / 0 failures / 0 errors / 0 skipped，27 个类；包含新增 8 项登记测试及独立 JVM 锁观察 | `lock-fixed-regression.log`、`backend-test-summary.json` |
| 真实隔离/恢复 | 15 项完整探针 + 1 项 CPU 内核节流 + 1 项 PID 内核事件，共 17 PASS | `probes-lock-fixed/isolation-results.json`、`cpu-quota/isolation-results.json`、`pid-events/isolation-results.json` |
| 真实业务故障矩阵 | 25 个分组全部 PASS，含 1 个夹具准备分组 | `full-matrix-lock-fixed/business-results.json`、`native/run.json` |
| 完整 JV 映射 | 29 PASS | `jv-matrix.md/json`；只把实际覆盖的样例映射到对应项 |
| F03 权限回归 | 53 PASS，原 Cookie 全程复用 | `permissions-isolated/permission-results.json`、`summary.json` |
| 真实前端主链 | 前端构建通过、真实 Chrome/HTTP 浏览器检查通过 | `frontend-build.log`、`full-matrix-lock-fixed/native/browser.log`、`REAL_BROWSER_PASSED` 事件 |
| 旧队列切换补验 | 排空旧消息、暂停写入、切换后新任务 AC、旧记录不变且可读、旧消费者退出 | `cutover-followup/cutover-results.json`、`run.json` |
| 清理 | 私有容器/卷为 0；VM、运行时、私有实例目录和本轮新 Lima 下载缓存删除，无所属残留进程 | `cleanup-preflight.json`、`cleanup.json`、`vm-stop.log`、`vm-delete.log` |

Java 8、MySQL 8.0.46、Redis 6.2.24、RabbitMQ 4.3.6；临时 Lima 2.2.1/VZ VM，Ubuntu 24.04.5/Linux 6.8.0-142、Docker 29.9.2/cgroup v2、2 vCPU/3GiB。镜像按 `isolation-environment.json` 中不可变 sha256 ID运行，基础镜像摘要及第三方包校验见 `runtime-provenance.json`。这些是环境记录，不是性能指标。

230 项为本次实际选中执行的测试；默认标注 infrastructure 的空上下文测试不计入该数字，服务启动/真实依赖由独立本机夹具证明。

## 关键物理与业务证据

- 用户 Java 实际 UID/GID 为 10000、effective capabilities 为 0；没有 Docker socket。受信 root 监督器与用户 JVM 分离，收据卷 root-only，用户无法伪造资源/退出协议。
- 实际网络连接、根文件系统/编译产物写入、私有收据写入被拒绝；临时盘写满后受控失败，两用例都看不到前一用例可写文件。
- 内存样例触发 cgroup MLE；CPU 忙线程观察到 `nr_throttled>0`；PID 补充样例先关闭子管道排除 nofile 干扰，再由受信、只读 cgroup 观察容器确认 `pids.events.max>0`。观察容器不运行参赛代码，用户执行容器始终没有宿主挂载。
- stdout/stderr 洪泛、不读取 stdin、死循环均受期限/预算限制；setsid 后代被清理。
- 两个真实沙箱 JVM 共享 1 个 slot；第二个 token 被拒绝，并发相同 token 得到同一结果；manager 重启后重放持久结果。
- manager SIGKILL 后，在没有任何 manager 存活时容器监督器仍终止执行；新 manager 启动清除容器/卷，旧 token 返回 OWNER_LOST，新任务可使用回收后的 slot。
- 真实 MySQL/MQ 重复领取、worker SIGKILL/租约接管、旧 token/generation fencing、ACK 丢失重投、报名回收/取消竞争、时钟偏差、快照不变、多用户积分和重建/结算已逐项执行。
- 旧队列补验先实测 1 条 WAITING/在途消息，排空后停止旧题目服务和旧 judge，再切换可靠隔离模式。未将开关回退当作安全生产回滚，也未静默回填旧提交。

## 原始失败、修复与证据整理

所有尝试原记录保留；只有表中最终对应运行计入 PASS。

1. VM 首次 apt 初始化引用已下架的 dnsmasq 包（404）；改为同一私有 VM 中固定版本 Docker 静态发行包。初始客户端曾引用用户配置中的不可用凭据助手，随后改用独立空 CLI 配置，未修改用户 Docker 配置。
2. 首轮隔离夹具未关闭 Nacos，服务注册失败；修正测试配置后重新运行。不是成功隔离证据。
3. Docker 拒绝向只读根拷贝源码，改用专用输入卷；编译器还缺少私有收据文件初始化，直接复现 NoSuchFileException 后修复。补充镜像协议版本检查和构建完成后再取摘要的流程。
4. 双管理器测试发现关闭竞争 FileChannel 会释放进程对同一文件的 POSIX 锁，造成另一管理器误回收活跃容器；改为 JVM 内每个文件共享持久通道。新增独立 JVM 锁观察，并重新执行全部隔离及完整业务矩阵。
5. `full-matrix/` 是锁修复前主动中断的尝试，`passed=false` 保留；最终使用 `full-matrix-lock-fixed/`。旧 helper 的清理循环覆盖 case 名称，原 `matrix-cases.json` 保留，`business-results.json` 依据按序 START 事件和完全相同 JV 数组恢复名称，**不改变任何状态、事实或断言**；新入口已修复标签保存。
6. `cutover/` 中新旧判题实际已完成，但消费者统计采样滞后被当成即时值而误报；改为等待观测刷新，独立新夹具 `cutover-followup/` 通过。
7. 原 PID 创建失败探针也可能先碰到 nofile 上限，因此额外以关闭管道和读取内核 `pids.events` 的方式确认 PID 控制；不只凭返回 true 宣称 PID 限额生效。

历史 `final-acceptance-20261018/` 和 `f03-20261022/` 不修改。后端计数只统计本次日志实际运行的测试类，排除 target 中过期的基础设施上下文 XML。

## 使用与边界

配置/源码调用链见 [隔离执行器说明](../../../docs/contest/isolated-sandbox.md)和[源码阅读地图](../../../docs/contest/source-reading.md)。默认 isolated 需固定镜像、同池共享本地目录和可靠判题 token/deadline；旧版可信演示显式 native。三个业务开关仍按迁移契约默认关闭，未切换任何线上服务。

本轮不包含 Nacos、部署、压测、跨宿主物理容量、状态盘被删除/人工改写、主机断电、Docker daemon 全故障矩阵或内核逃逸安全认证。上述限制不能从 29 项本机 PASS 推断为已完成。
