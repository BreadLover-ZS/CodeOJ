# JV01—JV29 当前本机隔离链路复验

29 PASS / 0 FAIL / 0 BLOCKED / 0 UNTESTED，限本轮固定环境与已执行样例。

| ID | 状态 | 证据分组 |
| --- | --- | --- |
| JV01 | PASS | business: submission-idempotency |
| JV02 | PASS | business: api-crash-commit-windows |
| JV03 | PASS | business: publisher-return-confirm-loss-disconnect |
| JV04 | PASS | business: two-consumers-precise-lock-observer |
| JV05 | PASS | business: worker-sigkill-token-generation-fence |
| JV06 | PASS | business: commit-before-ack-redelivery |
| JV07 | PASS | isolation: real-java-and-cgroup-metrics; isolation: wall-deadline; isolation: stdout-budget; isolation: stderr-budget; isolation: stdin-not-read |
| JV08 | PASS | isolation: network-denied; isolation: nonroot-no-capabilities-no-docker-socket; isolation: readonly-root-artifacts-and-private-receipt; isolation: bounded-tmpfs; isolation: pid-cgroup-limit-and-descendant-cleanup; isolation: cgroup-memory-limit; isolation: case-filesystem-isolation; cpu: cpu; pid-kernel: pid |
| JV09 | PASS | business: limited-registration |
| JV10 | PASS | business: reservation-api-crash-before-db |
| JV11 | PASS | business: confirmation-marker-failure; business: redis-down-confirmation-and-release |
| JV12 | PASS | business: reservation-before-close-consume-after |
| JV13 | PASS | business: registration-cancel-race; business: submission-cancel-race |
| JV14 | PASS | business: private-snapshot-and-submission-permissions |
| JV15 | PASS | business: accepted-snapshot-edited-before-execution |
| JV16 | PASS | business: score-race-rebuild-finalization |
| JV17 | PASS | business: score-race-rebuild-finalization |
| JV18 | PASS | business: score-race-rebuild-finalization |
| JV19 | PASS | business: budget-exhaustion-manual-recovery |
| JV20 | PASS | business: worker-sigkill-token-generation-fence |
| JV21 | PASS | business: confirmed-unclaimed-recovery |
| JV22 | PASS | business: redis-down-confirmation-and-release; business: late-confirm-after-release |
| JV23 | PASS | business: cancel-committed-redis-down |
| JV24 | PASS | business: ledger-initialization-loss-persistence; business: complete-redis-ledger-loss; business: mysql-clock-skew-both-directions |
| JV25 | PASS | isolation: pid-cgroup-limit-and-descendant-cleanup; isolation: setsid-descendants-cleaned-on-normal-exit; isolation: shared-slot-token-dedup-and-restart; isolation: sigkill-manager-independent-deadline-recovery |
| JV26 | PASS | business: score-race-rebuild-finalization |
| JV27 | PASS | business: score-race-rebuild-finalization |
| JV28 | PASS | business: ledger-initialization-loss-persistence |
| JV29 | PASS | business: practice-spec-frozen-at-acceptance |
