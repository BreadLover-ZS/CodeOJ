package com.codeoj.codeojbackendquestionservice.contest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

@Component @Slf4j
@ConditionalOnExpression("${codeoj.reliable-judge.enabled:false} && ${codeoj.registration.enabled:false}")
public class RegistrationMaintenance {
    private final JdbcTemplate jdbc;private final RegistrationTransactions transactions;private final RegistrationService service;private final RedisRegistrationLedger ledger;
    private long cursor;
    public RegistrationMaintenance(JdbcTemplate jdbc,RegistrationTransactions transactions,RegistrationService service,RedisRegistrationLedger ledger) { this.jdbc=jdbc;this.transactions=transactions;this.service=service;this.ledger=ledger; }
    @Scheduled(fixedDelay=1000)
    public void tick() {
        List<Long> contests=jdbc.queryForList("SELECT id FROM contest WHERE registrationMode LIKE 'REDIS_%' AND id>? ORDER BY id LIMIT 20",Long.class,cursor);
        if(contests.isEmpty()) { cursor=0;return; }
        for(long id:contests) { cursor=id;try { maintain(id); }catch(RuntimeException unknown) { log.warn("Registration recovery unavailable: contestId={}",id); } }
    }
    public void maintain(long contest) {
        RegistrationSettings s=transactions.settings(contest);if(!s.isInitialized()) { return; }
        try {
            int known=jdbc.queryForObject("SELECT COUNT(*) FROM contest_registration_request WHERE contestId=? AND reservationEpoch=?",Integer.class,contest,s.getEpoch());
            if(known>ledger.stats(contest).getInt("count")) { throw new RedisRegistrationLedger.LedgerException("LEDGER_DATABASE_MISMATCH"); }
            ledger.gate(s,s.gateOpen(),false);
            for(RegistrationReservation r:ledger.due(contest)) { service.persist(r); }
            for(long id:jdbc.queryForList("SELECT id FROM contest_registration_request WHERE contestId=? AND reservationEpoch IS NOT NULL AND (nextActionAt IS NULL OR nextActionAt<=?) AND status IN ('PROCESSING','CONFIRMED','FAILED','CANCELLED'"
                    +("CANCELLED".equals(s.getStatus())?",'MANUAL_REVIEW'":"")+") AND reclaimState NOT IN ('COMPLETE','RELEASED') ORDER BY nextActionAt,id LIMIT 20",Long.class,contest,s.getNow())) {
                transactions.recover(contest,id);service.synchronize(transactions.request(contest,id,false));
            }
        } catch(RedisRegistrationLedger.LedgerException broken) {
            if("STALE_GATE_REVISION".equals(broken.getCode())) { return; } // Another authority revision already fenced this tick.
            service.hold(contest,broken.getCode());throw broken;
        }
    }
}
