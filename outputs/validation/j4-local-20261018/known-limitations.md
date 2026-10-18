# Scope

PASS: 142 local backend functional tests, including 29 real Redis6.2.24/Lua/AOF + H2 transaction tests and 7 mock messaging tests; Java8 packaging; frontend build; 9 explicitly mocked HTTP browser scenarios; existing real native Java AC demo using H2/manual dispatch.

UNTESTED: actual MySQL8 V001-V003 migration/collations/locking/isolation/deadlocks; actual RabbitMQ delivery/Confirm/Return/DLQ/restart; Redis Cluster; full session/gateway/Nacos/Feign/remote sandbox business chain. AOF restart is not proof of lossless recovery after total Redis data loss. Unknown pre-DB reservations lost with the entire Redis ledger cannot be guaranteed recoverable.

Explicit functional bounds: max10000 ledger records and 5-second scan for online manual reconciliation; larger/incomplete snapshots remain paused. No automatic ledger TTL/cleanup; archive policy remains future work. Publishing has finite budgets; recovery of an old reservation does not reset its original deadline. Manual processing preserves original IDs and old attempt evidence.

PENDING: J5 scoreboard/projection/final settlement; original sandbox isolation and cross-manager/restart recovery boundaries. Docker, deployment and load tests deferred by user. Reliable/async registration flags remain false by default. No commit or push. Original output/ not read or modified.

Source snapshot is cumulative J0-J4 against the base commit and excludes all output/ and outputs/ contents. Current suite aggregation uses names actually executed in the full log; final registration XML comes from the later targeted 36-test rerun. Stale infrastructure XML, including prior Redis-blocked context output, is separately identified in test-results.json.
