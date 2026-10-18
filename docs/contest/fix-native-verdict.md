# 本机判题 F01/F02 修复

日期：2026-10-18。基线：`5d6333e0bce3f1112c790b0c4824e5dd2fb3a6de`。修复起因是 [全面验收报告](../../outputs/validation/acceptance-20261018/report.md) 的两项FAIL；历史报告及原始失败证据保持不变。本轮不涉及部署、Docker或压测。

## F01：用实际编译结果选择入口

根因：原执行器对整个源码执行 `public class` 正则，注释、字符串或嵌套类可能先被匹配。正确的Main代码因此写成另一个文件名而被误判CE；修饰符和Unicode声明也可能识别错误。

修复后的 `NativeCompiler` 是仅依赖JDK的独立子进程。通过JavaCompiler与虚拟JavaFileObject编译源码，源对象不依赖物理文件的公共类型文件名约定；编译器处理语法、注释、字符串与Unicode。随后从本次编译产物中读取顶层类型元数据，选择唯一公共顶层类型，或无公共类型时的Main。不会在编译阶段初始化用户类；多个公共顶层类型明确拒绝为CE。

保留普通Main、非public Main及其他公共入口名（例如Solution），支持合法修饰符；不添加第三方解析器或升级JDK。编译仍在原有期限与输出预算下运行，禁用注解处理，编译类路径/源码路径只指向该提交的产物目录。编译失败由编译任务的明确结果记录CE，不再依赖stderr诊断文本。

启动器字节码从本次运行包复制到独立目录，用户类由单独的、父加载器为空的URLClassLoader读取。运行时检查有无公共静态void main(String[])，无有效入口或启动器失效保留平台错误。

## F02：单独记录可归因的主线程异常

用户确认的边界：**本机受控程序的主线程未捕获异常、入口类初始化异常判RE；System.exit、外部终止、JVM异常等原因不明确的退出保留平台故障。**

`NativeLauncher` 调用main并处理InvocationTargetException或入口类初始化异常。成功返回、用户异常、平台错误分别写入独立控制回执；不记录异常消息/堆栈，不从用户stdout/stderr推断RE。宿主校验本次随机标识、版本、完整字段、字节上限及正常子进程退出。回执按用例单独创建，位于用例工作目录之外；读写不跟随符号链接。

| 情况 | 结果 |
| --- | --- |
| main正常返回且回执有效 | EXECUTED，继续原比较器和资源完整性检查 |
| 普通主线程异常，如IllegalArgumentException、ArithmeticException、NullPointerException | RE / USER_MAIN_EXCEPTION，属于COMPLETED，不触发平台重试 |
| 入口类初始化时普通异常 | RE / USER_CLASS_INITIALIZATION_EXCEPTION |
| 实测运行期限、RSS或输出预算超限 | 继续原TLE/MLE/OUTPUT_LIMIT规则，优先于控制回执 |
| System.exit(7)、外部非零退出 | PLATFORM_ERROR / UNCLASSIFIED_PROCESS_EXIT |
| System.exit(0)提前退出但没有有效回执 | PLATFORM_ERROR / EXECUTION_RECEIPT_UNAVAILABLE，不能仅凭exit=0判执行成功 |
| VM/链接错误或入口/启动器错误 | PLATFORM_ERROR；不猜RE或MLE |
| 回执缺失、截断、标识不匹配、过大或不合法 | PLATFORM_ERROR |

RE沿用既有SandboxResultMapper→完成命令→结果Outbox→计分链路；不改动租约/token/不可变结果规则。用户异常消息不会进入公开判题信息。

这不是恶意代码隔离或防篡改协议：原生用户进程仍具有宿主用户权限，不能把回执标识当作恶意程序无法获取的秘密。这里证明的是已确认的受控本机归因，未声称任意程序下完整RE、子线程异常归因、任意后代/跨重启回收或资源硬隔离完成。

## 回归与真实链路

证据目录：[fix-native-verdict-20261018](../../outputs/validation/fix-native-verdict-20261018/)。

- 原验收三段同源码探针重新运行：普通Main正常执行、含干扰注释Main正常执行、未捕获用户异常RE。保留旧失败证据作对照。
- 定向覆盖行/块注释、字符串、嵌套类、合法修饰符、Unicode、非public Main、同名启动器、接口入口，以及主线程/初始化异常、VM错误、System.exit和输出伪协议文本。
- 回执测试覆盖随机标识隔离、缺失/截断、过大与符号链接；worker契约确认RE仅调用完成命令、不走平台失败重试。
- `run-native-stack.py` 增加COMMENT_AC与RE真实提交；RE必须COMPLETED且activeAttempt=1。原异步报名、AC/WA/CE、故障恢复、浏览器和最终结算继续验证。

具体运行结果与最终权威事实以该目录的测试汇总、命令、native事件及database-facts为准。未测的JV崩溃/竞争场景不因两项修复自动变为PASS。

## 机制参考

兼容性按Java8 API和本机Corretto8核对：[JavaCompiler](https://docs.oracle.com/javase/8/docs/api/javax/tools/JavaCompiler.html)、[SimpleJavaFileObject](https://docs.oracle.com/javase/8/docs/api/javax/tools/SimpleJavaFileObject.html)、[Method.invoke](https://docs.oracle.com/javase/8/docs/api/java/lang/reflect/Method.html)。编译文件名检查另对照 [OpenJDK8 Enter实现](https://github.com/openjdk/jdk8u/blob/master/langtools/src/share/classes/com/sun/tools/javac/comp/Enter.java)，虚拟源码不冒充package-info/module-info专用文件。这些参考解释机制，是否修复以本轮测试和实际链路为准。
