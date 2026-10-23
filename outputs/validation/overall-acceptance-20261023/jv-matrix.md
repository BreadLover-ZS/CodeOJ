# JV01—JV29 本轮验收矩阵

基线提交 `19f77fc11c50aac34fa189a7b39bcb27fad9b139`。按本轮真实业务与隔离检查生成：29 PASS / 0 FAIL / 0 UNTESTED。

| ID | 场景 | 状态 | 本轮证据分组 |
| --- | --- | --- | --- |
| JV01 | 同幂等键重发提交；再换代码重发 | PASS | business-matrix-02: submission-idempotency |
| JV02 | 提交事务提交前/后分别杀API | PASS | business-matrix-02: api-crash-commit-windows |
| JV03 | MQ断连、Confirm丢失、不可路由 | PASS | business-matrix-02: publisher-return-confirm-loss-disconnect |
| JV04 | 两消费者同时处理同一消息 | PASS | business-matrix-02: two-consumers-precise-lock-observer |
| JV05 | RUNNING时杀worker并重启 | PASS | business-matrix-02: worker-sigkill-token-generation-fence |
| JV06 | 结果事务后、ACK前杀worker | PASS | business-matrix-02: commit-before-ack-redelivery |
| JV07 | 无输出死循环、stderr刷屏、stdin不读 | PASS | isolation-probes: real-java-and-cgroup-metrics; isolation-probes: wall-deadline; isolation-probes: stdout-budget; isolation-probes: stderr-budget; isolation-probes: stdin-not-read |
| JV08 | 内存、PID、网络、文件越界样例 | PASS | isolation-probes: network-denied; isolation-probes: nonroot-no-capabilities-no-docker-socket; isolation-probes: readonly-root-artifacts-and-private-receipt; isolation-probes: bounded-tmpfs; isolation-probes: pid-cgroup-limit-and-descendant-cleanup; isolation-probes: cgroup-memory-limit; isolation-probes: cpu-quota-throttling; isolation-probes: case-filesystem-isolation |
| JV09 | M名额、N>M个用户、重复请求混合 | PASS | business-matrix-02: limited-registration |
| JV10 | Redis预留后、DB落盘前杀API | PASS | business-matrix-02: reservation-api-crash-before-db |
| JV11 | DB确认后Redis标记失败；回收时Redis失败 | PASS | business-matrix-02: confirmation-marker-failure; business-matrix-02: redis-down-confirmation-and-release |
| JV12 | 报名关闭前预留、关闭后消费 | PASS | business-matrix-02: reservation-before-close-consume-after |
| JV13 | 取消与报名消费、取消与提交并发 | PASS | business-matrix-02: registration-cancel-race; business-matrix-02: submission-cancel-race |
| JV14 | 未报名/他人账号/普通API绕过赛题 | PASS | business-matrix-02: private-snapshot-and-submission-permissions |
| JV15 | 赛中编辑原题，再执行既有提交 | PASS | business-matrix-02: accepted-snapshot-edited-before-execution |
| JV16 | 同题多次AC，较早提交结果更晚到 | PASS | business-matrix-02: score-race-rebuild-finalization |
| JV17 | 截止前受理、截止后完成 | PASS | business-matrix-02: score-race-rebuild-finalization |
| JV18 | 清空榜单缓存，重建投影 | PASS | business-matrix-02: score-race-rebuild-finalization |
| JV19 | 基础设施反复失败到重试上限 | PASS | business-matrix-02: budget-exhaustion-manual-recovery |
| JV20 | 旧dispatchGeneration消息在新代次到达 | PASS | business-matrix-02: worker-sigkill-token-generation-fence |
| JV21 | Broker已Confirm但任务长期QUEUED未领取 | PASS | business-matrix-02: confirmed-unclaimed-recovery |
| JV22 | 终止/回收与迟到确认消费者竞争 | PASS | business-matrix-02: redis-down-confirmation-and-release; business-matrix-02: late-confirm-after-release |
| JV23 | 取消已提交但Redis关闸失败 | PASS | business-matrix-02: cancel-committed-redis-down |
| JV24 | 重复初始化、恢复时存在未决预留/时钟偏差 | PASS | business-matrix-02: ledger-initialization-loss-persistence; business-matrix-02: complete-redis-ledger-loss; business-matrix-02: mysql-clock-skew-both-directions |
| JV25 | slot耗尽、旧沙箱失联后仍运行 | PASS | isolation-probes: pid-cgroup-limit-and-descendant-cleanup; isolation-probes: setsid-descendants-cleaned-on-normal-exit; isolation-probes: shared-slot-token-dedup-and-restart; isolation-probes: sigkill-manager-independent-deadline-recovery |
| JV26 | 同用户两题同时AC、并列跨页与零分用户 | PASS | business-matrix-02: score-race-rebuild-finalization |
| JV27 | 最终重建期间迟到消息和旧版本缓存 | PASS | business-matrix-02: score-race-rebuild-finalization |
| JV28 | 报名在途账本到达普通缓存TTL | PASS | business-matrix-02: ledger-initialization-loss-persistence |
| JV29 | 普通练习提交排队后管理员修改题目 | PASS | business-matrix-02: practice-spec-frozen-at-acceptance |

业务 JSON：[matrix-cases.json](business-matrix-02/matrix-cases.json)；隔离 JSON：[isolation-results.json](isolation-probes/isolation-results.json)。

精确并列排名使用明确标注的临时数据库 acceptedAt 夹具；跨截止时间完成另有真实受理/完成时间证据。功能并发样例不作为负载性能结论。
