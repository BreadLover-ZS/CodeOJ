# JV01—JV29 本机验收矩阵

26 PASS / 1 FAIL / 2 UNTESTED。PASS 仅指表中已执行边界；JV14 纳入扩展的撤权检查。

| 编号 | 结果 | 已执行证据与边界 |
|---|---|---|
| JV01 | PASS | 实际 HTTP 相同键同 ID、改代码 409，MySQL 仅一条受理事实。 |
| JV02 | PASS | 真实 API 在提交前/后 SIGKILL；前者事务回滚，后者持久 Outbox 在恢复后完成。 |
| JV03 | PASS | 真实不可路由 Return、代理丢 Confirm 帧、Broker 断连均记录并恢复；UNKNOWN 不冒充失败。 |
| JV04 | PASS | 两个真实 judge 进程、重复 Broker 投递；观察到 InnoDB 锁等待，最终一个有效 attempt。以 SLEEP(2) 纠正首轮 SLEEP(5) 超过 Feign 超时的夹具问题。 |
| JV05 | PASS | RUNNING worker SIGKILL，真实租约过期接管；旧 token 心跳/结果均 false；总计两个 attempt。 |
| JV06 | PASS | 结果已提交，代理丢消费者 ACK，Broker 确认仍 unacked 后杀 worker；重投终态不重判/不多建结果事件。等效验证 Broker 未收到 ACK 的窗口，未声称断在 basicAck 调用之前。 |
| JV07 | PASS | 本轮真实 javac/java 测试覆盖死循环、stderr 刷屏、阻塞 stdin、双流与工作目录清理；范围为直接 JVM，非任意进程树。 |
| JV08 | UNTESTED | 仅 RSS 观测/超限有本轮实际测试；PID、网络、文件与容器级硬隔离未实现，本机可信输入边界后置。 |
| JV09 | PASS | 8 个真实用户并发争 2 名额；MySQL 资格 2、剩余 0、原键请求身份稳定，Redis 账本确认。属于功能竞争，不是压测。 |
| JV10 | PASS | Redis 已预留、DB 插入受阻时杀 API；重启以原 requestId/eventId 补建并确认。 |
| JV11 | PASS | 实际停 Redis，确认事务已提交且标记未完成；另在 FAILED 提交前停 Redis，释放待办持久保留；恢复后仅确认/释放一次。 |
| JV12 | PASS | 真实绑定控制消费晚于关闭时间；原有效预留确认，新请求 409。 |
| JV13 | PASS | 真实 MySQL 取消与报名 HTTP/提交竞争；本次取消获胜，取消后无新资格/受理，新键 409。未声称穷举全部调度顺序。 |
| JV14 | FAIL | 私有题面、他人提交、admin=true、内部入口均拒绝越权；扩展撤权检查发现 F03：旧 Session 仍可读取普通题隐藏用例并更新题目，因此权限项保守记 FAIL。 |
| JV15 | PASS | 独立实例如实时钟赛中受理，暂停 judge 后编辑源题，再启动执行：比赛固定快照 AC、新普通练习 WA；标题/hash 不变，练习 acceptedNum 未受比赛影响。 |
| JV16 | PASS | 真实投影与 Broker 消费验证重复 AC、较早提交迟到；用于精确并列的五条 acceptedAt 由隔离 DB 显式塑形，非自然 HTTP 时间证据。 |
| JV17 | PASS | 一条自然 acceptedAt 在截止前受理、真实判题跨截止；pending 时结束 409；终态后最终版本 2。另基线验证原键重放和新键拒绝。 |
| JV18 | PASS | 真实缓存 epoch 清理，MySQL 权威重建与手算一致；零分、并列样本时间使用显式夹具。 |
| JV19 | PASS | 真实沙箱停机耗尽 3 次预算进入 NEEDS_ATTENTION；未加额度重试 409，显式 +1 审计后第 4 次 AC。 |
| JV20 | PASS | 真实旧调度消息在新代次执行后再投递，旧 token/代次不能领取或覆盖；有效 attempt 未增加。 |
| JV21 | PASS | 真实 Broker 已 ACK 而 judge 停止超过扫描阈值；业务恢复增加代次后完成一次有效执行。 |
| JV22 | PASS | DB FAILED 先提交、Redis 释放后投原迟到事件及重复事件不确认；Redis 故障窗口待释放状态恢复后收敛。 |
| JV23 | PASS | 真实取消事务提交前停 Redis；DB CANCELLED 时迟到消费者不发新资格；恢复后关闸/释放完成，新报名 409。 |
| JV24 | PASS | 重复 reconcile 不重置；部分/全部账本丢失返回 503 保持暂停；实际 MySQL 连接时钟正负 10 秒偏差均暂停预留（未改宿主/Redis 时钟）。 |
| JV25 | UNTESTED | 本轮旧 token、worker SIGKILL 有据；跨沙箱管理器/重启的持久运行登记与任意残留进程树回收未实现，完整物理执行约束后置。 |
| JV26 | PASS | 真实同用户两题同时 RUNNING 并完成，积分不丢；4 用户并列/零分跨页名次正确。精确并列时间为显式 DB 夹具。 |
| JV27 | PASS | 真实重建中 SQL 失败回滚版本 1；首次迟到成绩投递与重建竞争，读者仅见完整版本 2；终态/收据一致。 |
| JV28 | PASS | 本轮持久账本 TTL=-1；基线真实 Redis AOF 重启恢复。非 TTL 等待演示，保留策略不自动归档。 |
| JV29 | PASS | 真实 API 排队旧普通练习后改原题，新旧提交分别 WA/AC，私有判题 hash 不同。 |
