# CodeOJ 全面验收报告

日期：2026-10-18。基线：`5d6333e0bce3f1112c790b0c4824e5dd2fb3a6de`，`master`。

## 结论

**全面验收暂不通过。本机受控业务主链通过；发现1项新判题缺陷，复现1项既有未完成能力；完整故障矩阵证据仍不足。**

本轮按用户确认范围检查 J0—J6 文档、业务源码、回归与本机真实联合链。发现问题只记录，没有修改业务源码。部署、压测、容量指标与容器环境验证继续后置，不因这些后置项目本身判定本轮失败；也不将其表述为已实现或已通过。

本轮重新运行测试与实际服务，历史绿色记录只作定位线索。依据是 `docs/contest/development-plan-v1.1.md` 及用户确认的范围变更；阶段开发记录是证据索引，不替代执行结果。

## FAIL：独立样例发现的判题问题

### F01 / P2：注释中的 public class 导致正确代码被误判 CE（新发现）

位置：`codeoj-backend/codeoj-backend-codesandbox/src/main/java/com/codeoj/codeojbackendcodesandbox/service/impl/JavaNativeCodeSandbox.java:26`，以及87—94行。

触发样例：

```java
// public class Decoy
public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println(3);
        Thread.sleep(100);
    }
}
```

预期：以实际入口类Main编译并执行，输出3。实际：正则在整段源码中先匹配到注释里的Decoy，写成Decoy.java，javac报Main应位于Main.java，执行器返回 `CE / COMPILATION_FAILED`。

独立Corretto8直接编译同一代码到Main.java，编译和运行退出码均0，输出 `3\n`。因此不是测试代码语法错误、基础设施故障或隔离环境限制。会影响含示例注释的合法提交，并可能影响比赛成绩。

证据：`NativeVerdictProbe.java`、`native-verdict-probe.log`、`javac-comment-control.json`。探针基础对照 `plain-main` 为EXECUTED，同一正文加注释变为CE。

修复建议：明确并统一Java入口类约定，或使用能识别注释/字符串/修饰符的源码解析方式；不要用当前全源码正则直接决定文件名。修复后加入正向判题用例，覆盖行/块注释、字符串中的声明及合法修饰符。该建议尚未实施。

### F02 / P2：普通用户运行异常仍作为平台故障处理（既有未完成能力）

位置：同文件140—143行；下游 `ReliableJudgeWorker` 与 `SandboxResultMapper` 的平台错误分支。

触发样例：

```java
public class Main {
    public static void main(String[] args) {
        throw new IllegalArgumentException();
    }
}
```

预期业务结果：用户代码RE，属于完成判定。当前原生执行器返回 `PLATFORM_ERROR / UNCLASSIFIED_PROCESS_EXIT`，未给出用户verdict。下游按源码会进入平台失败/有限重试路径，持续出现时需人工处理，从而阻止正常结算；这一下游影响是调用链推导，本轮没有向正式联合样例加入该失败提交。

这是J0/J1文档已明确保留的原生归因限制，本轮重新复现，不能称为新回归。RE枚举及协议存在，并不代表RE执行归因已完成。当前范围只能验收“已支持的受控样例”；若承诺一般Java判题完整性，此项仍为FAIL/待完成。修复应增加受信异常/退出归因，不能简单把所有非零退出或stderr文本直接判RE。

证据：`NativeVerdictProbe.java`、`native-verdict-probe.log`；需求依据为原方案6.1及第8节结果分类，既有限制见 `docs/contest/j0-j1-local.md`。

## PASS：本轮重新执行的结果

| 检查 | 结果与证据范围 |
| --- | --- |
| 后端完整功能回归与打包 | Java8；23个实际执行测试类、186项、0失败/错误/跳过，含30项真实Redis+H2；`backend-regression.log` / `backend-test-results.json` |
| 测试计数真实性 | 只汇总本轮日志中实际执行类；target中2份旧基础设施XML被排除，未将历史失败或未执行上下文混入。四个基础设施空上下文仍按既有策略不在默认功能回归内 |
| 前端构建 | 通过；21项lint warning及既有依赖/包体积提示保留，没有新增错误；`frontend-build.log` |
| 语法检查 | 8项Python AST、Shell和Node检查通过；`syntax-results.json` |
| 运行包一致性 | 五个可执行服务主类及嵌入的共享模块jar匹配本次构建；`native/artifact-checks.json` |
| 原生基础设施 | MySQL8.0.46、Redis6.2.24、RabbitMQ4.3.6、Erlang28.5.0.8；固定摘要临时提取；V001—V004在真实MySQL执行通过 |
| 真实身份与业务链 | 注册/BCrypt登录/Redis会话→建赛发布→Lua预留→MySQL请求/Outbox→MQ资格确认→比赛提交→claim/租约→Feign→HTTP原生Java→结果事务→MQ计分→榜单 |
| 用户判定 | 实际网站链AC/WA/CE通过；不将此扩展为全部Java程序或RE已支持 |
| 故障恢复 | judge暂停和Broker正常停止/重启后的持久队列恢复；实际重复消息不增加attempt；沙箱不可用后重试AC；Redis503与AOF恢复原会话/资格；浏览器管理员对账恢复通道 |
| 真实浏览器 | 8项通过、0页面异常：管理员、参赛者与旁观者；真实Monaco提交并收到AC。结果来自 `native/browser.log`，没有HTTP mock |
| 额外实际HTTP契约 | 8项通过：原键回原ID、不同内容409、匿名榜单401、旁观者无题面、他人提交403、伪管理参数403、网关和服务直连内部接口均403；`live-contract-results.json` |
| 额外浏览器契约回归 | 15项通过、0页面异常；`browser-contract-smoke.log`。这15项明确使用HTTP mock，仅证明前端交互契约 |
| 结算 | 真实到期→FINALIZING→关闭报名闸门对账→全量重建→FINISHED，finalVersion=2；原键截止后回原提交，新键409 |
| 最终权威事实 | 6条提交全部COMPLETED（AC/WA/CE/AC/AC/AC）；6回执；pendingCount=0、projectionPendingCount=0；1资格、容量2/余量1；沙箱恢复提交2次attempt、重复消息样例1次 |
| 清理与源码保护 | 自建实例数据及下载/编译运行时删除；12个端口均释放；业务源码无修改；原有 `output/` 未读取、未修改；未提交/推送本轮验收文件 |

真实链仅使用静态SimpleDiscoveryClient实例；网关仍走lb://、服务调用仍走Feign和内部鉴权。**本轮没有启动Nacos**。Broker恢复是正常SIGTERM停止/重启，不能替代掉电/磁盘故障持久性验证。真实链样例仅1个有效参赛者/1题，不能证明多用户多题在MySQL中的所有竞争顺序。

后端测试覆盖的关键规则已抽查源码：提交与Outbox事务、不可变规格、代次/token/截止校验、有限自动与人工预算、报名DB条件扣容量与唯一约束、取消/回收互斥、稳定Redis身份与无TTL账本、结果事件与回执、首次AC受理时间、同用户不同题计分锁、全局跨页并列、权威重建与最终版本保留。未发现本轮探针以外的新确定性反例；这不构成所有并发/故障组合正确性的证明。

## BLOCKED / UNTESTED：仍缺的本机完整场景

详表：[JV01—JV29逐项映射](jv-matrix.md)，机器可读版为 `jv-matrix.json`。

本轮按完整触发条件保守标记：4项真实场景已有相应证据，25项完整真实场景仍UNTESTED；后者多数已有通过的组件测试或部分联合证据，不能解读为25项业务失败，也不能以组件PASS填成真实故障PASS。两个额外判题FAIL单列于上文。

当前范围内仍应补齐的重点：

1. 真正杀进程的精确事务窗口：API提交前/后、RUNNING worker、结果提交后ACK前、Redis预留后DB前。
2. 真实Broker的Confirm丢失、Return、连接结果未知及旧代次迟到；两个真实消费者同时争抢同一执行权。
3. 真实MySQL下报名超容量竞争、取消/确认/提交与回收竞争、死锁回滚重试、延迟确认及账本异常组合。
4. 真实多用户多题手算榜单、早期AC迟到、跨页并列、零分、重建与迟到投影/旧缓存竞争；本轮这些主要由H2和缓存契约验证。
5. 真实普通练习→异步队列→固定规格执行，以及排队后编辑原题；目前本轮真实网站联合样例集中于比赛提交。
6. 旧库带历史数据迁移、旧code_queue排空/切换和新旧路径回滚兼容演练；本轮验证的是空fixture库增量迁移。

没有自动批准被拒导致的BLOCKED项。未实现/未运行的完整提交隔离、任意后代回收、跨管理器/重启残留回收仍应记录，不能用数据库token拒绝旧结果替代物理资源回收。容器验证、部署、压测及生产容量按用户要求后置；Nacos/集群/多节点本轮未验收。

## 本轮复现方法与证据索引

本轮具体命令及退出码：`commands.json`。源码指纹：`source-manifest.sha256`，覆盖380个已跟踪相关文件；基线信息：`baseline.json`。依赖地址与摘要：`runtime-archives.json`。主清理证据：`cleanup.json`、`runtime-cleanup.json`、`native/run.json`。

顺序为：固定摘要准备临时运行时→Java8带真实Redis/RSS选项执行离线Maven package→空API基址前端build→`scripts/local/run-native-stack.py`→清理。可复现主链入口是仓库已有 `scripts/test-native-stack.sh`；本轮各阶段分开执行并记录，没有宣称整段wrapper作为单条命令执行。

额外样例源码 `NativeVerdictProbe.java` 需要沙箱模块本次target/classes和可执行jar中BOOT-INF/lib作为类路径，通过Java8编译运行；日志前缀PROBE给出实际outcome。`live-contract-probe.py` 在自建fixture运行且browser-fixture.json生成后执行，使用该临时实例凭据，结果仅保留脱敏状态。实例已清理，脚本不能针对一个不存在的fixture单独重放。

初次附加HTTP探针把网关拒绝内部接口的非JSON错误体当JSON，属于验收工具解析错误；修正探针后8项全过，未修改业务代码，说明见 `probe-tooling-notes.md`。结果统计也排除了旧Surefire报告，避免将旧记录当本次结果。

本轮报告与证据尚未提交。后续顺序建议：先修复F01；明确F02在本机阶段的完成标准并补受信RE归因；再补上述真实崩溃/竞争场景并复验对应JV。不得仅凭186项回归或本机主链绿色，将J0—J6整体记为完成。
