package com.codeoj.codeojbackendquestionservice.submission.outbox;

import com.codeoj.codeojbackendquestionservice.submission.ExecutionRepository;
import lombok.Data;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class OutboxStore {
    private final JdbcTemplate jdbc;
    private final ExecutionRepository repository;
    public OutboxStore(JdbcTemplate jdbc,ExecutionRepository repository) { this.jdbc=jdbc;this.repository=repository; }
    @Data public static class Publication {
        private String eventId;
        private String token;
        private String payload;
    }
    public List<String> due() {
        return due("JUDGE_DISPATCH");
    }
    private void type(String type) { if(!Arrays.asList("JUDGE_DISPATCH","REGISTRATION_CONFIRM").contains(type)) { throw new IllegalArgumentException("UNSUPPORTED_OUTBOX_TYPE"); } }
    public List<String> due(String type) {
        type(type);
        LocalDateTime now=repository.now();
        return jdbc.queryForList("SELECT eventId FROM reliable_outbox WHERE eventType=? AND publishState='PENDING'"
                +" AND nextRetryAt<=? AND (leaseUntil IS NULL OR leaseUntil<=?)"
                +("REGISTRATION_CONFIRM".equals(type)?" AND EXISTS(SELECT 1 FROM contest_registration_request r WHERE r.id=reliable_outbox.aggregateId AND r.status='PROCESSING')":"")
                +" ORDER BY nextRetryAt LIMIT 20",String.class,type,now,now);
    }
    @Transactional(rollbackFor=Exception.class)
    public Publication claim(String eventId) {
        return claim(eventId,"JUDGE_DISPATCH");
    }
    @Transactional(rollbackFor=Exception.class)
    public Publication claim(String eventId,String type) {
        type(type);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT eventId FROM reliable_outbox WHERE eventId=? FOR UPDATE",eventId);
        if(rows.isEmpty()) { return null; }
        LocalDateTime now=repository.now();
        List<Publication> candidates=jdbc.query("SELECT eventId,payload FROM reliable_outbox WHERE eventId=? AND eventType=?"
                +" AND publishState='PENDING' AND nextRetryAt<=? AND (leaseUntil IS NULL OR leaseUntil<=?)",(rs,row)->{
                    Publication p=new Publication();p.setEventId(rs.getString("eventId"));p.setPayload(rs.getString("payload"));return p;
                },eventId,type,now,now);
        if(candidates.isEmpty()) { return null; }
        int attempts=jdbc.queryForObject("SELECT attempts FROM reliable_outbox WHERE eventId=?",Integer.class,eventId);
        int budget=20;
        if("REGISTRATION_CONFIRM".equals(type)) {
            List<Integer> budgets=jdbc.queryForList("SELECT maxPublishAttempts FROM contest_registration_request WHERE eventId=? AND status='PROCESSING'",Integer.class,eventId);
            if(budgets.isEmpty()) { return null; }budget=budgets.get(0);
        }
        if(attempts>=budget) {
            jdbc.update("UPDATE reliable_outbox SET publishState='ATTENTION',leaseToken=NULL,leaseUntil=NULL,lastError='PUBLISH_BUDGET_EXHAUSTED' WHERE eventId=?",eventId);
            return null;
        }
        // An elapsed confirm lease is still UNKNOWN evidence, not proof the broker rejected it.
        jdbc.update("UPDATE publish_attempt SET errorCode='CONFIRM_TIMEOUT' WHERE eventId=? AND confirmState='UNKNOWN' AND errorCode IS NULL",eventId);
        Publication publication=candidates.get(0);publication.setToken(UUID.randomUUID().toString());
        jdbc.update("UPDATE reliable_outbox SET attempts=attempts+1,leaseToken=?,leaseUntil=?,nextRetryAt=? WHERE eventId=?",
                publication.getToken(),now.plusSeconds(15),now.plusSeconds(15),eventId);
        jdbc.update("INSERT INTO publish_attempt(eventId,attemptNo,attemptToken,confirmState,returned,createdAt) VALUES (?,?,?,'UNKNOWN',false,?)",
                eventId,attempts+1,publication.getToken(),now);
        return publication;
    }
    @Transactional(rollbackFor=Exception.class)
    public void record(String eventId,String token,String confirmation,boolean returned,String error) {
        if(!Arrays.asList("ACK","NACK","UNKNOWN").contains(confirmation)) { throw new IllegalArgumentException("INVALID_CONFIRM_STATE"); }
        List<String> events=jdbc.queryForList("SELECT eventId FROM reliable_outbox WHERE eventId=? FOR UPDATE",String.class,eventId);
        if(events.isEmpty()) { return; }
        List<String> attempts=jdbc.queryForList("SELECT confirmState FROM publish_attempt WHERE eventId=? AND attemptToken=?",String.class,eventId,token);
        if(attempts.isEmpty()) { return; }
        if(!"UNKNOWN".equals(attempts.get(0))) { return; } // Definitive per-attempt evidence is immutable.
        if("UNKNOWN".equals(attempts.get(0))) {
            jdbc.update("UPDATE publish_attempt SET confirmState=?,returned=?,errorCode=? WHERE eventId=? AND attemptToken=?",
                    confirmation,returned,error,eventId,token);
        }
        if("ACK".equals(confirmation) && !returned) {
            jdbc.update("UPDATE reliable_outbox SET publishState='CONFIRMED',leaseToken=NULL,leaseUntil=NULL,lastError=NULL WHERE eventId=?",eventId);
        } else {
            // A late failure never revokes another attempt's ACK or releases a newer publisher lease.
            jdbc.update("UPDATE reliable_outbox SET leaseToken=NULL,leaseUntil=NULL,nextRetryAt=?,lastError=?"
                    +" WHERE eventId=? AND publishState='PENDING' AND leaseToken=?",repository.now().plusSeconds(2),
                    error==null?"BROKER_NACK":error,eventId,token);
        }
    }
}
