# F01/F02 修复复验结果

日期：2026-10-18。基线：`5d6333e0bce3f1112c790b0c4824e5dd2fb3a6de`，修改尚未提交/推送。

**结论：两项问题在用户确认的本机范围内修复并复验通过。整套JV、完整恶意代码隔离、部署和压测未因此变为通过。**

| 检查 | 结果 |
| --- | --- |
| F01原失败探针：注释含public class的正确代码 | PASS，EXECUTED；真实MySQL/MQ网站提交判AC |
| F02原失败探针：未捕获IllegalArgumentException | PASS，RE；真实链COMPLETED/RE且1个attempt，不触发平台重试、不阻止最终结算 |
| 最终源码后端回归/打包 | PASS，24类208项，0失败/错误/跳过，含30项真实Redis+H2；四个空基础设施上下文仍按原策略排除 |
| 入口与错误边界 | PASS，行/块注释、字符串、嵌套类、修饰符、Unicode、接口/包名/非public入口、主线程及初始化异常、VM错误、System.exit、输出伪协议、回执异常 |
| 原生完整链 | PASS，MySQL8.0.46/Redis6.2.24/RabbitMQ4.3.6；8提交全部COMPLETED、8回执、待判与待投影均0，FINISHED/finalVersion=2 |
| 恢复回归 | PASS，Broker/judge正常重启、重复消息、沙箱故障重试、Redis503/AOF会话恢复、管理员对账 |
| 真实浏览器 | PASS，8项、0页面异常，无HTTP mock |
| 前端构建 | PASS；代码无前端改动，既有警告保留 |
| 清理 | PASS，临时实例/下载编译运行时移除，12个端口全部释放 |

## 实现与边界

源码与设计说明见仓库 `docs/contest/fix-native-verdict.md`。没有新增第三方解析器、依赖升级、HTTP协议或数据库迁移。

编译器处理真实Java语法，并从本次编译产物选择顶层入口，消除源码正则的错误匹配。编译进程仍有期限和输出上限，且不初始化用户类。运行启动器用单独回执记录main正常返回、主线程异常或初始化异常；宿主校验本次标识、完整回执与退出状态，避免把stderr文本或单独exit=0当判定证据。

用户明确接受的RE范围是受控本机程序的主线程与入口类初始化普通异常。System.exit、外部终止、VM/链接错误及无有效回执仍保留平台错误；不将任意非零退出猜成RE/MLE。原生同宿主权限仍不能保证恶意代码下的回执防篡改或完整隔离；子线程归因、任意后代/跨重启回收与其他未测JV仍未验收。

## 证据与轮次

- 最终源码结果：本目录 `commands.json`、`backend-test-results.json`、`original-failures-recheck.json`、`native/run.json`、`native/database-facts.json`、`native/final-scoreboard.json`、`native/browser-results.json`。
- 源码对应关系：11文件SHA-256清单 `source-manifest.sha256`、含新增文件的 `workspace.patch`、反向应用检查 `source-checks.json`。基线SHA不冒充当前未提交修复的SHA。
- `targeted-tests.log` 是最初34项定向测试；最终24类208项以完整回归为准。
- `round-01`：首轮207项及真实链已通过，随后补虚拟源码专用文件名边界与带包名用例，最终源码重新执行，未把前一轮直接当最终证据。
- `round-02-download-failure`：运行时准备时Erlang归档摘要不符，脚本拒绝使用并清理，未启动实例或执行业务测试。首次下载HTTP头/实际摘要因清理无法回溯，不推测具体网络原因。单独重新获取同一固定URL后，HTTP200、完整88073667字节、SHA-256与固定值一致，见 `download-diagnostic.json`；最终轮复用这份校验正确的归档，未放宽校验。
- 既有全面验收报告与原FAIL探针保留在 `../acceptance-20261018/`，没有改写历史结果。

未提交、未推送；原有 `output/` 未读取或修改。
