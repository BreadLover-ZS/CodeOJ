# F03 修复复验记录

日期：2026-10-22（Asia/Shanghai）。源码基线：`b29bee7`；验证对象为该基线上的本轮改动，文件指纹见 `source-fingerprints.json`。

**结果：F03 本机复验通过；53 项真实权限检查通过，222 项后端测试通过。** 本轮不是全量 JV01—JV29 或生产验收。

## 结果与证据

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| Java 8 全模块打包 | PASS | `backend-regression-local.log` |
| 后端完整本机回归 | PASS：222 tests / 0 failures / 0 errors / 0 skipped，26 个类 | `backend-regression-opt-in.log`、`backend-test-summary.json`；只统计本轮实际运行的 XML 类，排除 target 中历史报告 |
| 新增鉴权回归 | PASS：9 项跨服务/AOP、5 项本地用户服务，已计入 222 项 | `CurrentAccountAuthorizationTest`、`CurrentUserServiceTest` |
| 真实 HTTP 权限检查 | PASS：53 / FAIL：0 | `native/permission-results.json`、`native/summary.json` |
| 真实练习判题 | PASS：AC | `native/run.json` 中 `REAL_PRACTICE_JUDGE_AC` 事件 |
| 打包内容对应当前构建 | PASS | `native/artifact-checks.json`：五个服务类文件及依赖 jar 校验 |
| 临时实例停止与数据库清理 | PASS | `native/run.json`：`passed=true`、`temporaryDataRemoved=true` 和停止事件；额外进程/目录核查见 `cleanup.json` |
| 全量 JV、隔离、Nacos、生产、压测 | UNTESTED | 不从本次结果推断 |

真实依赖：MySQL 8.0.46、Redis 6.2.24、RabbitMQ 4.3.6、Erlang 28.5.0.8，五个真实服务，静态实例发现；未启动浏览器/Nacos，不是完整历史验收重跑。权限请求通过 Gateway/Feign/Spring Session 及部分服务直连执行，无 Mock 数据源。

## 关键事实

- 同一登录 Cookie 全程复用，角色变更走真实用户管理 API，不通过重新登录替换 Session。
- 非所有者管理员撤权后，题目隐藏用例读取、更新、编辑、删除、管理分页均拒绝；六个用户管理接口与三个判题运维接口也拒绝。
- 正常管理员与题目所有者仍有原权限；提权、解封和重新授权可在原 Session 生效。
- 封禁所有者不能使用未标注注解的题目新增/编辑/删除/提交或本人资料接口；逻辑删除后按未登录拒绝。
- 用户服务停止期间不回退缓存角色；拒绝的更新未修改数据库，服务恢复后撤权仍生效。
- 他人提交详情拒绝；旧提交分页隐藏他人源码，所有者仍可查看自己的源码。

## 原始失败与执行边界

`red-tests.log` 是修复前测试：5 项直接登录读取用例失败；另 4 项新 AOP 测试因当时尚无待注入的查询字段而报夹具错误，不把这 4 项当作独立复现。修复后 9 项全部通过，见 `focused-tests.log`。

`backend-regression.log` 首次在沙箱执行，4 项已有 HTTP 测试因 `java.net.SocketException: Operation not permitted` 无法绑定端口；不是本次产品回归。获准本机运行后构建成功。`backend-regression-local.log` 默认有 32 项条件测试跳过，因此随后准备真实 Redis 并开启观测开关，以 `backend-regression-opt-in.log` 的 222 项无跳过结果作为最终计数。

2026-10-18 的原始失败证据保持原样。F03 在本次权限范围内关闭；不把不同时间、不同边界的运行拼成新的整套 JV PASS。JV08/JV25 等未验证项保持原边界。

完整修复说明、入口审计、复现命令和依赖可用性限制见 [开发记录](../../../docs/contest/fix-current-permissions.md)。复验脚本为 [check-current-permissions.py](../../../scripts/local/check-current-permissions.py)。
