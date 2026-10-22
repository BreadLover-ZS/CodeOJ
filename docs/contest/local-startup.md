# Mac 完整本机启动

本阶段面向 macOS arm64，以私有 MySQL/Redis/RabbitMQ、五个 Java 服务、前端和临时 Linux 隔离环境交付可执行入口。复用已验收的业务/隔离实现与原生依赖编排，不更改业务规则。Nacos、远程部署与压测仍后置。

## 两种模式

在仓库根目录执行：

```bash
# 默认完整构建，启动、真实 HTTP/隔离判题验证，然后清理全部所属运行时。
./scripts/local-stack.sh verify

# 完成相同启动验证后，前台保持运行；Ctrl+C 清理并退出。
./scripts/local-stack.sh serve
```

`serve` 的页面地址和私有 `access.json` 路径显示在 LOCAL_STACK_READY 事件中。访问该文件读取随机密码和演示账号；文件权限 600，位于私有临时目录，退出时删除，不复制进证据。演示管理员只在新建私有数据库中设置。不要在 `verify` 退出后尝试打开该地址，环境已清理。

若要加测真实 Chrome：

```bash
PLAYWRIGHT_MODULE=/absolute/path/to/playwright PLAYWRIGHT_BROWSER_CHANNEL=chrome ./scripts/local-stack.sh verify --browser
```

普通启动不要求 Playwright。`--browser` 需要已安装的包与 Chrome；脚本不自动安装全局浏览器。`serve --lifetime 30` 可验证定时退出，正常交互使用 Ctrl+C。就绪后正常退出或 Ctrl+C 返回0，启动阶段中断返回130，启动/清理失败返回非零。`--skip-build` 仅排查使用，仍校验打包 class 和内嵌共享 jar；验收执行默认完整构建。

## 前提与准备行为

需要 Python 3.11+、Java 8 JDK、Maven、Node/npm、macOS C 编译工具、tar/codesign/install_name_tool、可用的 `ps`、网络以及至少 12GiB 临时空间。脚本检查 CODEOJ_JAVA8_HOME，否则使用 macOS java_home 定位 Java 8。受限工具环境若禁止监听、虚拟化或进程观察，应以获准的本机权限执行，不能关闭资源判定来绕过。

首次运行会下载并校验固定的 MySQL/Redis/RabbitMQ/Erlang/OpenSSL、Lima 2.2.1、Docker 29.9.2 和 Ubuntu 云镜像，耗时取决于网络/编译。原生包摘要沿用 [prepare-runtimes.py](../../scripts/local/prepare-runtimes.py)，隔离环境摘要见 [private-isolation.py](../../scripts/local/private-isolation.py)。下载源失效或完整响应摘要不符时失败，不切换到未验证镜像。下载先写私有 partial 文件，确认完整响应、声明长度和固定摘要后原子发布；仅对超时、短传输、意外206及明确暂时 HTTP 错误最多尝试三次，完整错误摘要和鉴权错误不重试。重试仍访问同一固定资产并校验同一摘要，日志记录状态/长度/摘要，不记录认证头；任一隔离资产失败时通知其他下载停止。

Lima 使用专用 LIMA_HOME。先将云镜像下载到私有目录并校验，再以本地文件交给 Lima，避开其全局 URL 镜像缓存；不依赖无效的 LIMA_CACHE_HOME。仅向 VM 只读挂载该临时隔离运行时目录，不挂用户主目录或业务仓库。VM 不安装全局宿主 Docker、不变更用户 Docker context/凭据或已有 VM。每轮重新创建，适合可清理的本机演示，不提供持久业务数据保留。

后端构建产物保留在仓库 target 中，Maven 使用既有依赖仓库；前端仅复制明确列出的源码/配置和 npm 锁文件，在本轮私有目录执行 npm ci/build，依赖、dist 和 npm 缓存随退出删除，不改仓库 Yarn 锁文件。私有数据库、服务配置、密码和 VM 同样在验证后清理。

## 启动顺序和就绪含义

1. 预检查、创建专用临时目录，准备并校验原生依赖。
2. Java 8 多模块 package、npm ci、同源前端构建；对运行 jar 的 class 和嵌入依赖进行字节核验。
3. 启动私有 Lima/VZ VM；静态 Docker engine 经 systemd 启动，检查 Linux/cgroup v2；以固定基础镜像构建隔离镜像并记录最终 image ID。
4. 新建私有 MySQL，顺序应用基础表和 V001—V004，再启动独立 Redis/MQ；不借用已有实例，不删除既有业务表。
5. 生成权限 600 的私有服务配置，显式 isolated 与三个业务开关 true、内部密钥一致、UTC、静态服务发现；同池使用专用持久目录。创建服务期间不能删除该目录或锁文件。
6. 启动 sandbox/user/question/judge/gateway；TCP 只作为早期等待条件，随后真实 Gateway API、注册登录/共享会话、Redis+MQ 报名和隔离 AC/WA/CE 才能完成业务就绪验证。
7. 独立回环静态服务器提供 Vue 页面并透明代理 `/api`，验证同源 API、幂等返回、一次有效 attempt、cgroup 内存指标、榜单投影与未参赛者题面边界。
8. 可选真实 Chrome 登录、管理员比赛列表、Monaco 隔离提交 AC、非参赛者榜单。没有 page.route、伪造 Cookie 或 HTTP 替身。

用例级隔离仍由 [JavaIsolatedCodeSandbox](../../codeoj-backend/codeoj-backend-codesandbox/src/main/java/com/codeoj/codeojbackendcodesandbox/service/impl/JavaIsolatedCodeSandbox.java) 执行。Docker socket 只交给受信管理器；参赛容器不挂业务配置、Docker socket 或宿主目录。CLI 的每次执行使用独立配置和显式 endpoint，不读用户 Docker 凭据。主机配置了 HTTP_PROXY/HTTPS_PROXY 时，仅将代理配置到本轮私有 daemon/受信构建；主机回环地址转换为 host.lima.internal，不修改主机代理设置或将代理变量设为用户镜像 ENV。

仅监听回环；端口随机分配。服务发现保持与前轮一致的静态配置，不能将本阶段称为 Nacos 或正式部署验收。初始化和启动验证会创建演示比赛与提交，比赛约一小时到期，不是空白数据库安装器。

## 证据与退出

默认证据目录为 `outputs/validation/startup-时间/`，也可指定尚不存在的新路径：

```bash
./scripts/local-stack.sh verify --evidence outputs/validation/startup-my-run
```

主要文件：`delivery.json`（总结果、阶段事件、清理）、`runtime-provenance.json`（包与镜像）、`application/artifact-checks.json`（运行包）、`application/startup-results.json`（业务 smoke）、可选 `browser.log` 以及诊断日志。`application/run.json` 记录应用进程、端口与数据清理，当前模式以 sandboxMode=isolated 及 delivery 总结果为准。启动 smoke 不重新执行全部 JV 或压测，不得把本轮计数套用为完整回归。

正常退出、Ctrl+C、SIGTERM 和启动失败均进入清理：停止所属进程组、归档脱敏日志并保留登记/配置、检查端口、停止删除私有 VM，确认物理执行消失后才删除临时运行时与私有配置。VM 停止/删除或进程清理失败时返回非零，保留控制目录并在 delivery 中记录 retainedPrivateRoot，不能删除活跃磁盘或报告清理成功。SIGKILL、宿主掉电不能执行 finally，需在所记录专用目录中人工核对所属资源；不要删除或 stop 用户其他 VM。

仓库的旧 Compose 文件保留为历史参考；本入口直接在 Mac 启动业务与数据服务，Linux VM 专门执行容器判题。远程 Linux/全容器部署是后续独立工作。

## 已验证结果与实现检查

2026-10-22，默认全构建 `verify --browser` 与默认全构建 `serve` 实际 Ctrl+C 均通过；真实判题、报名、榜单和 23 项编排/下载契约检查完成，全部所属临时环境清理。完整范围与原失败记录见[报告](../../outputs/validation/startup-20261022/report.md)。


清理失败保留控制数据、停止失败不删除 VM、删除后独立核验以及不影响其他目录等约束测试：

```bash
python3 -B -m unittest discover -s scripts/local/tests -v
```

这些测试使用替身验证编排失败契约，不能当真实启动证据。真实运行结论见[本阶段独立报告](../../outputs/validation/startup-20261022/report.md)，历史隔离/JV 报告保持原样。

实现参考：[Lima 固定版本配置](https://github.com/lima-vm/lima/blob/v2.2.1/templates/default.yaml)、[Lima 本地文件不缓存的实现](https://github.com/lima-vm/lima/blob/v2.2.1/pkg/downloader/downloader.go)、[Docker daemon socket 边界](https://docs.docker.com/engine/security/protect-access/)、[daemon 代理配置](https://docs.docker.com/engine/daemon/proxy/)、[构建专用代理参数](https://docs.docker.com/build/building/variables/#proxy-arguments)。
