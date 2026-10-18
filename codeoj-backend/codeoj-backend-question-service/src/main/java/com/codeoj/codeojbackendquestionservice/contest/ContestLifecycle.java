package com.codeoj.codeojbackendquestionservice.contest;

import com.codeoj.codeojbackendquestionservice.submission.ExecutionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="codeoj.reliable-judge.enabled",havingValue="true")
public class ContestLifecycle {
    private final JdbcTemplate jdbc;private final ExecutionRepository executions;private final ContestTransactions transactions;
    public ContestLifecycle(JdbcTemplate jdbc,ExecutionRepository executions,ContestTransactions transactions) { this.jdbc=jdbc;this.executions=executions;this.transactions=transactions; }
    @Scheduled(fixedDelay=1000)
    public void tick() {
        for(long id:jdbc.queryForList("SELECT id FROM contest WHERE status='PUBLISHED' AND endAt<=? ORDER BY endAt LIMIT 50",Long.class,executions.now())) {
            transactions.finalizeIfDue(id);
        }
    }
}
