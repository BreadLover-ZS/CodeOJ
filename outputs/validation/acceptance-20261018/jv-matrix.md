# JV01—JV29 验收映射

基线：`5d6333e0bce3f1112c790b0c4824e5dd2fb3a6de`。日期：2026-10-18。

组件列为本轮重跑的代码测试证据；真实场景列按用例的完整触发条件判定。H2、mock Channel/Template、mock浏览器通过不提升为真实MySQL/MQ故障通过。UNTESTED表示未执行或证据不足，不表示产品已被证明出错。两个额外判题FAIL见主报告，不能因29项表内没有新增FAIL便宣布通过。

| ID | 要求 | 组件验证 | 真实场景 | 已有证据与缺口 |
| --- | --- | --- | --- | --- |
| JV01 | 相同提交键回原ID，不同内容409 | PASS | PASS | 实际HTTP 202/409；H2并发唯一事实；最终MySQL事实数核对 |
| JV02 | 提交事务前/后杀API | PASS | UNTESTED | H2事务回滚已测；未在两个提交窗口杀真实API |
| JV03 | MQ断连、Confirm丢失、不可路由 | PASS | UNTESTED | 替身确认/Return/UNKNOWN已测；正常Broker重启已测；未逐一注入真实确认丢失与路由失败 |
| JV04 | 两个消费者同时领取同一消息 | PASS | UNTESTED | H2同时claim只得一个租约；实际Broker重复投递已测，未做两个真实消费者同时竞争 |
| JV05 | RUNNING杀worker后接管，拒绝旧token | PASS | UNTESTED | H2租约过期/代次/旧token通过；实际沙箱不可用恢复通过，未杀RUNNING worker并验证迟到写回 |
| JV06 | 结果提交后ACK前杀worker | PASS | UNTESTED | H2完成幂等与真实重复消息通过；未在指定ACK窗口杀worker |
| JV07 | 死循环、stderr刷屏、stdin不读 | PASS | PASS | 真实javac/java受控样例通过；仅直接JVM/流/临时文件范围，不外推任意后代回收 |
| JV08 | 内存、PID、网络、文件限制 | UNTESTED | UNTESTED | RSS观测超限有证据；PID/CPU硬限制、网络/文件隔离未实现；按已确认范围后置 |
| JV09 | N>M并发报名与重复混合 | PASS | UNTESTED | 真实Redis+H2中8人争2名额通过；本轮MySQL仅1参赛者，未做InnoDB/MQ组合竞争 |
| JV10 | Redis预留后DB前杀API | PASS | UNTESTED | 真实Redis账本补建/AOF通过；DB侧H2，未杀真实API卡在该窗口 |
| JV11 | DB确认后标记失败、回收时Redis失败 | PASS | UNTESTED | 真实Lua+H2故障标记测试通过；未在MySQL/MQ链逐一注入这些窗口 |
| JV12 | 关闭前预留，关闭后确认 | PASS | UNTESTED | 真实Redis+H2跨结束时间确认通过；未做真实Broker延迟到报名关闭后消费 |
| JV13 | 取消与确认/提交竞争 | PASS | UNTESTED | H2事务并发和Redis取消栅栏通过；真实MySQL取消竞争未执行 |
| JV14 | 权限、他人代码、私有赛题与旧接口 | PASS | PASS | 实际匿名401、他人提交403、伪管理标记403、内部接口双层403、旁观者无题面；专用题不入普通题表 |
| JV15 | 编辑原题后仍按发布快照判题 | PASS | UNTESTED | H2固定发布快照通过；真实MySQL赛中编辑原题场景未执行 |
| JV16 | 重复AC、较早提交迟到 | PASS | UNTESTED | 独立手算与H2重复/乱序通过；真实多题多用户迟到矩阵未执行 |
| JV17 | 截止前受理、截止后完成与最终结算 | PASS | UNTESTED | H2赛后完成通过；真实到期/原键重放/新键409/结算通过，未刻意让执行跨截止线 |
| JV18 | 清缓存与权威重建一致 | PASS | UNTESTED | H2清投影含零分重建及缓存替身通过；真实MySQL重建通过，未以多题并列样本清真实缓存对照 |
| JV19 | 平台失败到有限上限和人工审计 | PASS | UNTESTED | H2预算耗尽/显式追加/审计通过；真实沙箱恢复只覆盖一次失败，不覆盖全额度耗尽 |
| JV20 | 旧调度代次迟到 | PASS | UNTESTED | H2旧代次/token拒绝和恢复幂等通过；真实Broker旧代次迟到未执行 |
| JV21 | 已Confirm但长期QUEUED | PASS | UNTESTED | H2业务扫描重派通过；真实短暂停judge队列恢复通过，未覆盖超过扫描阈值的重派 |
| JV22 | 终止回收与迟到确认竞争 | PASS | UNTESTED | 真实Redis+H2释放幂等与确认互斥通过；实际MySQL/MQ竞争窗口未执行 |
| JV23 | 取消已提交而Redis关闸失败 | PASS | UNTESTED | H2/Redis权威取消和延迟闸门栅栏通过；真实联合故障未执行 |
| JV24 | 重复初始化、未决预留、时钟偏差 | PASS | UNTESTED | 真实Redis+H2初始化/缺失/旧账本/双向时钟偏差通过；真实联合组合未执行 |
| JV25 | slot耗尽、失联旧沙箱、残留回收 | PASS（部分） | UNTESTED | 本进程slot/token去重和DB旧token有测试；跨重启注册表不持久，任意后代/跨管理器残留回收未完成 |
| JV26 | 同用户两题同时AC、跨页并列与零分 | PASS | UNTESTED | H2事务多题并发、手算与跨页排名通过；真实MySQL多用户多题并发未执行 |
| JV27 | 重建遇迟到消息与旧缓存 | PASS | UNTESTED | H2重建竞争/回滚/版本切换及缓存契约通过；真实最终重建通过，未复现该竞争组合 |
| JV28 | 未决账本不受普通缓存TTL清空 | PASS | PASS | 真实Redis确认五个键TTL=-1；源码无expire；AOF重启保留；归档策略为保留，未自动清理 |
| JV29 | 普通练习排队后编辑原题 | PASS | UNTESTED | H2练习私有规格与幂等通过；本轮实际网站演示是比赛提交，未重跑真实普通练习编辑链 |

组件明细见 `backend-test-results.json` 中23个本次实际执行类，包括 ReliableExecutionTest、ContestCoreTest、RegistrationRedisIntegrationTest、ScoreboardTest、JavaNativeCodeSandboxTest 及消息/HTTP/缓存契约测试。真实HTTP见 `live-contract-results.json`；实际业务时间线和状态见 `native/run.json`、`native/database-facts.json`。
