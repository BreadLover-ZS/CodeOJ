# Evidence boundaries

- 83 passing functional tests are not J2/MySQL/RabbitMQ production acceptance.
- H2 checks transactions and finite state behavior. It does not prove MySQL collation, isolation, driver UTC conversion, lock ranges or crash durability.
- RabbitTemplate/Feign/Channel doubles check contracts. Real Confirm loss, unroutable delivery, DLX routing and broker outages are untested.
- New path is disabled by default. V001 and old-queue cutover have not been executed. Blindly disabling the feature after new data is not a safe rollback.
- Result events are persisted for J5; no score projection consumer is implemented yet.
- Native token deduplication is in-memory within one live manager. Cross-restart execution registration/cleanup and arbitrary descendant termination remain incomplete.
- Native RSS is sampled for the direct JVM; it is not a cgroup memory measurement or malicious-code isolation proof.
- Frontend build passed with warnings. Real browser/session/API retry behavior has not been exercised.
- Admin diagnostic API exists; the complete admin UI remains future work.
- J3 contest rules/qualification, J4 limited registration and J5 scoreboard are not implemented by this increment.
- Deployment, Docker environment setup and load/capacity testing are explicitly deferred.
- Original untracked output/ was not read, modified or removed.
