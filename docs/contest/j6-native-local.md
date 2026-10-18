# J6 本机真实链路联调记录

日期：2026-10-18。用户选择临时独立实例，验证后清理，只保留脱敏证据。继续不使用 Docker、不安装全局数据库/消息服务、不部署、不压测。原有 `output/` 未读取或改动。J5/J6改动尚未提交。

## 环境与复现

本机检查未发现已启动的 MySQL、Redis、RabbitMQ、Nacos 和 CodeOJ 服务，也未发现对应原生服务安装。本轮固定下载包、校验摘要并在临时目录运行：

| 组件 | 版本/方式 |
| --- | --- |
| MySQL | Community 8.0.46，官方macOS15/arm64压缩包；独立数据目录、应用账户与随机端口，时区UTC |
| Redis | 6.2.24，固定官方源码摘要后临时编译；独立密码/端口/AOF，appendfsync=always |
| RabbitMQ | 4.3.6 Generic UNIX；独立节点、cookie、端口、Mnesia、日志与管理接口 |
| Erlang / 配套库 | Homebrew固定28.5.0.8及OpenSSL4.0.3包；仅重定位临时副本动态库路径并作本机ad-hoc签名，不改动系统库 |
| Java | Corretto8，真实5个服务进程：user/question/judge/gateway/codesandbox |
| 前端 | Vue生产构建，已有Chrome/Playwright无头；真实HTTP，无page.route或伪造登录态 |

所有外部依赖和服务限制在回环地址，使用本轮随机端口。MySQL、Redis、MQ均为真实进程。为减少本机依赖，Nacos未启动；使用Spring `SimpleDiscoveryClient` 固定实例配置，网关仍走 `lb://`，判题仍走Feign和内部鉴权，沙箱仍经HTTP远程协议调用。不能把这一结果称为Nacos注册发现验收。

```bash
# macOS arm64，Python>=3.11、Java8、Maven、Node/npm、C编译工具、已有Chrome/Playwright
PLAYWRIGHT_MODULE=/absolute/path/to/playwright \
PLAYWRIGHT_BROWSER_CHANNEL=chrome \
  scripts/test-native-stack.sh
```

脚本默认写 `outputs/validation/native-<时间>/`，可用 `CODEOJ_NATIVE_EVIDENCE=/absolute/path` 指定。首次需要下载约数百MB原生包，解压占用更多临时空间；不调用brew install或brew services，不建立常驻服务。每个组件有独立目录和随机凭据，配置文件600权限，测试结束/异常时停止拥有的进程组并删除实例数据。外层脚本退出再删除临时运行时和源码包。

`prepare-runtimes.py` 固定URL与SHA-256。MySQL首次还核对了官方MD5，后续脚本固定本轮实际SHA-256；各包的原URL及摘要保存在证据中。已有OpenSSL3保持不动。Erlang单独限制线程及本机分布式端口，不借用全局epmd。

前端新增 `VUE_APP_API_BASE_URL` 构建参数；未设置时保留原 `http://localhost:8101`。本轮显式设为空字符串，通过临时静态服务器将 `/api` 转发到真实网关，使浏览器使用同源Cookie。该转发不修改请求/响应业务数据，也不生成模拟API结果。

## 联调发现与修复

### 正式Spring代理下的受理/判题空指针

真实登录后，发布比赛返回503。原错误响应不泄露内部异常，补充受控诊断后定位到 `SubmissionAcceptance.freezeSpec`。

根因：`ExecutionRepository` 标注 `@Repository`，完整Boot环境的异常转换后处理器生成CGLIB代理；消费者直接访问 `repository.jdbc` 读到了代理对象未初始化的字段。原H2精简上下文未启用异常转换代理，所以没有复现。

修复：字段改为private，通过可代理的 `jdbc()` 方法访问目标实例。同步修改提交受理和执行命令的所有调用。ReliableExecutionTest、ContestCoreTest和ScoreboardTest显式注册真实异常转换后处理器，增加代理存在且JDBC可用的断言。不能通过关闭代理来绕开生产环境问题。

`ExecutionRpcAdvice` 新日志仅保存异常类型、应用代码位置及受限格式的SQLState/错误码；不记录异常message、Throwable、SQL参数、代码、用例、Cookie或密码。专门测试覆盖敏感消息和非标准SQLState的脱敏。

### Redis会话故障早于MVC异常处理

Redis关闭时，会话读取可能在Session过滤器阶段超时，MVC advice尚未接管，原响应是通用500。客户端连接还具有重连退避，TCP监听恢复不代表会话请求立即恢复。

修复：共享Servlet服务在Session过滤器外增加 `SessionAvailabilityFilter`。识别Redis/Lettuce原因链，未提交响应时返回503/SESSION_STORE_UNAVAILABLE，不删除原会话Cookie，不伪装未登录、未报名或报名失败；非Redis异常保留原处理方式，已提交响应不重写。四个单元用例覆盖这些边界。测试脚本以真实会话查询成功作为恢复条件，有界等待客户端重连。

### 增量运行包仍包含旧共享依赖

第二次Redis验证发现新过滤器没有进入运行包。对比确认：reactor中service-client jar已更新，可执行question-service jar内仍是旧版本（摘要不同，缺少新增类）。普通package复用已被Boot二次处理的jar，未重建原始jar输入。

修复：父POM固定maven-jar-plugin 3.5.0并设置forceCreation=true，使Boot每次从新原始jar重新打包。联调启动前检查五个服务的每个主类，以及嵌入的common/model/service-client jar字节是否与当前target一致，不匹配则在启动任何实例前失败。源码测试通过不能替代运行包核验。

### 原生联调工具的就绪检查

RabbitMQ管理端口可能先监听、后安装HTTP路由，已改为 `/api/overview` 返回真实版本后才继续。Erlang Homebrew包中的库占位路径需重定位临时副本，否则crypto NIF无法加载；保留版本和处理过程，不改动全局库。脚本也重定向第三方Java日志到临时目录；初轮使用默认日志目录时产生的追加诊断不通过删除既有用户日志清理。前几轮失败日志与清理记录保留在run-01到run-06，不只保留最终绿色结果。

## 真实链路范围

联调使用真实接口创建三种测试账户，注册/BCrypt登录，管理员角色只在独立fixture数据库中设定。HTTP会话由现有Spring Session Redis产生和共享。

通过真实HTTP管理接口创建/发布比赛、切换秒杀报名并对账，参赛者报名经Redis Lua→MySQL请求/Outbox→RabbitMQ→确认资格。到真实开赛时间后，提交经Outbox→MQ→判题服务claim/租约→Feign→远程原生Java沙箱→结果事务→计分MQ→榜单。原始业务键重试返回同一提交。

浏览器实际操作覆盖管理员登录/比赛管理、报名通道视图/实际对账恢复、参赛者登录/Monaco提交/AC状态/榜单、未参赛用户登录后读榜单且无法查看专用题面。创建、发布和首次报名由真实HTTP脚本驱动，未声称这些步骤全部通过浏览器表单操作。

故障检查每次只提交一个受控样例，属于功能恢复验证：

- 判题服务停机期间提交，等待真实Broker确认；重启RabbitMQ和判题服务后继续判题。
- 将原判题消息通过实际Broker再投递，原提交只有一个有效attempt，不重复执行/计分。
- 沙箱不可用时提交进入平台重试，恢复沙箱后转AC，不把平台故障写成WA。
- Redis不可用时真实会话请求503；AOF重启后恢复原会话、原资格和账本。
- 比赛真实到期进入FINALIZING；关闭Redis闸门完成对账后重建、校验并固定最终版本。
- 到期后原提交键可回查原受理结果，新键受理被拒绝。

## 验证结果

| 状态 | 范围 |
| --- | --- |
| PASS | Java8完整回归186项、0失败/错误/跳过；含30项独立Redis+H2，真实异常转换代理、日志脱敏和会话503边界 |
| PASS | 前端构建、Python/Node/Shell语法检查，五个服务运行包主类/共享依赖匹配当前构建 |
| PASS | MySQL V001—V004原生迁移，真实注册/登录/会话、Redis Lua+MQ报名确认、AC/WA/CE、榜单和权限 |
| PASS | 正常Broker/判题服务重启后的持久队列恢复、重复消息无额外attempt、沙箱失败后重试AC |
| PASS | Redis停机503，AOF重启后原会话与身份恢复，再由管理员真实对账恢复通道 |
| PASS | 真实浏览器8项、0页面异常；浏览器提交的AC还通过独立数据库只读检查确认 |
| PASS | 最终成功轮run-07：6提交全部COMPLETED、6计分回执、pendingCount/projectionPendingCount均0，FINISHED/finalVersion=2；原键回查成功，新键到期拒绝 |
| PASS | 全部12个本轮端口已释放；实例数据、下载/编译运行时和临时包均已清理，未安装全局服务 |
| FAIL（历史轮） | run-01/run-03发现代理字段空指针；run-02就绪竞态；run-04会话故障边界；run-05打包旧依赖；run-06缺少故障后管理对账步骤；均保留记录，不计入最终成功轮 |
| UNTESTED/后置 | Nacos、全部JV、全故障矩阵、集群、多节点、完整提交隔离、部署与压测 |

## 结果与证据边界

最终结果、逐项事件、数据库安全投影及浏览器结果见 [本轮证据目录](../../outputs/validation/j6-native-20261018/)。完整后端回归和前端构建分别留存，原失败轮不并入成功轮。

本机真实联合链只证明本轮固定版本、单节点、受控输入和已执行场景。未完成整套JV01—JV29验收，不宣称正式生产可用。

UNTESTED/后置：Nacos注册发现、Redis Cluster/MySQL主从/多节点服务、全故障矩阵、MQ磁盘故障/掉电、真实Broker下的所有Confirm/Return/未知组合、全面InnoDB死锁/隔离矩阵、完整提交级容器隔离、恶意代码公开执行、跨重启沙箱孤儿进程清理、部署与压测。RabbitMQ重启是受控正常停止/重启，不等同于断电持久性保证。

依赖/运行步骤参考：[MySQL原生二进制](https://dev.mysql.com/doc/refman/8.0/en/binary-installation.html)、[RabbitMQ Generic UNIX](https://www.rabbitmq.com/docs/install-generic-unix)、[Spring固定实例发现](https://docs.spring.io/spring-cloud-commons/docs/3.1.8/reference/html/#simplediscoveryclient)、[Spring代理与异常转换](https://docs.spring.io/spring-framework/docs/5.3.x/reference/html/core.html#aop-understanding-aop-proxies)。另参考 [Maven jar forceCreation](https://maven.apache.org/plugins/maven-jar-plugin/jar-mojo.html#forceCreation)。文档用于核对机制；本轮是否通过以保存的命令和实际结果为准。
