# 隔离执行器与持久化回收

本轮以 `b070aac` 为业务基线，新增隔离执行器。默认 `codesandbox.mode=isolated`；未配置固定镜像或隔离后端不可用时启动失败，不自动降级。`native` 仅用于显式选择的可信本机样例。

隔离链路使用可靠判题契约：题目服务和判题服务应用 V001—V004 迁移并启用 `codeoj.reliable-judge.enabled=true`，由 worker 提供 token/绝对期限。旧版无执行身份的调用在 isolated 模式被拒绝，不自动生成另一身份冒充重试去重；可信旧版演示需显式选择 native。比赛报名/榜单开关仍按既有迁移步骤启用，本轮不自动切换线上开关或在途队列。

## 执行边界

一次 attempt 使用独立编译容器及产物卷；每个用例使用新容器、新临时盘、新私有收据卷，编译产物只读挂载。Docker CLI 仅由受信服务调用，参数使用列表传递；不拼接参赛代码为 shell 命令，不向用户容器挂载 Docker socket、宿主业务目录、服务配置或凭据。

| 约束 | 实现 |
| --- | --- |
| 非特权用户程序 | 独立 Java 子进程 UID/GID 10000，effective capabilities 为 0，`no-new-privileges` |
| 受信监督器 | Python 进程保留容器内 root，只具 SETUID/SETGID/KILL，负责降权、期限、namespace 内后代清理及私有收据；用户 Java 无法改写它的收据或向它发信号 |
| 网络/文件 | `--network none`、只读根、只读产物；独立 16MiB `/tmp`，nosuid/nodev/noexec |
| CPU/PID | 默认 1 CPU、64 PID；nofile=64，单文件上限 16MiB；启动检查相应 cgroup 控制器与 seccomp |
| 内存 | 整个用例 cgroup 硬限额，memory-swap 与 memory 相等；JVM 最大堆不超过该内存值；不使用宿主进程 RSS 代替容器内存 |
| 期限 | 受信监督器独立于 HTTP/JVM 管理器限制逐阶段预算及 attempt 绝对期限；管理器也限制 CLI、输入和双流读取 |
| 后代进程 | 监督器在独立 PID namespace 内执行 kill(-1)，包含 setsid/双 fork 后代；容器最终删除前 slot 不复用 |
| 收据/指标 | root-only 收据卷保存子进程退出、期限及 cgroup `memory.peak`/`memory.events`；用户 stdout/stderr 不能伪装控制协议 |

`CGROUP_V2_PEAK_KIB` 指整个用例 cgroup 的内存峰值（含监督器、JVM 和后代），单位 KiB，不等于 Java 堆或采样 RSS。缺失指标不会填 0 或直接 AC。cgroup OOM 归 MLE，监督器确认超时归 TLE，输出超限归 OUTPUT_LIMIT；明确编译诊断归 CE，正常资源范围内用户 JVM 非零退出归 RE；引擎、收据或入口基础设施问题保留平台错误。

## 共享执行权与回收

`DurableExecutionRegistry` 在专用本地目录持久化 token、请求 SHA256、期限、slot、结果和清理状态，不持久化源代码或输入到 journal；临时源码仅在执行工作目录中。完成结果可在有限保留窗口内重放，最多保留 64 条；该容量是有界保护，不是吞吐指标。

所有指向同一执行池的管理器必须共用**同一个本地持久目录与同一 Docker daemon**；镜像、容量及资源策略不一致时拒绝启动。不要将该文件锁协议部署到 NFS/不同宿主的非共享目录，不能将两个独立目录当作同一个全局容量池。

1. 写入 slot journal 和 token journal并 fsync 后才创建物理资源，文件使用原子替换。
2. 执行期间保持 slot 文件锁；重复 token 等待原结果，内容改变拒绝，不能重新编译/运行同一 token。
3. 容器及卷以执行池/token 标签关联；正常完成先删除并确认物理对象不存在，再清除 slot。
4. 删除失败保留 REAP_PENDING 和容量占用；后台及启动扫描重试。引擎不可达或 journal 损坏时保持拒绝，不能将未知状态当作空闲。
5. 管理器被杀后 OS 释放锁，新管理器先回收旧容器/卷、记录 OWNER_LOST，再允许新 token 使用 slot；旧 token 不重跑。没有管理器时监督器仍执行期限。

锁通道在单个 JVM 内按文件共享并保持打开，避免关闭竞争通道时释放该进程的全部 POSIX 锁。[Java 8 FileLock 文档](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/FileLock.html)明确说明此平台差异；回归包含独立 JVM 的真实锁观察。

配置目录必须专用、持久且仅管理用户可读写（例如 `install -d -m 700 /var/lib/codeoj-sandbox`）。不得在运行时删除目录、policy、锁文件或 journal来“重置容量”。执行中目录丢失/人工改写不是受支持的恢复方式；应停止准入并处理该执行池物理资源。工作目录和缓存结果包含私有执行数据，不对公网提供下载。

## 构建和配置

需要 Linux Docker/cgroup v2，以及受信管理服务可调用的 Docker CLI。可以从 macOS 通过私有 Unix socket连接临时 Linux VM；本轮使用 Lima 2.2.1/VZ、Ubuntu 24.04.5、Docker 29.9.2。未安装全局 Docker Desktop，也未给用户代码开放管理 socket。

先从官方 Java 8 镜像解析并记录摘要，使用该摘要构建；下面固定的是本轮实际使用的基础镜像，不承诺未来可用性。新环境应保留自己的摘要和构建证据。

```bash
docker pull eclipse-temurin@sha256:185d4ef919b98cd195690c33f3fd93bd49c66c70f0b662e54566c0dbaebea6ce
docker build --network=host \
  --build-arg BASE_IMAGE=eclipse-temurin@sha256:185d4ef919b98cd195690c33f3fd93bd49c66c70f0b662e54566c0dbaebea6ce \
  -f codeoj-backend/codeoj-backend-codesandbox/isolation/Dockerfile \
  -t codeoj-isolation:local codeoj-backend/codeoj-backend-codesandbox
docker image inspect codeoj-isolation:local --format '{{.Id}}'
```

`--network=host` 仅用于受信镜像构建下载 Python，不用于参赛程序执行。管理器接受不可变 image ID 或 `repository@sha256`，拒绝可移动 tag；镜像须有 `codeoj.isolation.protocol=1`。CLI 使用独立空配置，不读取用户 Docker 凭据配置。

| 环境变量 | 配置要求 |
| --- | --- |
| `CODESANDBOX_MODE` | 默认 isolated；可信样例才显式 native |
| `CODESANDBOX_ISOLATION_IMAGE` | 上述构建所得完整 sha256 ID，不填 tag |
| `CODESANDBOX_DOCKER_BINARY` | Docker CLI 绝对路径 |
| `CODESANDBOX_DOCKER_ENDPOINT` | 受信 Unix socket，默认 unix:///var/run/docker.sock |
| `CODESANDBOX_STATE_DIRECTORY` | 专用持久本地目录，同池所有管理器一致 |
| `CODESANDBOX_MAX_CONCURRENT` | 1—16，同池一致，默认 2 |
| `CODESANDBOX_PIDS` / `CODESANDBOX_CPUS` | 32—256 / 0.1—1.0，同池一致，默认 64 / 1.0 |
| `CODESANDBOX_REMOTE_AUTH_SECRET` | 管理 API 与 judge 保持一致；用户容器不继承此变量 |

## 本机复验入口

先按现有 [原生依赖脚本](../../scripts/local/prepare-runtimes.py)准备 MySQL/Redis/MQ；它只支持本轮固定的 macOS arm64 依赖。准备一个**私有、可清理的 Linux Docker daemon**，并将临时 CLI、endpoint、固定镜像和空配置目录记录为 JSON：

```json
{"docker":"/absolute/path/to/docker","endpoint":"unix:///private/socket/docker.sock","image":"sha256:<64 hex>","dockerConfig":"/private/empty/docker-config"}
```

Java 8 打包完成后，执行以下命令，输出目录使用新路径；完整业务矩阵还需要已构建的前端 dist、真实 Chrome 和 Playwright。

```bash
python3 -B scripts/local/check-isolation.py \
  --docker /absolute/path/to/docker --endpoint unix:///private/socket/docker.sock \
  --image sha256:<64-hex> --docker-config /private/empty/docker-config --evidence /new/evidence/probes
python3 -B scripts/local/run-final-acceptance.py \
  --runtimes /private/native-runtimes --environment /private/isolation-environment.json \
  --evidence /new/evidence/full-matrix
```

`run-final-acceptance.py` 只读加载历史故障夹具，将所有输出路径重定向到新目录，采用已诊断的两秒 claim gate 和独立 JV15 题目/前后计数。`--skip-baseline` 只适用于排查；完整验收需运行默认的真实浏览器主链。F03 单独复验时给 `check-current-permissions.py` 设置 `CODEOJ_SANDBOX_MODE=isolated`、`CODEOJ_DOCKER_BINARY`、`CODEOJ_DOCKER_ENDPOINT` 和 `CODEOJ_ISOLATION_IMAGE`。

测试脚本 `finally` 停止拥有的 JVM/数据库实例，删除自身执行池容器、卷及私有数据；临时 VM、下载包和本轮新建的 Lima 镜像缓存需由外层停止并删除，保留脱敏证据及源码。不要清理用户其他 VM、Docker 配置或原有缓存。

## 验收边界

本机固定版本、单个 Docker daemon、共享本地登记目录、受控越界/故障样例的结果按证据报告。该边界不包含内核逃逸安全认证、任意恶意代码公开服务、跨宿主物理全局容量、状态盘被删、主机断电、Nacos、部署或压测。

初轮证据保留在 `outputs/validation/isolation-20261022/`；最近完整本机复验见[2026-10-23 矩阵](../../outputs/validation/overall-acceptance-20261023/jv-matrix.md)，版本和边界统一见[状态页](../status.md)。只有对应版本的实际执行结果才能关闭验收项。
