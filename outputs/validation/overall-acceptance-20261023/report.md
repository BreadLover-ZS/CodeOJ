# CodeOJ 整体本机验收（2026-10-23）

结论：**PASS，限当前 Mac 全面本机验收范围**。基线提交 `19f77fc11c50aac34fa189a7b39bcb27fad9b139`。本轮重新构建、重新创建临时数据库/账号与 Linux VM，没有引用历史 PASS 数字充当新结果。业务源码与验收断言未修改，证据尚未提交。

| 范围 | 本轮结果 | 证据 |
| --- | --- | --- |
| Java 8 完整 clean/package 回归 | 248 tests，27 类，0 失败/错误/跳过 | [backend-tests.json](backend-tests.json)、[构建日志](backend-regression.log) |
| 前端生产构建 | 通过；最终构建明确配置临时 Gateway 代理 | [最终构建日志](frontend-build-relative.log)、[产物指纹](frontend-artifacts-relative.json) |
| 启动/下载/清理脚本测试 | 23 PASS；这是脚本契约测试 | [日志](startup-unit-tests.log) |
| 真实依赖完整业务链 | MySQL 8.0.46 / Redis 6.2.24 / RabbitMQ 4.3.6；V001—V005；五个服务与第二 judge | [运行收据](business-matrix-02/native/run.json) |
| Chrome 与最终结算 | 8 项浏览器检查通过、pageErrors=[]；真实 AC/WA/CE、FINALIZING→FINISHED | [浏览器日志](business-matrix-02/native/browser.log)、[最终榜单](business-matrix-02/native/final-scoreboard.json) |
| 故障/并发业务分组 | 25/25 PASS，含 1 个准备夹具组、24 个业务组 | [业务矩阵](business-matrix-02/matrix-cases.json) |
| Linux cgroup v2 隔离 | 16/16 PASS，包含 CPU/PID 内核观测、网络/文件/内存边界、SIGKILL 回收及跨管理器去重 | [检查结果](isolation-probes/isolation-results.json)、[运行时来源](runtime-provenance.json) |
| 当前账号权限 | 53/53 PASS；同一 Session 降权/提权、封禁/删除、跨服务和隐私入口 | [权限结果](current-permissions/permission-results.json) |
| 报名恢复专项 | 真实 V005/唯一索引、旧快照、Lua 部分写入、单次回收、旧格式升级、容量上限及终态收敛通过 | [事实收据](registration-recovery/registration-facts.json) |
| 旧队列切换演练 | 排空旧在途消息、停止生产者后切换、新隔离链 AC、旧记录不改写 | [切换结果](judge-cutover/cutover-results.json) |
| JV01—JV29 | 29 PASS / 0 FAIL / 0 UNTESTED | [逐项矩阵](jv-matrix.md) |
| 清理与交接 | 自有 VM/运行时目录删除，独立核对进程、端口与数据目录；213 个文档文件链接无缺失 | [清理](cleanup.json)、[独立核对](independent-cleanup-audit.json)、[链接检查](document-links.json) |

## 首轮失败与修正

首轮 `business-matrix` 的浏览器登录页等待超时。原因是本轮自定义验收调度器遗漏 `VUE_APP_API_BASE_URL=`，使构建沿用默认 `http://localhost:8101`；临时 Gateway 使用随机端口。仓库已有启动入口原本已设置该变量，本轮没有修改产品逻辑。按现有启动文档重新构建后，在新数据库和 `business-matrix-02/` 完整重跑基线、浏览器及 25 组业务断言，通过结果才用于上表。原失败日志与原始 stages 保留。[原因与修正](fixture-correction.json)、[重跑收据](business-rerun.json)。

## 非阻塞问题

前端仍有 21 条 ESLint 警告、约 5.02 MiB 入口体积告警、Browserslist 旧数据及 Node API 废弃提示。本轮构建和浏览器功能通过，尚无加载性能验收结论。历史交接说明保留了旧隔离数量/分步执行提示；本轮按当前脚本实际输出计为 16 项，CPU/PID 内核观测均已执行，不沿用历史“17 项”的计数。

## 未验证边界

按已确认范围，真实 Nacos、目标环境部署、生产发布/回滚、压测与长期稳定性继续后置。当前证据针对 Mac + 单个临时 Linux Docker daemon 的功能和故障样例，不证明多宿主容量、断电恢复或内核逃逸防护。排名精确并列使用显式 acceptedAt 数据夹具；跨截止完成另有真实时间证据。空 infrastructure context 测试由项目默认标签配置排除，248 项不包含这些测试；真实依赖由独立运行验证。DianPing 不属于本轮项目验收。

切换演练的旧通道显式使用 native 执行仓库内可信样例，切换后的新通道使用 isolated；旧通道样例不用于证明隔离安全。完整业务链的浏览器日志有历史文案“remote native AC”，本轮实际模式以 run.json 的 sandboxMode=isolated、镜像 ID 和隔离证据为准。

## 复核入口

先检查 [summary.json](summary.json) 的 checks、[source-provenance.json](source-provenance.json) 的提交/指纹，再读取各类事实与 [JV 矩阵](jv-matrix.json)。下载摘要与镜像 ID 分别保留在 native/runtime provenance 文件。临时路径仅为已清理环境的历史记录，不可直接复用。[run_overall.py](run_overall.py) 是初始调度过程的原样记录；[rerun_business.py](rerun_business.py) 记录本轮修正后的继续执行过程，二者均不应直接覆盖本目录重跑。新验收须使用新证据目录与新临时环境。
