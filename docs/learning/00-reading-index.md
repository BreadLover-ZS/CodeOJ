# CodeOJ 学习与二开导航

这套文档服务于三个目标：读懂现有代码、完成一条有证据的二开主线、在面试中准确说明个人工作。阅读时始终区分三种内容：

- **当前实现**：能由当前源码直接证明；
- **目标设计**：准备修改，但还没有实现；
- **验证证据**：构建、测试、联调、故障注入或压测产生的结果。

## 1. 推荐阅读顺序

### 第一轮：先形成全局地图

阅读：

1. 根目录 `README.md`；
2. `codeoj-backend/pom.xml`；
3. Gateway、四个业务服务和沙箱的 `application.yml`；
4. [当前架构与真实判题调用链](01-architecture-and-judge-call-chain.md)。

完成标准：可以不看文档画出一次提交从 HTTP、MySQL、RabbitMQ、Judge Service 到 Code Sandbox 的路径，并说出每个服务保存什么状态。

### 第二轮：沿一次 Java 提交读源码

按顺序定位：

1. `QuestionController#doQuestionSubmit`
2. `QuestionSubmitServiceImpl#doQuestionSubmit`
3. `MyMessageProducer#sendMessage`
4. `MyMessageConsumer#receiveMessage`
5. `JudgeServiceImpl#doJudge`
6. `RemoteCodeSandbox#executeCode`
7. `ExecuteCodeController#executeCode`
8. `JavaNativeCodeSandbox#executeCode`
9. `JudgeManager#doJudge`
10. `JavaLanguageJudgeStrategy#doJudge`

完成标准：能分别解释 AC、WA、编译错误、运行错误和超时在当前代码中实际会走到哪里；不能只复述理想流程。

### 第三轮：用失败时间线读可靠性

围绕以下时刻逐个推演：

- 提交记录入库前失败；
- 入库成功、发布 MQ 前后失败；
- 两个消费者同时收到同一提交 ID；
- 改成 RUNNING 后消费者崩溃；
- 沙箱返回失败响应；
- 结果已写回、ACK 前消费者崩溃；
- 失败消息被 `nack(requeue=false)`。

阅读：[可靠判题二次开发设计](02-secondary-development-design.md)。

完成标准：能说明“至少一次投递”和“业务幂等”是两件事，并给出每个失败点的当前后果、目标状态和验证方法。

### 第四轮：补用户与权限链路

重点源码：

1. `UserController`、`UserServiceImpl`；
2. `AuthCheck`、`AuthInterceptor`；
3. `GlobalAuthFilter`；
4. `FeignInnerAuthConfig` 和各 `*InnerController`；
5. Spring Session、Redis 和 CORS 配置。

当前要点：Gateway 没有统一登录认证；Session 中保存用户对象，用户服务读取后会再次查库；跨服务中的 `UserFeignClient#getLoginUser` 默认实现只读 Session；内部接口使用共享密钥，不是服务身份系统；账号防重的 `synchronized(userAccount.intern())` 只在单 JVM 生效，数据库也没有唯一约束兜底。

### 第五轮：把学习变成证据

阅读：[证据、压测与简历台账](03-evidence-and-resume-ledger.md)。每完成一项改动，同步记录：

- 原问题和复现方式；
- 设计取舍；
- 关键代码和测试；
- 失败样例；
- 尚未覆盖的边界；
- 可以写进简历的最小准确表述。

## 2. 70 小时投入建议

14 天 × 每天 5 小时是约束，不是验收标准。按 Gate 推进，不因日期到了就把未验证内容写成“完成”。

| 工作包 | 建议投入 | 产出 |
|---|---:|---|
| 工具链、构建、最小 E2E | 8h | 可重复启动记录和基线失败样例 |
| 判题结果契约与单测 | 10h | AC/WA/CE/RE/TLE 明确映射 |
| CAS 抢占、幂等与恢复 | 14h | 重复消息、宕机恢复证据 |
| Outbox、发布确认、重试/DLQ | 18h | 入库到发布闭环与重放证据 |
| 沙箱最小安全修复 | 10h | 超时、输出、日志和恶意样例 |
| 压测、报告、简历复盘 | 10h | 原始数据、瓶颈和可讲述结论 |

如果实际进度落后，优先保证“结果契约 + CAS + Outbox/DLQ + 可复现测试”的闭环。完整容器级沙箱属于更大范围，不能用几个配置项假装完成。

## 3. 每次阅读的笔记模板

```text
类/方法：
输入：
输出：
读写的数据：
调用的外部系统：
成功条件：
失败分支：
是否可重试：
幂等依据：
当前证据：静态 / 单测 / 集成 / E2E / 故障注入 / 压测
仍未确认：
```

## 4. 第一轮面试自检

必须能准确回答：

- 为什么 RabbitMQ 只传 submission ID？题目在提交后被修改会怎样？
- 数据库保存成功但 MQ 发布失败，为什么会永久 WAITING？
- 手动时间线中的 publisher confirm、consumer ACK 和业务状态分别解决什么？
- 两个消费者为什么可能重复执行？条件更新如何取得执行权？
- RUNNING 状态为什么需要租约或恢复扫描？
- 沙箱 `status`、提交 `status` 和 `judgeInfo.message` 为什么不能混用？
- 当前编译错误为什么可能变成系统 FAILED，而不是 Compile Error？
- 为什么当前 TLE/MLE 结论不可信？
- 为什么提交 API QPS 不等于真实判题吞吐？
- 哪些代码来自项目基线，哪些是自己的修改，分别有什么证据？
