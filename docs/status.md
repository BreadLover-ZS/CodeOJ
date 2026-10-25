# 当前项目状态

更新：2026-10-25。最近一次完整本机验收的源码基线仍为 `19f77fc11c50aac34fa189a7b39bcb27fad9b139`；验收材料归档提交为 `bbfd4af169a70cc180d583249deeacc25e0bc00d`。此后发生了下述报名修复；不能把旧版本的完整本机验收直接当成本轮源码的全栈结果。

## 2026-10-25 后续修复与复查

- 修复并发对账：旧请求始终使用自己的版本，失败处理也受 epoch/revision 约束，不再暂停更新的成功恢复或覆盖更新的安全状态。
- 修复用户页面名额口径：比赛列表和详情显示“已确认人数 / 总名额”，不把尚未确认的数据库余量视为可预留名额。
- 本轮 Java 8 全后端打包回归 255 tests、27 类，0 失败/错误/跳过；报名相关 62 项通过（包含于本轮后端回归），含新增七项并发/故障组合回归。另有首轮报名专项 61 项通过；各轮不相加。
- 前端生产构建通过；实际 Vue 模板的待确认/已确认两种数据夹具渲染通过。本轮未重跑真实 MySQL/MQ、浏览器、隔离全栈或 JV01—JV29。

修复前反例保留在[审查报告](../outputs/validation/registration-chain-review-20261025/report.md)；修复后的范围、复查及清理见[本轮报告](../outputs/validation/registration-chain-fix-20261025/report.md)。

## 2026-10-23 完整本机验收

可靠判题、固定规格与赛事快照、限额报名、Lua 异常恢复、当前账号鉴权、可重建榜单/最终结算，以及 Linux 隔离执行与持久回收均已实现，并通过上述基线的本机整体验收。

| 验证层 | 该轮结果 | 依据 |
| --- | --- | --- |
| 后端构建与回归 | Java 8 clean/package；248 tests、27 类，0 失败/错误/跳过 | [测试明细](../outputs/validation/overall-acceptance-20261023/backend-tests.json) |
| 前端 | 生产构建通过；真实 Chrome 8 项检查，pageErrors=[] | [最终构建](../outputs/validation/overall-acceptance-20261023/frontend-build-relative.log)、[浏览器](../outputs/validation/overall-acceptance-20261023/business-matrix-02/native/browser.log) |
| 真实业务与故障 | 25/25 分组通过，含 1 个夹具准备组、24 个业务组 | [业务结果](../outputs/validation/overall-acceptance-20261023/business-matrix-02/matrix-cases.json) |
| 完整 JV 矩阵 | JV01—JV29：29 PASS / 0 FAIL / 0 UNTESTED | [逐项矩阵](../outputs/validation/overall-acceptance-20261023/jv-matrix.md) |
| 隔离与资源回收 | 16/16 项通过，含 CPU 节流/PID 内核事件、双管理器与 SIGKILL | [隔离结果](../outputs/validation/overall-acceptance-20261023/isolation-probes/isolation-results.json) |
| 当前账号权限 | 53/53 项通过，同一 Session 下刷新角色与账号状态 | [权限结果](../outputs/validation/overall-acceptance-20261023/current-permissions/permission-results.json) |
| 报名恢复 | V005、旧快照、Lua 部分写入、单次回收、旧账本升级及准入上限通过 | [专项事实](../outputs/validation/overall-acceptance-20261023/registration-recovery/registration-facts.json) |
| 旧队列切换 | 旧在途消息排空、暂停生产者后切换，新隔离链 AC、旧记录保留 | [切换结果](../outputs/validation/overall-acceptance-20261023/judge-cutover/cutover-results.json) |
| 启动脚本契约 | 23 项测试通过；使用替身检查编排/下载/清理契约 | [测试日志](../outputs/validation/overall-acceptance-20261023/startup-unit-tests.log) |
| 临时环境清理 | VM、运行时与私有数据移除；独立核对 63 个端口关闭，无遗留测试进程 | [独立清理核对](../outputs/validation/overall-acceptance-20261023/independent-cleanup-audit.json) |

完整结论见[本轮报告](../outputs/validation/overall-acceptance-20261023/report.md)与[机器收据](../outputs/validation/overall-acceptance-20261023/summary.json)。MySQL 8.0.46、Redis 6.2.24、RabbitMQ 4.3.6 为临时独立实例；五个服务使用静态发现，执行沙箱使用单个临时 Linux Docker/cgroup v2 环境。后端计数不包含默认排除的 infrastructure 空 context 测试；真实服务链另行验证。

## 尚未验收与遗留项

真实 Nacos、目标环境部署、生产发布/回滚、压测与长期稳定性继续后置。多宿主容量、断电恢复及内核逃逸认证也无本轮证据。前端仍有 21 条 ESLint 警告、约 5.02 MiB 入口体积告警及工具链废弃/旧数据提示，功能通过不等于加载性能通过。

首次整体验收尝试的前端构建遗漏临时 Gateway 代理变量，浏览器超时；修正验收配置后在新数据库完整重跑，未改变业务断言。原始失败、根因与成功轮均保留，详见[修正记录](../outputs/validation/overall-acceptance-20261023/fixture-correction.json)。

## 版本与历史读法

- `19f77fc`：报名旧快照/Lua 恢复修复及学习文档同步；本次整体验收源码基线。
- `bbfd4af`：归档 2026-10-23 整体验收材料，未改变业务源码。
- 2026-10-22 隔离完整复验的 230 tests、17 项隔离/恢复检查属于历史执行口径；当前脚本默认 `all` 已包含 CPU/PID 观察，本轮实际输出 16 项，不叠加旧轮数字。
- 2026-10-18 报告中的 F03 FAIL、隔离 UNTESTED 等是当时结果；修复与后续复验见[历史索引](history/README.md)和[证据索引](../outputs/validation/README.md)。

归档文件里的 `published=false`、“尚未提交”及临时路径记录的是验收当时快照，不是实时 Git 发布状态。发布以 Git 历史为准；当前工作区状态可运行 `git status --short` 核对。历史报告、日志、源码指纹和 SHA 清单保持原样。
