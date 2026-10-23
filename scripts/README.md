# 运行与验证脚本

从仓库根目录执行 Shell 入口。完整前提、私有参数与清理步骤见[运行交接](../docs/contest/handoff.md)，已有运行结果见[状态页](../docs/status.md)。脚本路径保留原样。

## 按目的选入口

| 入口 | 环境与作用 | 结果边界 |
| --- | --- | --- |
| [local-stack.sh](local-stack.sh) | Mac arm64；`verify` 全构建/启动/验证/清理，`serve` 保持前台至 Ctrl+C；可加 `--browser` | 真实依赖与隔离判题启动 smoke，不能当全部 JV |
| [demo-local-judge.sh](demo-local-judge.sh) | Java 8/Maven；可信 native 的 AC/WA/CE/TLE/OUTPUT_LIMIT 样例 | 不启动真实数据库、MQ 或容器 |
| [demo-local-contest.sh](demo-local-contest.sh) | H2、真实可信 Java 与手动事件分发的报名/比赛/结算样例 | 不证明真实 MySQL/Redis/MQ/HTTP |
| [test-registration-local.sh](test-registration-local.sh) | Java 8、真实 Redis 与 H2；可用 `REDIS_SERVER` 指定二进制，或 `BUILD_TEST_REDIS=true` 临时构建 | Maven 回归；消息替身与组件测试不替代完整服务链 |
| [test-native-stack.sh](test-native-stack.sh) | Mac arm64 临时 MySQL/Redis/MQ、五个服务、真实 Chrome；需 Playwright/Chrome | 默认 native 可信样例链；完整隔离复验另见下表 |

## 完整复验的组成

这些 Python 入口由外层准备和管理临时环境，均需新证据目录与专属实例。参数命令见[交接第 4 节](../docs/contest/handoff.md#4-重新执行隔离及完整验收)，不要直接套用旧 JSON 里的临时路径。

| 文件 | 职责 |
| --- | --- |
| [prepare-runtimes.py](local/prepare-runtimes.py) | 下载/校验固定原生依赖，解包到调用者的私有目录 |
| [private-isolation.py](local/private-isolation.py) | 私有 Lima/Docker/cgroup v2 环境生命周期；由完整启动入口调用 |
| [check-isolation.py](local/check-isolation.py) | 双沙箱 JVM、cgroup/网络/文件边界、token/slot 与 SIGKILL 回收；当前 `all` 为 16 项，包含 CPU/PID 内核观测 |
| [run-final-acceptance.py](local/run-final-acceptance.py) | 真实基线/浏览器及 25 个业务分组；含历史夹具加载，`--skip-baseline` 只用于排查 |
| [check-current-permissions.py](local/check-current-permissions.py) | 真实 Session/Gateway/Feign/MySQL 下的当前账号权限回归 |
| [check-registration-recovery.py](local/check-registration-recovery.py) | 真实 V005、旧快照、Lua 部分写入、回收/升级及准入上限 |
| [check-judge-cutover.py](local/check-judge-cutover.py) | 私有旧队列排空、新可靠隔离通道切换与历史记录保留 |
| [run-native-stack.py](local/run-native-stack.py) | 真实依赖/服务编排及可复用基类；直接入口默认可信 native |

## 编排、页面与测试工具

[start-stack.py](local/start-stack.py) 是 `local-stack.sh` 的实现；[verified_download.py](local/verified_download.py) 处理固定摘要和完整响应校验；[serve-frontend.cjs](local/serve-frontend.cjs) 提供本机静态页面与 Gateway 代理。

[startup-real-browser.cjs](validation/startup-real-browser.cjs) 和 [contest-real-browser.cjs](validation/contest-real-browser.cjs) 使用真实 HTTP。后者保留部分历史 native 标签，实际执行模式以当轮 run.json 与镜像配置为准。[contest-browser-smoke.cjs](validation/contest-browser-smoke.cjs) 使用页面请求替身检查前端契约，不能作为真实服务证据。`demo/` 中的 Java 文件是上述演示脚本的可信输入。

编排/下载/清理契约测试：

```bash
python3 -B -m unittest discover -s scripts/local/tests -v
```

这些测试不启动真实 VM。[历史 Python 夹具依赖与文件保留规则](../docs/project-files.md#历史路径中的可执行依赖)说明为什么不能移动 `outputs/validation/final-acceptance-20261018/`。完整本机验收同时需要后端/前端构建、业务、隔离、权限、报名专项、切换与独立清理证据。
