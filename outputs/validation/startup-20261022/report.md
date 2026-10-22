# 完整本机启动交付验证

2026-10-22，基线 `b1abbb5c86ace8933c1f473f72cc7e9746365f50`。用户确认当前 Mac，使用临时 Linux 隔离环境，一条命令启动、验证后清理；Nacos、远程部署及压测继续后置。

**结果：Mac 完整启动交付通过。默认全构建的 verify + 真实 Chrome 通过；同一源码默认全构建的 serve 实际 Ctrl+C 退出通过；23 项独立契约测试通过。全部 6 轮所属临时根目录删除，未发现残留进程，全局 Docker/Lima 配置元数据及 Lima URL 缓存清单未变。**

机器可读结论见 [summary.json](summary.json)，独立清理核对见 [cleanup-audit.json](cleanup-audit.json)。

## 交付

入口 `scripts/local-stack.sh verify|serve`，编排 `scripts/local/start-stack.py`，私有 VM/固定资产 `private-isolation.py`，真实同源前端服务 `serve-frontend.cjs`，可选浏览器 `startup-real-browser.cjs`。复用 `run-native-stack.py` 的真实依赖编排、迁移、服务配置与运行包核验；`prepare-runtimes.py` 改用共享严格下载器 `verified_download.py`，固定包/摘要不变，业务 Java 与隔离协议不变。

- 默认完整构建；前端在临时源码副本里 npm ci/build，不改仓库锁文件或复制私有 .env。
- 固定包摘要、私有 Lima HOME、本地已校验云镜像避开全局 URL 缓存；私有 daemon/受信构建单独处理代理。
- 新建私有数据与随机凭据，应用 V001—V004，启用可靠判题/异步报名/榜单，显式 isolated，静态服务发现。
- TCP 之外，以真实 API、会话、报名、隔离用户 verdict、幂等、cgroup 指标、榜单/权限和可选真实 UI 操作判定启动就绪。
- 先停服务、保留 journal/控制数据，再删除 VM；物理执行确认消失后才删整个私有目录，失败留存并返回非零。

使用前提、命令及限制见[完整启动说明](../../../docs/contest/local-startup.md)。

## 尝试记录

| 运行 | 结果 | 原因/证据 |
| --- | --- | --- |
| run-01 | FAIL，清理成功 | 固定依赖和完整构建通过、VM/Docker 启动；新 daemon 缺少主机代理，拉取基础镜像超时。补齐 daemon 代理及受信 build proxy 参数 |
| run-02 | FAIL，清理成功 | 原生 OpenSSL blob 摘要不符被拒绝，未启动 VM；独立后续请求 HTTP200/11216046 bytes/摘要匹配，早次原因未复现，不放宽固定摘要、不将失败算成功 |
| run-03 | FAIL，清理成功 | 数据库迁移、五服务、真实登录/报名、前端代理完成；榜单就绪检查直接比较字符串 Long 与数字零而超时，修为按 wire 契约解析。该轮未保存中途数据库事实，不追认用户结果为 PASS |
| run-04 | FAIL，清理成功 | Lima 包摘要不符被拒绝，未启动 VM；独立获取 HTTP200/38328082 bytes/固定摘要匹配。旧下载缺少长度诊断，早次原因未确认 |
| run-05 | PASS，清理成功 | 当前固定源码，默认全构建 + verify --browser；真实 AC/WA/CE、幂等、cgroup 指标、榜单/题面边界、Chrome UI通过，见本轮 JSON/日志 |
| run-06 | PASS，实际 Ctrl+C 清理成功 | 同一源码默认全构建 + 前台 serve，真实启动检查通过并保持运行；PTY 发送 Ctrl+C，退出码0，先停应用保留登记，再删除VM及私有目录 |

run-01 的初稿还让 npm 改写了 Yarn 锁中的一条 Mac 可选依赖；核对仅该工具产生的差异后恢复，随后改为私有前端构建。原始尝试目录均保留，不能将不同运行的片段拼成一轮通过。

## 默认 verify 的实际结果

`run-05/delivery.json`、`application/run.json`、`application/startup-results.json` 和 `browser.log` 均为 passed=true；严格顺序清理成功，专用根目录删除。运行时及源码指纹分别见 runtime-provenance 和 `run-05-source.json`。

真实数据库只读投影确认 4 条提交均 COMPLETED、每条 1 attempt；结果为 2 AC（含浏览器新增提交）、1 WA、1 CE，4 条计分回执。榜单 wire 返回 projectionPendingCount 为字符串 `0`，已按契约正确解析。浏览器真实操作管理员登录/比赛列表、参赛者 Monaco 隔离 AC 和未参赛者榜单，不伪造 HTTP 或 Session。

## 实际中断与下载恢复

`run-06` 在 LOCAL_STACK_READY 后保持前台运行，再实际发送 Ctrl+C；INTERRUPTED → 所属应用停止 → APPLICATION_STOPPED_CONTROL_DATA_RETAINED → VM 删除 → privateRootRemoved=true，退出码0。`application/run.json` 的 temporaryDataRemoved/physicalRuntimeRemoved 均为 true。没有保留常驻演示服务，使用者需重新运行 serve 获得本轮地址和私有账号文件。

本轮捕获真实 HTTP200 短传输：Docker Linux 包声明 77953959 bytes，首轮只收到 77943731 bytes，相差10228 bytes。严格长度检查拒绝并清理 partial，第二次获取77953959 bytes且仍匹配原 SHA256 后才解压/使用。见 [real-download-recovery.json](real-download-recovery.json)及该轮事件；这证明新边界生效，不追溯认定前轮所有摘要失败都来自同一原因。

全局检查只比较配置元数据和缓存清单，不读取或归档用户 Docker 凭据。23项契约测试与真实服务 smoke 是不同口径。源码按每轮 source.json 绑定；本轮与上轮8个执行入口/依赖文件摘要一致。

## 独立契约测试

23 项清理/编排/代理/计数/下载协议测试通过，见 `orchestration-contract-tests.log`。覆盖停止失败不删除活跃 VM、清理失败保留控制文件、应用退出后 journal 留到物理 VM 删除、整体失败不能写成 PASS、代理回环转换/日志脱敏、Jackson Long 字符串与未知计数等。这些使用替身或本地文件验证失败契约，不作为真实依赖/虚拟化验收。新增下载边界覆盖无异常 EOF 的短传输、意外206、有限超时重试、完整错误摘要不重试、既有损坏包拒绝、鉴权失败不重试和同伴取消；验证完成前只有 partial 文件，固定摘要匹配后才发布归档。

## 范围

本阶段验证本机启动、真实业务 smoke、隔离执行接线及清理；不重新执行全套 JV01—JV29、不做负载/性能、Nacos、远程发布或断电/内核逃逸安全认证。之前 `bc35142` 的全套本机 JV 与 230 项后端回归仍是其原固定版本的证据，不能把本阶段新计数与它们直接相加。

日志保留已有的 Mac Netty DNS native 加载回退、LoadBalancer 默认缓存及前端构建警告；本阶段以实际 API/浏览器/数据库结果判定启动，不将所有日志告警都视为已修复。
