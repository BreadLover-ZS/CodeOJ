# J0/J1：本机构建与单次判题

后续修复说明（2026-10-18）：入口识别与主线程/类初始化异常的RE归因已更新，见 [修复记录](fix-native-verdict.md)。以下保留本阶段当时的代码和证据边界；不能将历史“尚未完成”或协议枚举当作当前全部能力的结论。

日期：2026-10-17。代码基线：`b2f43551a4929babe6f5b8be22007670f18767b9`。本轮修改保留在工作区，未提交、未推送；证据目录记录修改文件的哈希，不能把基线 SHA 当成本轮修改的提交 SHA。

## 本机基线

- macOS / arm64，Amazon Corretto `1.8.0_504`，Maven `3.9.16`，Node `26.0.0`。
- 系统默认 Java 26；构建与演示明确选用已有 Corretto 8，没有升级项目 Java/Spring 版本。
- 本轮检查时 MySQL、Redis、RabbitMQ、Nacos 的常用端口未监听，客户端命令也不在 PATH 中；不由此断言整台机器没有这些软件。
- 改动前 `mvn test` 确实发现并运行 JUnit 5；在题目服务 `contextLoads` 处因 Redis `localhost:6379` 连接拒绝失败。不是 `Tests run: 0`，也不是已经完成业务回归。
- 父 POM 固定 Surefire `3.5.4`。四个既有空 `@SpringBootTest` 标记为 `infrastructure`，默认功能测试不启动它们。`-Pinfrastructure-tests` 会重新包含它们；没有删除或伪造通过。
- 父 POM 原有 Spring Cloud BOM 放在普通 dependencies 中，Maven 仍提示 `scope=import` 不合法。此次未调整依赖管理，以免混入未经核对的版本解析变化。

## 实际修改

1. 提交入口与页面只接受 Java。保留旧语言枚举和历史记录查询。
2. 执行器并发读取 stdout/stderr，独立写入 stdin；字节预算合并计算。输出超限终止执行，不把截断内容当完整答案。
3. 编译、每个用例和整次执行分别有期限。整次预算不足或执行器策略小于题目时限时报告平台错误，不偷偷缩短题目时限后判 TLE。
4. 每次编译一次，各用例分别启动 JVM、使用独立工作目录。执行器不把宿主环境变量和凭据传给用户 JVM。
5. 默认两个本机 slot，满额立即返回容量暂不可用，不把排队算作 TLE。它还不是 J2 中带 executionToken 的执行身份与恢复协议。
6. 结果先按结构化 outcome 分类。CE/TLE/MLE/OUTPUT_LIMIT 是已完成的用户判定；HTTP 超时、指标未知、协议缺失、无法归因的非零退出均走平台错误，不转成 WA。
7. Java 策略移除固定扣除 10000ms；时间单位为 ms，内存为 KiB。修正前端新建题目的内存单位，默认值改为 131072 KiB；没有改写历史题目配置。
8. 文本比较只进行 CRLF→LF，并忽略末尾一个 LF。保留首尾空格、制表符、额外空行及大小写。沙箱返回原始输出，由比较器对两侧统一处理。
9. HTTP 连接/读取/调用总期限默认 2000/35000/38000ms；最多四个在途传输，响应体上限 8MiB（覆盖允许输出的 JSON 转义膨胀），不自动重试。总期限返回后，底层读取可能延续至读期限或下一次读取检查；这不证明远端执行已终止，执行 ID/取消由 J2 后续补齐。
10. 沙箱代理日志只记语言、用例数量与 outcome，不打印源码、输入或输出。未知沙箱类型直接报错，不回落到示例执行器。

## 本轮协议

`ExecuteCodeRequest`：`language=java`、`code`、非空 `inputList`，可选 `runTimeLimitMs`、`memoryLimitKiB`。判题服务从题目配置传入资源限制。当前上限：源码 UTF-8 256KiB、输入合计 1MiB、20 个用例、单用例 stdout+stderr 合计 64KiB、内存配置不高于 1048576 KiB。默认编译 15s、用例 5s、整次 30s。此配置用于开发功能验证，不是容量结论。

| outcome | 兼容 status | 后续动作 |
| --- | --- | --- |
| EXECUTED | 1 | 校验资源指标、数量、答案，再生成 AC/WA 等最终 verdict；不是自动 AC |
| CE / RE / TLE / MLE / OUTPUT_LIMIT | 2 | 映射明确用户 verdict，流程完成不等于 AC |
| PLATFORM_ERROR / REJECTED | 3 | 不生成用户 verdict；当前旧流程写平台异常，J2 再接可恢复任务状态 |

`JudgeInfo.verdict` 为稳定代码，`message` 保留旧展示兼容。旧响应缺 outcome、status 矛盾或输出数量缺失均视为平台协议错误。模型与沙箱/判题服务需要配套更新，不能承诺新旧沙箱响应可随意混用。

内存方法 `NATIVE_RSS_SAMPLED`：通过 `/bin/ps` 采样直接 JVM 的 RSS，单位 KiB，采样间隔至少 20ms。它是采样最大值，可能漏掉短时峰值，不包括子进程，也不等于内核 cgroup 峰值或硬限制。观测到超过限制时终止直接 JVM，返回 `OBSERVED_RSS_LIMIT_EXCEEDED`。任何用例未采样成功，则完整结果内存为 unknown；策略拒绝将其变成 0 或 AC。

原生非零退出无法可靠区分用户异常、JVM 问题和外部终止，当前保守返回 `UNCLASSIFIED_PROCESS_EXIT`。RE 协议已定义，但没有用 stderr 字符串猜测或宣称其原生归因完成。非特权、禁网络、只读根文件系统、PID/CPU 硬约束、进程树回收和防恶意修改编译产物均待隔离执行器完成。当前样例目录隔离不能被表述成安全隔离。

## 复现命令

在仓库根目录选择已有 Java 8 JDK（macOS 示例）：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -f codeoj-backend/pom.xml test
mvn -B -f codeoj-backend/pom.xml -Dcodeoj.native-observation=true test
./scripts/demo-local-judge.sh
```

第二条测试命令显式启用两项 RSS 观测验证；需要本机允许 `/bin/ps` 读取本轮启动的子进程。普通测试仍包含回环 HTTP 协议测试，需要允许本机端口绑定。Codex 沙箱限制端口绑定/进程观测时，应在获得允许的执行环境复验，不删除这些测试来制造通过。

演示调用真实 `javac/java`、`JavaNativeCodeSandbox`、`SandboxResultMapper` 和 `JavaLanguageJudgeStrategy`，运行 AC、WA、CE、TLE、OUTPUT_LIMIT 五个受控样例；没有启动业务服务、MySQL、Redis 或 MQ，不能称为完整网站端到端验收。Java 8 选择与临时目录清理由脚本处理，默认构建不运行部署或压测。

完整基础设施上下文检查（本轮未重新运行成功）：

```sh
mvn -B -f codeoj-backend/pom.xml -Pinfrastructure-tests test
```

## 验证记录

本轮最终计数、命令、环境和示例结果见 [证据目录](../../outputs/validation/j1-native-20261017/)。单元/服务替身、回环 HTTP 协议、真实子进程和完整业务链证据分别记录。改动前 Redis 环境失败与执行沙箱拒绝端口的记录也保留。

| 项目 | 状态 | 边界 |
| --- | --- | --- |
| Java 8 全模块构建 | PASS | 只证明打包 |
| 新增功能测试 | PASS | 计数见 test-results.json；基础设施上下文不在此范围 |
| AC/WA/CE/TLE/输出超限实际执行链 | PASS | 本机方法调用演示，不含业务 DB/MQ/网页 |
| stdout/stderr、阻塞 stdin、直接 JVM 超时回收 | PASS | JV07 的部分本机证据；不含任意后代进程回收 |
| RSS 采样与观测超限 | PASS | 不代表 JV08 全部安全约束通过 |
| 前端构建 | PASS | 未做浏览器真实 API 回归 |
| 完整服务与 DB/MQ 链、旧队列迁移 | BLOCKED/UNTESTED | 当前本机依赖未运行 |
| 容器/进程树/网络/PID/CPU 安全隔离 | UNTESTED | 隔离执行器尚未实现；不因用户后置部署而宣称已经完成 |
| 部署、压力负载与容量指标 | 后置 | 不属于当前开发验收范围 |

下一项实现为 J2：提交与 Outbox 的本地事务、不可变判题规格、调度代次、claim/heartbeat/complete/fail、有限恢复和人工处理。在接通真实 MySQL/RabbitMQ 前，相关实现不能提升为真实依赖验收。

## 源码阅读入口

先读 `BoundedProcessRunner` 理解为什么三个流必须独立处理、为什么销毁进程要先于关闭可能阻塞的流；再读 `JavaNativeCodeSandbox` 的平台预算/用户期限区分；最后对照 `SandboxResultMapper` 与 `JavaLanguageJudgeStrategy` 理解“执行完成”“用户判定”和“平台故障”的边界。

API 兼容核对参考 [Java 8 Process](https://docs.oracle.com/javase/8/docs/api/java/lang/Process.html)、[ExecutorService](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutorService.html)、[Surefire JUnit Platform](https://maven.apache.org/surefire/maven-surefire-plugin/examples/junit-platform.html)，并核对了本机 Corretto 8 的 UNIXProcess 源码与实际 Hutool 5.8.8 方法。官方 API 本身不提供本轮未实现的恶意代码隔离保证。
