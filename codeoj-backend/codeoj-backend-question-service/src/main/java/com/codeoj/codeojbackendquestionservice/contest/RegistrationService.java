package com.codeoj.codeojbackendquestionservice.contest;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendquestionservice.submission.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Supplier;

/** No transaction spans the Redis -> DB durability gap. Original identity survives every retry. */
@Service
public class RegistrationService {
    private final ContestTransactions contests;private final RegistrationTransactions transactions;private final RedisRegistrationLedger ledger;
    private final ExecutionRepository executions;private final JdbcTemplate jdbc;
    @Value("${codeoj.registration.enabled:false}") private boolean enabled;
    public RegistrationService(ContestTransactions contests,RegistrationTransactions transactions,RedisRegistrationLedger ledger,ExecutionRepository executions,JdbcTemplate jdbc) {
        this.contests=contests;this.transactions=transactions;this.ledger=ledger;this.executions=executions;this.jdbc=jdbc;
    }
    public void requireEnabled() { if(!enabled) { throw unavailable(); } }
    static BusinessException unavailable() { return new BusinessException(ErrorCode.SYSTEM_ERROR,"REGISTRATION_STATE_UNAVAILABLE"); }
    public <T> T retry(Supplier<T> call) {
        for(int n=0;n<3;n++) { try { return call.get(); }catch(TransientDataAccessException error) { if(n==2) { throw error; } } }throw new IllegalStateException("UNREACHABLE");
    }
    private Long byUser(long contest,long user) {
        List<Long> ids=jdbc.queryForList("SELECT id FROM contest_registration_request WHERE contestId=? AND userId=?",Long.class,contest,user);return ids.isEmpty()?null:ids.get(0);
    }
    public RegistrationRequest persist(RegistrationReservation r) {
        try { return retry(()->transactions.persist(r)); }
        catch(DuplicateKeyException concurrent) {
            RegistrationRequest existing=transactions.request(r.getContestId(),r.getRequestId(),false);
            if(existing!=null) { return retry(()->transactions.persist(r)); }
            hold(r.getContestId(),"RESERVATION_IDENTITY_CONFLICT");throw unavailable();
        }
    }
    public Map<String,Object> register(long contest,String key,long user) {
        ContestTransactions.requireKey(key);executions.requireAccount(user,null,false);ContestTransactions.Contest c=contests.load(contest,false);
        Long prior=byUser(contest,user);
        if(prior!=null) {
            String original=jdbc.queryForObject("SELECT requestKey FROM contest_registration_request WHERE id=?",String.class,prior);
            if(!key.equals(original)) { throw new SubmissionConflictException("REGISTRATION_ALREADY_HAS_ANOTHER_KEY"); }return contests.registration(contest,user);
        }
        if("DB_SYNC".equals(c.mode)) { return retry(()->contests.register(contest,key,user)); }
        requireEnabled();RegistrationSettings s=transactions.settings(contest);
        try {
            if(!s.isInitialized()) { throw new SubmissionConflictException("REGISTRATION_PAUSED"); }
            RegistrationReservation r=ledger.user(contest,user);
            if(r!=null) {
                if(!key.equals(r.getRequestKey()) || !RegistrationTransactions.hash(contest,s.getPublishedVersion()).equals(r.getRequestHash())) { throw new SubmissionConflictException("REGISTRATION_ALREADY_HAS_ANOTHER_KEY"); }
            } else {
                if(!s.gateOpen()) { throw new SubmissionConflictException("REGISTRATION_PAUSED"); }
                r=ledger.reserve(s,user,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(contest,s.getPublishedVersion()));
            }
            if(r==null || !r.valid()) { throw unavailable(); }persist(r);return contests.registration(contest,user);
        } catch(RedisRegistrationLedger.LedgerException error) { handleLedger(contest,error);throw error; }
        catch(SubmissionConflictException | BusinessException known) { throw known; }
        catch(RuntimeException unknown) { throw unavailable(); } // Preserve Redis reservation on DB/transport uncertainty.
    }
    public Map<String,Object> status(long contest,long user) {
        executions.requireAccount(user,null,false);ContestTransactions.Contest c=contests.load(contest,false);
        if(byUser(contest,user)!=null || "DB_SYNC".equals(c.mode)) { return contests.registration(contest,user); }
        RegistrationSettings s=transactions.settings(contest);if(!s.isInitialized()) { return contests.registration(contest,user); }
        try {
            RegistrationReservation r=ledger.user(contest,user);if(r!=null) { persist(r);return contests.registration(contest,user); }
            return contests.registration(contest,user);
        } catch(RuntimeException unknown) { if(unknown instanceof RedisRegistrationLedger.LedgerException) { hold(contest,((RedisRegistrationLedger.LedgerException)unknown).getCode()); }throw unavailable(); }
    }
    private void handleLedger(long contest,RedisRegistrationLedger.LedgerException error) {
        String code=error.getCode();if(Arrays.asList("CONTEST_FULL","REGISTRATION_WINDOW_CLOSED","REGISTRATION_PAUSED","IDEMPOTENCY_CONFLICT").contains(code)) { throw new SubmissionConflictException(code); }
        hold(contest,code);throw unavailable();
    }
    public void hold(long contest,String code) { transactions.issue(contest,code.matches("[A-Z_]{1,128}")?code:"REGISTRATION_EVIDENCE_UNKNOWN"); }
    public RegistrationSettings prepare(long id,ContestCommand command,long actor) { requireEnabled();return retry(()->transactions.prepare(id,command,actor)); }
    public RegistrationSettings reconcile(long id,ContestCommand command,long actor) {
        requireEnabled();RegistrationSettings s=retry(()->transactions.beginReconcile(id,command,actor));
        try {
            ledger.initialize(s);if(!s.isInitialized()) { transactions.initialized(id,s.getEpoch(),s.getRevision()); }
            s=transactions.settings(id);ledger.gate(s,false,false);List<RegistrationReservation> rows=ledger.snapshot(id);
            for(RegistrationReservation r:rows) { persist(r); }
            int stock=ledger.stats(id).getInt("stock");long revision=s.getRevision();
            retry(()->transactions.finishReconcile(id,revision,rows,stock,actor,command.getReason()));
            RegistrationSettings current=transactions.settings(id);ledger.gate(current,current.gateOpen(),true);return current;
        } catch(RuntimeException error) { hold(id,"RECONCILIATION_INCOMPLETE");throw error instanceof BusinessException?error:unavailable(); }
    }
    /** Called only after a durable terminal DB state. Lua verifies exact original reservation identity. */
    public void synchronize(RegistrationRequest q) {
        if(q==null || q.getReservation().getEpoch()==null) { return; }
        try {
            if("CONFIRMED".equals(q.getStatus()) && !"COMPLETE".equals(q.getReclaimState())) { ledger.finish(q.getReservation(),"CONFIRMED");transactions.marked(q.getReservation().getContestId(),q.getReservation().getRequestId(),"COMPLETE"); }
            else if(("FAILED".equals(q.getStatus())||"CANCELLED".equals(q.getStatus())) && "RELEASE_PENDING".equals(q.getReclaimState())) { ledger.finish(q.getReservation(),"RELEASED");transactions.marked(q.getReservation().getContestId(),q.getReservation().getRequestId(),"RELEASED"); }
        } catch(RuntimeException unknown) {
            try { transactions.synchronizationFailed(q.getReservation().getContestId(),q.getReservation().getRequestId()); }
            catch(RuntimeException authorityUnknown) { /* Durable pending state still proves recovery is required. */ }
            throw unknown;
        }
    }
}
