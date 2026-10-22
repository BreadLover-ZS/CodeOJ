# F03：旧会话撤权修复与本机复验

2026-10-22，基线 `b29bee7`。本轮已修复并在真实本机依赖下复验 F03；不等于重新执行整套 JV01—JV29。

## 问题与修复

历史验收中，管理员在 MySQL 被降为普通用户后，旧 Spring Session 中仍有 `admin`。旧 `UserFeignClient.getLoginUser` 和 `AuthInterceptor` 信任该快照，导致非题目所有者仍能读取 `judgeCase`、更新题目。历史失败证据见 [F03 原始记录](../../outputs/validation/final-acceptance-20261018/findings.json)，保留原结果。

统一改为 Session 仅提供账号 ID；每次鉴权查询现时账号：

- `SessionUserResolver` 处理本地与跨服务登录读取；缺失、格式错误、账号不存在或已逻辑删除时按未登录拒绝，当前封禁账号按无权限拒绝。
- `UserFeignClient.getLoginUser` 复用已有内部 `getById`，保持内部鉴权头及 Feign 调用链；查询失败向上传播，禁止回退 Session 的旧角色。
- `AuthInterceptor` 调用上述现时账号查询，再校验注解指定角色；用户服务的本地登录、可空登录和基于请求的管理员判断使用同一规则。
- `isAdmin(User)` 仍是对传入账号的纯判断，调用方必须传本次查询结果。已检查全部生产调用点：题目控制器从更新后的 `getLoginUser` 取得账号；提交列表从同一入口传入账号，并保留可靠判题分支原有的 DB 二次核验。

不改写 Session 用户快照，不创建匿名 Session；现时权限无需重新登录即可生效。题目所有者仍按既有业务规则访问、编辑自己的题目。

## 同类入口审计

| 入口 | 修复覆盖及证据 |
| --- | --- |
| 普通题目 `/get`、`/edit`、`/delete` | 基于现时账号做所有者/管理员判断；旧管理员非所有者均拒绝，正常所有者仍可读取和编辑 |
| 普通题目 `/update`、`/list/page` | `@AuthCheck` 查询现时角色；旧管理员全部拒绝 |
| 用户 `/add`、`/delete`、`/update`、`/get`、`/get/vo`、`/list/page` | 同一权限切面；真实 HTTP 已逐个验证旧管理员被拒绝，包括服务直连 |
| 判题运维 `/admin/operations`、`/{id}`、`/{id}/retry` | 注解查询现时角色，并保留原有仓储账号检查；三个入口均验证拒绝 |
| 比赛管理、成绩及报名管理 | 入口统一读取现时账号，保留现有事务内账号检查；本轮实测比赛管理撤权对照，不声称逐个重跑全部成绩/报名接口 |
| 提交详情、旧提交分页 | 撤权后不能读取他人提交详情；分页仍可查看公开信息但不返回他人源码，所有者仍能读取自己的源码 |
| 题目新增、本人资料、普通提交 | 当前封禁账号的旧会话被统一拒绝；已删除账号读取本人资料或原有题目按未登录拒绝 |
| 内部用户查询 | 经网关和服务直连均继续拒绝缺少内部鉴权头的调用 |

## 验证结果

| 层次 | 本轮结果 | 边界 |
| --- | --- | --- |
| 鉴权回归 | 新增 14 项通过：跨服务登录读取与真实 AOP 代理 9 项、本地用户服务 5 项 | Mock 用户查询/本地服务；不代替真实依赖 |
| Java 8 全量构建 | `mvn -o -B -f codeoj-backend/pom.xml package` 成功 | 默认关闭的依赖测试需另行启用 |
| 完整本机后端回归 | 26 个测试类，222 tests / 0 failures / 0 errors / 0 skipped | 启用 30 项真实 Redis+H2 测试和 2 项原生观测；默认排除的空 Spring 基础设施上下文测试不计入这 222 项 |
| 真实权限链路 | 53 PASS / 0 FAIL | 临时 MySQL 8.0.46、Redis 6.2.24、RabbitMQ 4.3.6、五个服务、Spring Session、Gateway、Feign；静态实例发现 |
| 判题冒烟 | 真实普通练习经 MQ、judge、远程原生 Java 沙箱返回 AC | 单个受控样例，不是判题全故障矩阵重验 |

真实权限夹具通过管理 API 完成提权、撤权、封禁、解封和逻辑删除，全程保持原 Cookie；没有重新登录刷新 Session。另实测用户服务停止期间读取/更新失败且无旧权限回退、恢复后撤权仍生效、重新授权后原会话恢复访问。拒绝的写操作没有修改题目或创建越权用户。

证据目录：[f03-20261022](../../outputs/validation/f03-20261022/report.md)。主要文件：`native/permission-results.json`、`native/summary.json`、`native/run.json`、`backend-test-summary.json`、`backend-regression-opt-in.log`。

## 复验命令

使用 Java 8，在临时目录准备固定运行时。下列 `task_tmp` 用于可删除的下载/解压目录，`task_evidence` 必须使用新的证据目录。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
task_tmp="$(mktemp -d /private/tmp/codeoj-f03-runtimes.XXXXXX)"
task_evidence="outputs/validation/f03-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$task_evidence"
trap 'rm -rf "$task_tmp"' EXIT
python3 scripts/local/prepare-runtimes.py --root "$task_tmp/runtimes"
mvn -o -B -f codeoj-backend/pom.xml package
mvn -o -B -f codeoj-backend/pom.xml \
  -Dcodeoj.real-redis=true \
  -Dcodeoj.redis-server="$task_tmp/runtimes/redis/redis-6.2.24/src/redis-server" \
  -Dcodeoj.native-observation=true test
python3 -B scripts/local/check-current-permissions.py \
  --runtimes "$task_tmp/runtimes" --evidence "$task_evidence/native"
```

运行时准备需要联网；测试需要本机端口权限。夹具只修改自身临时数据库，`finally` 停止服务并删除数据库/配置；外层删除运行时。本轮构建首次受沙箱端口限制，获准在本机重跑后通过；原受限日志保留，不能把它归为产品失败或删除后声称所有尝试成功。

## 验收结论与限制

F03 在上述本机权限场景内关闭。JV14 中本次复验的撤权缺陷及列出的同类入口已通过；原始矩阵仍保留 `26 PASS / 1 FAIL / 2 UNTESTED` 的历史结论，本轮不合并为新的全量矩阵。

JV08 的完整隔离、JV25 的跨管理器/任意进程树物理回收，以及 Nacos、部署和压测仍未验证。本次鉴权只保证请求鉴权时查询当前账号，不提供已开始请求与并发撤权之间的线性化保证。需要现时角色的跨服务请求现在依赖用户服务可用性；失败时拒绝访问，旧接口仍沿用既有 HTTP 200 + 非零业务错误码，新可靠性接口按原有 Advice 返回相应 HTTP 错误。
