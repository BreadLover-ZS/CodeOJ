# 运行、演示与交接

开发与验收版本：`bc3514261767d07cc3be7c3ab681afc894e25c02`（2026-10-22，已推送）。后续学习/交接文档更新不改变该版本的运行证据。原报告中“尚未提交”描述的是验收当时状态；历史文件与指纹保留原样。

完整启动的新入口见 [Mac 完整本机启动](local-startup.md)：`./scripts/local-stack.sh verify`，或 `serve` 前台演示。下文保留各层单独复验入口。

## 1. 接手时先核对

从仓库根目录运行 `git status --short`、`git log -2 --oneline`。先阅读[验收报告](../../outputs/validation/isolation-20261022/report.md)、[逐项矩阵](../../outputs/validation/isolation-20261022/jv-matrix.md)与[隔离配置](isolated-sandbox.md)。

| 已有证据 | 结论边界 |
| --- | --- |
| 后端 230 tests，0失败/错误/跳过 | 当轮实际选中的 27 个测试类；默认 infrastructure 标签未计入，不能以任意一次默认 test 的计数替代 |
| JV01—JV29 全 PASS、17 项隔离检查、53 项权限检查、真实 Chrome 主链 | 固定版本、本机独立 MySQL/Redis/MQ、单 Docker daemon、受控功能/故障样例 |
| 旧队列排空切换、临时环境清理通过 | 私有实例演练；不代表生产发布/回滚；原 VM、私有数据、镜像与运行时目录已删除 |
| 后置 | Nacos、部署、压测、多宿主容量、断电与内核逃逸认证 |

不要直接复用历史 JSON 中的 `/private/tmp/…` 路径：它们是已清理环境的记录。新一轮准备新环境、写新证据目录，不覆盖历史结果。

## 2. 最短可信样例演示

前提：Java 8 JDK、Maven、Python 3.11+；工具能启动子进程并执行 `ps` 采集 RSS。首次 Maven 解析需要网络。脚本从任意目录启动均可，下面统一在仓库根目录执行。

```bash
./scripts/demo-local-judge.sh
./scripts/demo-local-contest.sh
```

第一个输出 5 条 JSON，依次为 AC、WA、CE、TLE、OUTPUT_LIMIT；第二个输出报名 CONFIRMED、executionStatus=COMPLETED、verdict=AC、FINISHED/final=true、submissions=1、attempts=1、resultEvents=1。第二个通过 H2、真实 Java 编译/执行和手动 Outbox 分发演示快照、幂等、计分及结算，**不启动 MySQL、Redis、RabbitMQ 或 HTTP 服务**。H2 依赖缺失时按脚本提示解析测试依赖，不能用它替代真实依赖验收。

2026-10-22 交接复核：两个脚本在允许 RSS 采样的本机环境通过。工具受限沙箱内 `ps` 返回 operation not permitted 时，样例会报 RESOURCE_METRICS_UNAVAILABLE；这是缺少采样能力，不能填 0、删掉资源检查或宣称 AC。脚本只运行仓库内可信代码，不接收陌生参赛程序。

建议演示时按“提交幂等返回同一 ID → 一次有效 attempt → AC 结果 → 最终榜单”解释 JSON；再打开真实验收报告，展示消息故障和容器隔离的独立证据。

## 3. 真实依赖与浏览器主链

该入口限定 macOS arm64。需要 Java 8、Maven、Python 3.11+、Node/npm、C 编译工具、Chrome 和可加载的 Playwright 包；首次下载/编译需要网络及临时磁盘空间。固定依赖 URL/摘要见 [prepare-runtimes.py](../../scripts/local/prepare-runtimes.py)，不承诺在其他平台或未来下载源失效时直接运行。

```bash
npm --prefix codeoj-frontend ci
PLAYWRIGHT_MODULE=/absolute/path/to/playwright PLAYWRIGHT_BROWSER_CHANNEL=chrome ./scripts/test-native-stack.sh
```

将 Playwright 路径换成实际包目录。脚本以 Java 8 打包并构建前端，使用临时 MySQL/Redis/MQ、静态服务发现、五个服务和真实 Chrome，自动注册登录、报名、提交、查榜、故障恢复和结算。默认输出 `outputs/validation/native-时间/`；可通过 `CODEOJ_NATIVE_EVIDENCE` 指定新路径。

这是显式 **native 可信样例** 入口，默认没有隔离容器；它证明真实依赖业务链，不单独证明全部 JV。浏览器覆盖登录、管理对账、Monaco 提交及榜单；创建/发布比赛和首次报名由真实 HTTP 夹具完成。脚本结束/异常时清理所属服务与数据，外层删除临时运行时；不要借用现有数据库。

## 4. 重新执行隔离及完整验收

先准备自己的私有 Linux Docker/cgroup v2 环境，按[隔离说明](isolated-sandbox.md)构建镜像并记录不可变 ID、私有 Unix socket 与 CLI 绝对路径。使用专用空 CLI 配置；受信管理器可调用 socket，用户容器不挂载 socket。VM 的建立和删除目前由外层管理，仓库脚本不自动安装 Lima 或管理用户其他 VM。

创建私有 JSON（替换占位值；不提交私有配置）：

```json
{"docker":"/absolute/path/to/docker","endpoint":"unix:///private/socket/docker.sock","image":"sha256:<64 hex>","dockerConfig":"/private/empty/docker-config"}
```

在仓库根目录执行；下面的私有目录和证据路径必须替换成新目录，不能覆盖既有验收。Java 8 的环境同时供服务启动与构建使用。

```bash
export CODEOJ_JAVA8_HOME="$(/usr/libexec/java_home -v 1.8)"
python3 -B scripts/local/prepare-runtimes.py --root /private/tmp/codeoj-new-runtimes
env JAVA_HOME="$CODEOJ_JAVA8_HOME" PATH="$CODEOJ_JAVA8_HOME/bin:$PATH" mvn -B -f codeoj-backend/pom.xml -DskipTests package
npm --prefix codeoj-frontend ci
env VUE_APP_API_BASE_URL= npm --prefix codeoj-frontend run build

python3 -B scripts/local/check-isolation.py --docker /absolute/path/to/docker --endpoint unix:///private/socket/docker.sock --image sha256:REPLACE_WITH_64_HEX --docker-config /private/empty/docker-config --evidence /new/evidence/probes
# all 默认执行 15 项；CPU/PID 内核证据必须分别运行，不能仅凭 all 宣称 17 项。
python3 -B scripts/local/check-isolation.py --docker /absolute/path/to/docker --endpoint unix:///private/socket/docker.sock --image sha256:REPLACE_WITH_64_HEX --docker-config /private/empty/docker-config --focus cpu --evidence /new/evidence/cpu
python3 -B scripts/local/check-isolation.py --docker /absolute/path/to/docker --endpoint unix:///private/socket/docker.sock --image sha256:REPLACE_WITH_64_HEX --docker-config /private/empty/docker-config --focus pid --evidence /new/evidence/pid

PLAYWRIGHT_MODULE=/absolute/path/to/playwright PLAYWRIGHT_BROWSER_CHANNEL=chrome python3 -B scripts/local/run-final-acceptance.py --runtimes /private/tmp/codeoj-new-runtimes --environment /private/isolation-environment.json --evidence /new/evidence/full-matrix
CODEOJ_SANDBOX_MODE=isolated CODEOJ_DOCKER_BINARY=/absolute/path/to/docker CODEOJ_DOCKER_ENDPOINT=unix:///private/socket/docker.sock CODEOJ_ISOLATION_IMAGE=sha256:REPLACE_WITH_64_HEX python3 -B scripts/local/check-current-permissions.py --runtimes /private/tmp/codeoj-new-runtimes --evidence /new/evidence/permissions
python3 -B scripts/local/check-judge-cutover.py --runtimes /private/tmp/codeoj-new-runtimes --environment /private/isolation-environment.json --evidence /new/evidence/cutover
```

REPLACE_WITH_64_HEX 与 JSON 中的尖括号是占位说明，运行前必须替换。PID 检查使用受信只读观察容器挂载 VM cgroup，观察容器从不运行用户代码。完整业务入口依赖仓库历史目录中的只读 Python 夹具，不能只拷贝 `scripts/local/`；`--skip-baseline` 仅排查使用，不能当完整浏览器验收。

这些入口不自动重建全套 JV 汇总：逐项阅读新 JSON/日志，再按[矩阵](../../outputs/validation/isolation-20261022/jv-matrix.md)合并业务、隔离、权限及切换证据，分别保留 PASS/FAIL/BLOCKED/UNTESTED。230 项 Maven 回归还包括显式启用的真实 Redis+H2 与 native observation 测试，不包含在上面的 `-DskipTests package` 中。

如需复现同口径后端回归，从新运行时 `runtimes.json` 的 `paths.redis` 取得本轮 Redis 可执行路径，替换下面占位值。在允许本地监听和 RSS 观测的环境运行，保存本轮日志；不借用既有 Redis，也不添加跳过失败用例的选项。

```bash
env JAVA_HOME="$CODEOJ_JAVA8_HOME" PATH="$CODEOJ_JAVA8_HOME/bin:$PATH" mvn -B -f codeoj-backend/pom.xml -Dcodeoj.real-redis=true -Dcodeoj.redis-server=/absolute/path/to/private/redis-server -Dcodeoj.native-observation=true clean package
```

测试自行创建独立 Redis/H2 和可信原生样例；它仍不能替代上面的真实 MySQL/MQ、容器与浏览器验收。测试数量随源码变化而变化，按新报告实际执行类和失败/跳过数记录。

## 5. 配置、观测与退出

| 检查点 | 要求 |
| --- | --- |
| 业务版本 | 初始化业务表并按顺序应用 V001—V004；question/judge 同时启用 RELIABLE_JUDGE_ENABLED；ASYNC_REGISTRATION_ENABLED、SCOREBOARD_ENABLED 按迁移契约启用。临时夹具自行处理这些配置，不能照搬为在线切换步骤 |
| 默认行为 | 三个业务开关默认 false，但沙箱默认 isolated；旧无 token/deadline 的调用不兼容 isolated，不能仅启动默认 YAML 就期待旧链可用 |
| 执行池 | CODESANDBOX_ISOLATION_IMAGE 固定摘要、CODESANDBOX_STATE_DIRECTORY 专用持久本地目录（权限700）；同池同 daemon、同目录、同资源策略；不能 live 删除 journal/锁文件 |
| 内部调用 | INNER_AUTH_SECRET、沙箱远程密钥在调用两端一致；业务凭据只在受信服务，不能进入用户容器 |
| 持久事实 | submission_execution/judge_attempt、reliable_outbox/publish_attempt、报名请求/资格、结果回执、榜单版本；以对应源码/迁移定义为准 |
| 通过表现 | COMPLETED + AC 等 verdict；非 AC 的用户结果也可 COMPLETED。平台故障可 RETRY_WAIT/NEEDS_ATTENTION，不能写成 WA |
| 结算表现 | pendingCount=0、projectionPendingCount=0，FINALIZING 后重建核验，FINISHED/finalVersion；不能只看页面成功提示 |
| 清理 | 先停止本轮所属服务，确认本轮容器/卷和进程已回收，再删除私有 VM、运行时与私有配置；只删能证明属于本轮的新缓存，保留脱敏证据 |

仓库 Compose 是历史环境模板，未完成本轮隔离后端部署装配或 Nacos验收，不能当作新版一键启动交付。准备正式部署或负载验证时，另开明确范围和验收条件；本轮不追加这些工作。
