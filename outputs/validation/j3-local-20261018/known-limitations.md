# Evidence scope

PASS: 106 functional tests, Java 8 packaging, frontend build, six mocked HTTP browser interactions, H2 contest + real native AC demo with manual outbox dispatch.

UNTESTED: actual MySQL V001/V002 migration/collations/InnoDB isolation/deadlocks; real session/gateway/Nacos/Redis/RabbitMQ/Feign/remote sandbox business chain; old queue cutover; cross-manager/restart recovery and hostile-code isolation. Browser mocks and manual outbox dispatch do not establish actual service acceptance.

PENDING: J4 Redis registration and recovery, J5 scoreboard/final settlement. Deployment/Docker/load testing deferred by user. Reliable judge flag stays false by default. No commit or push.

Source snapshot is cumulative from the base commit (J0-J3), excludes all output/ and outputs/ contents, and excludes test target artifacts. Full reactor run was followed by the new gateway contract and final 22 contest tests; the demo rebuilt final Java sources. Stale infrastructure XML reports (including the earlier Redis-blocked context) are retained separately in test-results.json and are not executions from this run.
