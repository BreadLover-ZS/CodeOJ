# Evidence boundaries

PASS: 186 Java8 functional tests; fresh real MySQL8.0.46 V001-V004 migrations; actual Redis6.2.24, RabbitMQ4.3.6, five services; static discovery + Gateway + Feign + Spring Session; remote native Java AC/WA/CE; actual queue/broker/worker restart, duplicate message, sandbox retry, Redis session 503/AOF recovery, browser admin reconciliation, finalization and cutoff/idempotency checks.

PASS: Real Chrome/HTTP browser eight scenarios, zero page errors. Most recent browser submission AC independently read from MySQL, not inferred from the page's general scoring text. Six submissions completed, six score receipts, no pending verdicts/projection events, fixed finalVersion2.

Historical FAIL runs retained: run-01/03 CGLIB repository-field access, run-02 RabbitMQ readiness race, run-04 missing pre-MVC session availability boundary, run-05 stale embedded shared jar, run-06 missing explicit admin reconciliation after a conservative registration pause. Final successful run is run-07 only.

UNTESTED: Nacos (fixed SimpleDiscoveryClient instances used), Redis Cluster, replication/multi-node/failover, complete JV01-JV29 matrix, all real confirm/return/unknown combinations, disk/power-loss durability, full MySQL deadlock matrix, hostile code isolation and orphan cleanup, deployment/load/production acceptance.

Versions are fixed for this native macOS arm64 fixture. Homebrew Erlang/OpenSSL copies are relocated and ad-hoc signed only in the temporary directory. Original OpenSSL and global services remain unchanged. Early third-party logs may append to existing user diagnostic directories; those pre-existing logs were not deleted. Final runner redirects its third-party Java logs into its private directory.

Build artifacts and existing default service switches remain distinct from local test settings. No commit/push in this development turn. Original output/ was neither traversed nor changed.
