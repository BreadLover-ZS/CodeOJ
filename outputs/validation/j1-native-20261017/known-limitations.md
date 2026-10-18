# Known limitations

- These are J0/J1 functional results, not completion of J0-J6 or JV01-JV29.
- Existing full-context test failed before changes because Redis localhost:6379 was unavailable. It remains opt-in and is not counted as passing.
- The first offline baseline/build attempts could not resolve missing cached Maven plugins. Missing artifacts were subsequently downloaded; Java/Spring versions were not upgraded.
- Restricted execution denied loopback socket binding and process observation. The unchanged functional tests passed in the permitted local environment.
- A frontend formatting error introduced while labelling unsupported stack limits was corrected and the final frontend build passed.
- Native RSS is sampled, approximate and direct-process-only. Missing measurements remain unknown. No container security or full descendant cleanup claim.
- Unclassified native nonzero exits remain platform errors; trustworthy RE attribution remains incomplete.
- Original question JSON configs were not migrated. Review historical time/memory limits before using them with measured execution.
- Database/MQ chain, frontend browser/API flow, Outbox, leases, registration and scoreboard have not been verified or implemented by this increment.
- Deployment and load tests are deferred by user instruction.
- Legacy Spring Cloud BOM placement and existing frontend dependency/bundle warnings remain; no dependency upgrade was included.
