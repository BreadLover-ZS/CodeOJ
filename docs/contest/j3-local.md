# J3 比赛业务本机开发记录

日期：2026-10-18。按用户约定仅推进本机功能开发；本轮未安装 Docker、部署服务或运行压测。所有改动尚未提交/推送。

## 本轮实现

- 题目服务新增 `contest` 包，沿用现有业务数据库、Spring 事务、会话身份和 J2 可靠判题链，没有新增赛事微服务或运行时依赖。
- 草稿创建/修改、发布、取消、资格数据暂停/恢复均验证数据库中的当前管理员角色。修改要求 `expectedVersion`；取消/资格开关要求原因并写审计。发布响应丢失可重试，不能重复创建快照。
- 四个时间要求显式 ISO UTC/offset 时间，统一转为 UTC 微秒；严格满足报名开始 < 报名截止 < 开赛 < 结束。报名、提交均为左闭右开，以取得权威锁后的数据库时间裁决。页面显示本地时区。
- 发布固定题面、题号、判题规格、容量、时间和 `FIRST_AC_V1` 赛制版本。发布后实质修改需取消重建。
- `PRIVATE` 赛题在草稿载荷/比赛快照中独立存储，**从未写入普通 `question` 表**。旧练习列表/详情/提交接口因此无法读取或提交专用赛题。开赛前不向参赛用户返回题面；开赛后仅已确认报名且账户有效的用户可见。隐藏用例始终只在管理员草稿/内部规格中读取。
- `PUBLIC_TRAINING` 只接受已有公开练习题 ID，发布时读取原题并生成快照；不允许夹带另一套题面/用例，不宣称这些题属于未见题。
- 过渡报名采用 `registrationMode=DB_SYNC`，使用最终请求表和资格表。固定用户/比赛唯一身份，原键重放不再扣容量，换键明确冲突。确认函数锁请求、检查状态并条件扣减数据库余量，容量/资格/请求同事务提交。它尚未处理 Redis 预留。
- 报名、取消、提交共用比赛行锁；取消保留已确认历史，但资格立即失效。`registrationReady=false` 阻止全场新提交；某个用户请求未确认不影响其他已确认用户。
- 比赛受理先查全局用户提交幂等键，再在比赛锁下检查资格、发布版本、快照归属、账户及时间，记录 UTC `acceptedAt`，同事务写提交、执行权威记录和派发 Outbox。重放不因结束或取消丢失原提交 ID。
- 比赛与练习共用 `SubmissionAcceptance.freezeSpec/write`，执行仍走 J2 租约协议。比赛结果不增加练习题的 `submitNum/acceptedNum`，练习提交列表排除比赛记录。
- 到期扫描只转 `FINALIZING`，不会提前宣称 `FINISHED`。已受理提交继续判题；成绩投影/结算在 J5 实施。
- 新增比赛列表、参赛详情/编辑器、报名状态、判题状态、管理员草稿表单/发布/取消/资格开关。未知提交在当前页面保留原代码和原键，可在结束/取消后重试。浏览器存储只保存指纹/请求键，不存代码或隐藏用例。
- 网关新增 `/api/contests`、`/api/submissions`、`/api/admin/operations` 到题目服务现有 `/api/question` context 的别名；兼容原前缀。

## 数据兼容和开关

新增迁移：`codeoj-backend/mysql-init/migrations/V002__contest_core.sql`，依次在 V001 之后使用。新增比赛、发布快照、报名请求、资格表，以及执行侧表的快照/发布版本外键和索引。不重置旧库、不把历史记录变为比赛提交。

专用赛题没有普通题目 ID，因此其旧兼容 `question_submit.questionId=0`；权威题目身份是 `submission_execution.contestId/problemSnapshotId/publishedVersion`。公开训练赛可保留原练习题 ID，但不会混入练习计数。不得把旧 `questionId` 当成专用赛题引用，或通过旧通道重判新比赛记录。

继续使用 `RELIABLE_JUDGE_ENABLED=false` 默认关闭。真实库需先完成 V001/V002 的迁移核验，再启用 J2/J3；本轮**没有**在真实 MySQL 执行迁移或启用业务服务。禁止已有新记录后直接回退到旧写入/消费者代码。

J4 必须在比赛锁和发布版本下核对原预留身份后进入确认函数；切换报名模式时关闭并排空 DB_SYNC，不能双入口同时分配名额。当前没有可供外部调用的异步确认 HTTP 接口。

## API

以下均由网关转发到题目服务；除公开列表外要求登录。管理动作及 `admin=true` 详情/列表均重新核对数据库角色，不能靠前端菜单授权。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| POST | `/api/contests` | 创建草稿，201 |
| PUT | `/api/contests/{id}` | 修改草稿，含 expectedVersion |
| POST | `/api/contests/{id}/publish` | 发布固定版本，含 expectedVersion |
| POST | `/api/contests/{id}/cancel` | 取消，含 expectedVersion/reason |
| POST | `/api/contests/{id}/readiness` | 资格数据开关，含 expectedVersion/reason/registrationReady；恢复前核对容量总账 |
| GET | `/api/contests?page=0` | 20 条分页，隐藏草稿；管理列表加 admin=true |
| GET | `/api/contests/{id}` | 合规脱敏详情；管理详情加 admin=true |
| POST | `/api/contests/{id}/registrations` | Idempotency-Key；202，当前同步路径返回真实 CONFIRMED 后才授予资格 |
| GET | `/api/contests/{id}/registrations/me` | 本人状态、contestStatus、qualificationValid、statusUrl |
| POST | `/api/contests/{id}/submissions` | Idempotency-Key；problemSnapshotId/publishedVersion/language/code，202，返回字符串提交 ID |
| GET | `/api/submissions/{id}` | J2 本人/管理员执行状态 |

新增比赛、快照、报名请求和提交响应 ID 按字符串处理，避免 JavaScript 大整数精度丢失。时间/状态冲突与幂等冲突为409；校验400、权限403、不可见404、基础设施不可判断503。不会将查询失败伪装成未报名。

## 运行与验证

工作目录为仓库根目录。Java 使用 Corretto 8（1.8.0_504），Maven 3.9.16，H2 2.2.224（测试依赖）。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-8.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -o -B -f codeoj-backend/pom.xml -Dcodeoj.native-observation=true package
scripts/demo-local-contest.sh
cd codeoj-frontend
npm run build
```

`demo-local-contest.sh` 会打包、在临时目录提取依赖并创建内存 H2；运行 Spring 真实事务、真实 javac/java、比较器和结果写回，退出清理临时目录。数据库时间在该样例中受控推进，以便演示赛程边界。它手动读取/消费派发 Outbox，不启动 RabbitMQ、Feign、网关或网站。

浏览器检查需要已有 Playwright 包和可用浏览器，不会自动下载：

```bash
PLAYWRIGHT_MODULE=/absolute/path/to/node_modules/playwright \
PLAYWRIGHT_BROWSER_CHANNEL=chrome \
node scripts/validation/contest-browser-smoke.cjs
```

脚本临时服务本机 `dist`，全部业务 HTTP 明确 mock，退出关闭服务器和浏览器。若使用 Playwright 自带 Chromium，可不指定 channel。

| 检查 | 本轮结果 | 范围 |
| --- | --- | --- |
| 后端功能测试 | 106 通过，0 失败/错误/跳过 | 完整 reactor 105 + 新增网关契约1；4个依赖基础设施的空 Spring 上下文按既有策略排除 |
| 比赛核心 | 22 通过 | H2 + Spring真实事务，含8个用户争用2名额、取消/提交竞争、时间边界、角色、发布回滚、隐私、快照和Outbox回滚 |
| 网关契约 | 1 通过（4条路径） | 真实 YAML、Path predicate、RewritePath filter；无Nacos/后端网络 |
| 本机比赛样例 | CONFIRMED → COMPLETED / AC | 1提交、1attempt、1结果事件，余量0；H2 +真实 Java；手动派发 |
| 前端构建 | 通过 | 保留既有 lint/依赖和包体积警告 |
| 浏览器 | 6项通过、0页面异常 | 既有Chrome，无头；HTTP mock，含结束后未知提交原键/原载荷重试 |

对应 JV13—JV15 的权限、竞争和固定快照不变量有本机证据；不得据此将整个真实基础设施故障用例记 PASS。证据目录：`outputs/validation/j3-local-20261018`，包含结果、环境、命令、源文件摘要和工作区补丁。

## 未验证及下一步

- **UNTESTED**：真实 MySQL 8 迁移、InnoDB 隔离/锁竞争/死锁行为，真实 Nacos/网关/会话/Redis/RabbitMQ/远程沙箱的联合业务链。H2 测试移除了 MySQL 字符集/排序规则子句，不能验证这些规则。
- **待实施 J4**：Redis Lua 预留、稳定账本、独立报名队列、Outbox、未知发布证据、迟到确认、回收与对账。当前 DB_SYNC 不是秒杀吞吐方案。
- **待实施 J5**：成绩投影、并列名次、全量重建与最终结算；JUDGE_RESULT 仍只持久化，尚未消费计分。
- 浏览器脚本没有真实身份/基础设施联合验收；源码只在页面内保存，刷新后需重新输入代码，已接受的提交身份仍在服务器保留。
- J1/J2 的原生执行隔离、子进程树和跨管理器/重启回收边界继续保留；本机样例仅用于受控代码。
- Docker、部署、压测按用户要求后置。

实现依据核验：[MySQL 8 锁定读取](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)、[Spring 5.3 事务代理](https://docs.spring.io/spring-framework/docs/5.3.30/reference/html/data-access.html#transaction-declarative-annotations)，以及同级 DianPing 的 `VoucherOrderServiceImpl`。只借鉴身份、状态和事务边界，不复用订单实体。
