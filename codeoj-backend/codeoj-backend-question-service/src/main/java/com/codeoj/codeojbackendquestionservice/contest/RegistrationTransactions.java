package com.codeoj.codeojbackendquestionservice.contest;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendquestionservice.submission.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** DB only. Contest is always locked first. All Redis/MQ work is outside this proxy. */
@Service
public class RegistrationTransactions {
    @org.springframework.beans.factory.annotation.Value("${codeoj.scoreboard.enabled:false}") private boolean scoreboardEnabled;
    private final JdbcTemplate jdbc;private final ContestTransactions contests;private final ExecutionRepository executions;
    @org.springframework.beans.factory.annotation.Value("${codeoj.registration.max-clock-skew-ms:2000}") private long clockSkewMs=2000;
    @javax.annotation.PostConstruct public void validateClockPolicy() { if(clockSkewMs<0 || clockSkewMs>60000) { throw new IllegalArgumentException("INVALID_REGISTRATION_CLOCK_POLICY"); } }
    public RegistrationTransactions(JdbcTemplate jdbc,ContestTransactions contests,ExecutionRepository executions) { this.jdbc=jdbc;this.contests=contests;this.executions=executions; }
    public RegistrationSettings settings(long id) {
        ContestTransactions.Contest c=contests.load(id,false);
        RegistrationSettings s=jdbc.queryForObject("SELECT registrationEpoch,registrationInitialized,initialRedisCapacity,registrationIssue FROM contest WHERE id=?",(rs,n)->{
            RegistrationSettings r=new RegistrationSettings();r.setEpoch(rs.getLong(1));r.setInitialized(rs.getBoolean(2));r.setInitialCapacity(rs.getInt(3));r.setIssue(rs.getString(4));return r;
        },id);
        s.setContestId(id);s.setPublishedVersion(c.publishedVersion==null?0:c.publishedVersion);s.setRevision(c.version);s.setMode(c.mode);s.setStatus(c.status);
        s.setReady(c.ready);s.setOpen(c.open);s.setClose(c.close);s.setNow(executions.now());return s;
    }
    public RegistrationRequest request(long contest,long id,boolean lock) {
        List<RegistrationRequest> rows=jdbc.query("SELECT * FROM contest_registration_request WHERE contestId=? AND id=?"+(lock?" FOR UPDATE":""),(rs,n)->{
            RegistrationRequest q=new RegistrationRequest();RegistrationReservation r=new RegistrationReservation();r.setContestId(rs.getLong("contestId"));r.setRequestId(rs.getLong("id"));r.setUserId(rs.getLong("userId"));
            r.setPublishedVersion(rs.getLong("publishedVersion"));r.setEpoch(rs.getObject("reservationEpoch")==null?null:rs.getLong("reservationEpoch"));r.setEventId(rs.getString("eventId"));r.setRequestKey(rs.getString("requestKey"));r.setRequestHash(rs.getString("requestHash"));
            r.setReservedAtMs(RedisRegistrationLedger.epoch(rs.getObject("reservedAt",LocalDateTime.class)));r.setReservedAtMicros(RedisRegistrationLedger.micros(rs.getObject("reservedAt",LocalDateTime.class)));r.setState("PROCESSING");q.setReservation(r);
            q.setStatus(rs.getString("status"));q.setReclaimState(rs.getString("reclaimState"));q.setLastError(rs.getString("lastError"));q.setVersion(rs.getLong("stateVersion"));q.setMaxPublishAttempts(rs.getInt("maxPublishAttempts"));
            q.setNextActionAt(rs.getObject("nextActionAt",LocalDateTime.class));q.setDeadline(rs.getObject("confirmationDeadlineAt",LocalDateTime.class));return q;
        },contest,id);return rows.isEmpty()?null:rows.get(0);
    }
    public static String hash(long contest,long version) { return ExecutionRepository.hash(JSONUtil.toJsonStr(ContestTransactions.map("type","REGISTRATION","contestId",contest,"publishedVersion",version))); }
    private void expected(ContestTransactions.Contest c,ContestCommand command) {
        if(command==null || command.getExpectedVersion()==null || command.getReason()==null || command.getReason().trim().isEmpty() || command.getReason().length()>500) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"REASON_AND_VERSION_REQUIRED"); }
        if(command.getExpectedVersion()!=c.version) { throw new SubmissionConflictException("CONTEST_VERSION_CONFLICT"); }
    }
    private void audit(long target,Long actor,String action,String reason,long before,long after,String details) {
        jdbc.update("INSERT INTO operation_audit(id,actorId,action,targetId,reason,beforeVersion,afterVersion,policyDetails,createdAt) VALUES(?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),actor,action,target,reason,before,after,details,executions.now());
    }
    @Transactional(rollbackFor=Exception.class)
    public RegistrationSettings prepare(long id,ContestCommand command,long actor) {
        ContestTransactions.Contest c=contests.load(id,true);executions.requireAccount(actor,"admin",true);expected(c,command);
        if(!"DB_SYNC".equals(c.mode) || !"PUBLISHED".equals(c.status)) { throw new SubmissionConflictException("REGISTRATION_MODE_NOT_SWITCHABLE"); }
        int confirmed=jdbc.queryForObject("SELECT COUNT(*) FROM contest_registration WHERE contestId=? AND status='CONFIRMED'",Integer.class,id);
        int unresolved=jdbc.queryForObject("SELECT COUNT(*) FROM contest_registration_request WHERE contestId=? AND status IN ('PROCESSING','MANUAL_REVIEW')",Integer.class,id);
        if(unresolved!=0 || confirmed+c.remaining!=c.capacity) { throw new SubmissionConflictException("DRAIN_AND_RECONCILE_DB_SYNC_FIRST"); }
        jdbc.update("UPDATE contest SET registrationMode='REDIS_PAUSED',registrationReady=false,registrationEpoch=registrationEpoch+1,registrationInitialized=false,initialRedisCapacity=remainingCapacity,registrationIssue=NULL,version=version+1 WHERE id=?",id);
        audit(id,actor,"REGISTRATION_MODE_PREPARE",command.getReason(),c.version,c.version+1,null);return settings(id);
    }
    @Transactional(rollbackFor=Exception.class)
    public RegistrationSettings beginReconcile(long id,ContestCommand command,long actor) {
        ContestTransactions.Contest c=contests.load(id,true);executions.requireAccount(actor,"admin",true);expected(c,command);
        if(!c.mode.startsWith("REDIS_") || "CANCELLED".equals(c.status) || "DRAFT".equals(c.status)) { throw new SubmissionConflictException("REGISTRATION_NOT_RECONCILABLE"); }
        jdbc.update("UPDATE contest SET registrationMode='REDIS_RECONCILING',registrationReady=false,version=version+1 WHERE id=?",id);
        audit(id,actor,"REGISTRATION_RECONCILE_BEGIN",command.getReason(),c.version,c.version+1,null);return settings(id);
    }
    @Transactional(rollbackFor=Exception.class)
    public void initialized(long id,long epoch,long revision) {
        ContestTransactions.Contest c=contests.load(id,true);RegistrationSettings s=settings(id);
        if(s.getEpoch()!=epoch || c.version!=revision || !"REDIS_RECONCILING".equals(c.mode) || "CANCELLED".equals(c.status)) { throw new SubmissionConflictException("INITIALIZATION_SUPERSEDED"); }
        jdbc.update("UPDATE contest SET registrationInitialized=true WHERE id=?",id);
    }
    @Transactional(rollbackFor=Exception.class)
    public void issue(long id,String issue) {
        ContestTransactions.Contest c=contests.load(id,true);if(!c.mode.startsWith("REDIS_")) { return; }
        RegistrationSettings s=settings(id);if(issue.equals(s.getIssue()) && !c.ready && "REDIS_PAUSED".equals(c.mode)) { return; }
        jdbc.update("UPDATE contest SET registrationMode='REDIS_PAUSED',registrationReady=false,registrationIssue=?,version=version+1 WHERE id=?",issue,id);
        audit(id,null,"REGISTRATION_HOLD",issue,c.version,c.version+1,null);
    }
    private boolean same(RegistrationReservation a,RegistrationReservation b) {
        return Objects.equals(a.getRequestId(),b.getRequestId()) && Objects.equals(a.getEventId(),b.getEventId()) && Objects.equals(a.getUserId(),b.getUserId())
                && Objects.equals(a.getContestId(),b.getContestId()) && Objects.equals(a.getPublishedVersion(),b.getPublishedVersion()) && Objects.equals(a.getEpoch(),b.getEpoch())
                && Objects.equals(a.getRequestKey(),b.getRequestKey()) && Objects.equals(a.getRequestHash(),b.getRequestHash()) && Objects.equals(a.getReservedAtMicros(),b.getReservedAtMicros());
    }
    private void event(RegistrationRequest q,LocalDateTime now) {
        RegistrationEvent event=q.event();
        jdbc.update("INSERT INTO reliable_outbox(eventId,eventType,aggregateId,aggregateVersion,payloadVersion,payload,publishState,nextRetryAt,attempts) VALUES(?,'REGISTRATION_CONFIRM',?,1,1,?,'PENDING',?,0)",event.getEventId(),event.getRequestId(),JSONUtil.toJsonStr(event),now);
    }
    @Transactional(rollbackFor=Exception.class)
    public RegistrationRequest persist(RegistrationReservation r) {
        if(r==null || !r.valid()) { throw new IllegalArgumentException("INVALID_RESERVATION"); }
        ContestTransactions.Contest c=contests.load(r.getContestId(),true);RegistrationSettings s=settings(c.id);
        if(!c.mode.startsWith("REDIS_") || s.getEpoch()!=r.getEpoch() || !Objects.equals(c.publishedVersion,r.getPublishedVersion()) || !hash(c.id,c.publishedVersion).equals(r.getRequestHash())) { throw new SubmissionConflictException("RESERVATION_CONTEXT_CONFLICT"); }
        boolean account=true;try { executions.requireAccount(r.getUserId(),null,true); }catch(BusinessException inactive) { account=false; }
        RegistrationRequest existing=request(c.id,r.getRequestId(),true);
        if(existing!=null) { if(!same(existing.getReservation(),r)) { throw new SubmissionConflictException("RESERVATION_IDENTITY_CONFLICT"); }return existing; }
        LocalDateTime now=executions.now(),reserved=LocalDateTime.ofInstant(Instant.ofEpochSecond(r.getReservedAtMicros()/1000000,(r.getReservedAtMicros()%1000000)*1000),ZoneOffset.UTC);
        String state="PROCESSING",error=null,reclaim="RESERVED";
        if(!"PROCESSING".equals(r.getState()) || reserved.isBefore(c.open) || !reserved.isBefore(c.close) || reserved.isAfter(now.plusNanos(clockSkewMs*1000000))) { state="MANUAL_REVIEW";error="RESERVATION_EVIDENCE_INVALID"; }
        else if("CANCELLED".equals(c.status)) { state="CANCELLED";error="CONTEST_CANCELLED";reclaim="RELEASE_PENDING"; }
        else if(!account) { state="FAILED";error="ACCOUNT_INACTIVE";reclaim="RELEASE_PENDING"; }
        else if(!now.isBefore(reserved.plusMinutes(5))) { state="MANUAL_REVIEW";error="RESERVATION_RECOVERY_EXHAUSTED"; }
        jdbc.update("INSERT INTO contest_registration_request(id,contestId,publishedVersion,userId,eventId,requestKey,requestHash,reservedAt,status,stateVersion,reclaimState,lastError,createdAt,reservationEpoch,confirmationChecks,maxPublishAttempts,nextActionAt,confirmationDeadlineAt) VALUES(?,?,?,?,?,?,?,?,?,0,?,?,?,?,0,20,?,?)",
                r.getRequestId(),c.id,r.getPublishedVersion(),r.getUserId(),r.getEventId(),r.getRequestKey(),r.getRequestHash(),reserved,state,reclaim,error,now,r.getEpoch(),now,reserved.plusMinutes(5));
        RegistrationRequest q=request(c.id,r.getRequestId(),false);if("PROCESSING".equals(state) || "RESERVATION_RECOVERY_EXHAUSTED".equals(error)) { event(q,now); }
        else { audit(r.getRequestId(),null,"REGISTRATION_RECOVER",error,0,0,null); }return q;
    }
    private void state(RegistrationRequest q,String state,String reclaim,String error,LocalDateTime now) {
        jdbc.update("UPDATE contest_registration_request SET status=?,reclaimState=?,lastError=?,nextActionAt=?,stateVersion=stateVersion+1 WHERE id=?",state,reclaim,error,now,q.getReservation().getRequestId());
        audit(q.getReservation().getRequestId(),null,"REGISTRATION_"+state,error,q.getVersion(),q.getVersion()+1,null);
    }
    @Transactional(rollbackFor=Exception.class)
    public boolean confirm(RegistrationEvent message) {
        if(message==null || !message.valid()) { return false; }
        ContestTransactions.Contest c=contests.load(message.getContestId(),true);RegistrationRequest q=request(c.id,message.getRequestId(),false);if(q==null) { return false; }
        boolean account=true;try { executions.requireAccount(q.getReservation().getUserId(),null,true); }catch(BusinessException inactive) { account=false; }
        q=request(c.id,message.getRequestId(),true);if(!q.event().equals(message)) { return false; }
        if(!"PROCESSING".equals(q.getStatus())) { return true; }
        LocalDateTime now=executions.now();RegistrationSettings s=settings(c.id);
        if("CANCELLED".equals(c.status)) { state(q,"CANCELLED","RELEASE_PENDING","CONTEST_CANCELLED",now);return true; }
        if(!account) { state(q,"FAILED","RELEASE_PENDING","ACCOUNT_INACTIVE",now);return true; }
        if("REDIS_RECONCILING".equals(c.mode) || s.getIssue()!=null) { jdbc.update("UPDATE contest_registration_request SET nextActionAt=? WHERE id=?",now.plusSeconds(5),message.getRequestId());return true; }
        if(q.getDeadline()==null || !now.isBefore(q.getDeadline())) { state(q,"MANUAL_REVIEW","RESERVED","CONFIRMATION_DEADLINE_EXHAUSTED",now);return true; }
        try { contests.confirmRegistration(c,message.getRequestId(),q.getReservation().getUserId(),now); }
        catch(SubmissionConflictException invalid) { state(q,"MANUAL_REVIEW","RESERVED","CAPACITY_OR_CONTEXT_CONFLICT",now);issue(c.id,"CAPACITY_OR_CONTEXT_CONFLICT");return true; }
        jdbc.update("UPDATE contest_registration_request SET nextActionAt=?,confirmationChecks=confirmationChecks+1,lastError=NULL WHERE id=?",now,message.getRequestId());return true;
    }
    @Transactional(rollbackFor=Exception.class)
    public void marked(long contest,long request,String marker) {
        contests.load(contest,true);RegistrationRequest q=request(contest,request,true);if(q==null) { return; }
        boolean valid="COMPLETE".equals(marker)?"CONFIRMED".equals(q.getStatus()):("FAILED".equals(q.getStatus())||"CANCELLED".equals(q.getStatus()));
        if(valid && !marker.equals(q.getReclaimState())) { jdbc.update("UPDATE contest_registration_request SET reclaimState=?,nextActionAt=NULL,lastError=NULL,stateVersion=stateVersion+1 WHERE id=?",marker,request); }
    }
    @Transactional(rollbackFor=Exception.class)
    public void synchronizationFailed(long contest,long request) {
        contests.load(contest,true);RegistrationRequest q=request(contest,request,true);if(q==null || Arrays.asList("COMPLETE","RELEASED").contains(q.getReclaimState())) { return; }
        jdbc.update("UPDATE contest_registration_request SET lastError='RESERVATION_SYNC_UNAVAILABLE',nextActionAt=?,stateVersion=stateVersion+1 WHERE id=?",executions.now().plusSeconds(5),request);
        if(!"RESERVATION_SYNC_UNAVAILABLE".equals(q.getLastError())) { audit(request,null,"REGISTRATION_SYNC_UNKNOWN","RESERVATION_SYNC_UNAVAILABLE",q.getVersion(),q.getVersion()+1,null); }
    }
    @Transactional(rollbackFor=Exception.class)
    public void recover(long contest,long request) {
        ContestTransactions.Contest c=contests.load(contest,true);RegistrationRequest q=request(contest,request,true);if(q==null || q.getReservation().getEpoch()==null) { return; }
        LocalDateTime now=executions.now();
        if("CANCELLED".equals(c.status) && ("PROCESSING".equals(q.getStatus()) || "MANUAL_REVIEW".equals(q.getStatus()))) { state(q,"CANCELLED","RELEASE_PENDING","CONTEST_CANCELLED",now);return; }
        if("CANCELLED".equals(q.getStatus()) && "RESERVED".equals(q.getReclaimState())) { state(q,"CANCELLED","RELEASE_PENDING","CONTEST_CANCELLED",now);return; }
        if(!"PROCESSING".equals(q.getStatus()) || (q.getNextActionAt()!=null && now.isBefore(q.getNextActionAt()))) { return; }
        int attempts=jdbc.queryForObject("SELECT attempts FROM reliable_outbox WHERE eventId=?",Integer.class,q.getReservation().getEventId());
        if(attempts>=q.getMaxPublishAttempts() || q.getDeadline()==null || !now.isBefore(q.getDeadline())) { state(q,"MANUAL_REVIEW","RESERVED","REGISTRATION_RETRY_EXHAUSTED",now);return; }
        jdbc.update("UPDATE reliable_outbox SET publishState='PENDING',nextRetryAt=? WHERE eventId=? AND publishState='CONFIRMED' AND (leaseUntil IS NULL OR leaseUntil<=?)",now,q.getReservation().getEventId(),now);
        jdbc.update("UPDATE contest_registration_request SET nextActionAt=? WHERE id=?",now.plusSeconds(30),request);
    }
    @Transactional(rollbackFor=Exception.class)
    public void review(long contest,long request,RegistrationReview command,long actor) {
        ContestTransactions.Contest c=contests.load(contest,true);executions.requireAccount(actor,"admin",true);RegistrationRequest q=request(contest,request,true);
        if(command==null || command.getReason()==null || command.getReason().trim().isEmpty() || command.getReason().length()>500 || command.getExpectedVersion()==null
                || command.getAdditionalPublishAttempts()<0 || command.getAdditionalPublishAttempts()>20 || command.getExtendRecoverySeconds()<0 || command.getExtendRecoverySeconds()>3600) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_REGISTRATION_REVIEW"); }
        if(q==null || q.getVersion()!=command.getExpectedVersion() || "REDIS_RECONCILING".equals(c.mode)) { throw new SubmissionConflictException("REGISTRATION_REVIEW_VERSION_CONFLICT"); }
        LocalDateTime now=executions.now();
        if("FAIL".equals(command.getDecision())) {
            if(!Arrays.asList("PROCESSING","MANUAL_REVIEW").contains(q.getStatus())) { throw new SubmissionConflictException("CONFIRMED_OR_TERMINAL_REQUEST_CANNOT_RELEASE"); }
            state(q,"FAILED","RELEASE_PENDING","ADMIN_TERMINATED",now);
        } else if("CONTINUE".equals(command.getDecision())) {
            if(!"MANUAL_REVIEW".equals(q.getStatus()) || "CANCELLED".equals(c.status)) { throw new SubmissionConflictException("REGISTRATION_NOT_RESUMABLE"); }
            List<Integer> publications=jdbc.queryForList("SELECT attempts FROM reliable_outbox WHERE eventId=?",Integer.class,q.getReservation().getEventId());
            if(publications.isEmpty()) { throw new SubmissionConflictException("ORIGINAL_PUBLICATION_EVIDENCE_MISSING"); }
            int used=publications.get(0);
            int budget=q.getMaxPublishAttempts()+command.getAdditionalPublishAttempts();LocalDateTime deadline=q.getDeadline();
            if(command.getExtendRecoverySeconds()>0) { deadline=(deadline==null || deadline.isBefore(now)?now:deadline).plusSeconds(command.getExtendRecoverySeconds());if(deadline.isAfter(now.plusHours(1))) { deadline=now.plusHours(1); } }
            if(budget>100 || used>=budget || deadline==null || !now.isBefore(deadline)) { throw new SubmissionConflictException("EXPLICIT_FINITE_REGISTRATION_EXTENSION_REQUIRED"); }
            jdbc.update("UPDATE contest_registration_request SET status='PROCESSING',lastError=NULL,maxPublishAttempts=?,confirmationDeadlineAt=?,nextActionAt=?,stateVersion=stateVersion+1 WHERE id=?",budget,deadline,now.plusSeconds(30),request);
            jdbc.update("UPDATE reliable_outbox SET publishState='PENDING',nextRetryAt=?,leaseToken=NULL,leaseUntil=NULL WHERE eventId=?",now,q.getReservation().getEventId());
        } else { throw new BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_REVIEW_DECISION"); }
        audit(request,actor,"REGISTRATION_ADMIN_"+command.getDecision(),command.getReason(),q.getVersion(),q.getVersion()+1,JSONUtil.toJsonStr(command));
    }
    @Transactional(rollbackFor=Exception.class)
    public RegistrationSettings finishReconcile(long contest,long revision,List<RegistrationReservation> rows,int stock,long actor,String reason) {
        ContestTransactions.Contest c=contests.load(contest,true);executions.requireAccount(actor,"admin",true);RegistrationSettings s=settings(contest);
        if(c.version!=revision || !"REDIS_RECONCILING".equals(c.mode) || "CANCELLED".equals(c.status)) { throw new SubmissionConflictException("RECONCILIATION_SUPERSEDED"); }
        Map<Long,RegistrationReservation> byId=new HashMap<>();int active=0;
        for(RegistrationReservation r:rows) { if(!r.valid() || r.getContestId()!=contest || r.getEpoch()!=s.getEpoch() || !Objects.equals(c.publishedVersion,r.getPublishedVersion()) || byId.put(r.getRequestId(),r)!=null) { throw new SubmissionConflictException("LEDGER_IDENTITY_INVALID"); }if(!"RELEASED".equals(r.getState())) { active++; } }
        List<Long> ids=jdbc.queryForList("SELECT id FROM contest_registration_request WHERE contestId=? AND reservationEpoch=?",Long.class,contest,s.getEpoch());int confirmed=0;
        for(long id:ids) {
            RegistrationRequest q=request(contest,id,true);RegistrationReservation r=byId.get(id);
            if(r==null || !same(q.getReservation(),r) || ("CONFIRMED".equals(q.getStatus()) && "RELEASED".equals(r.getState()))
                    || (!"CONFIRMED".equals(q.getStatus()) && "CONFIRMED".equals(r.getState()))
                    || (Arrays.asList("PROCESSING","MANUAL_REVIEW").contains(q.getStatus()) && "RELEASED".equals(r.getState()))) { throw new SubmissionConflictException("LEDGER_DATABASE_MISMATCH"); }
            if("CONFIRMED".equals(q.getStatus())) { confirmed++; }
        }
        int allConfirmed=jdbc.queryForObject("SELECT COUNT(*) FROM contest_registration WHERE contestId=? AND status='CONFIRMED'",Integer.class,contest);
        if(ids.size()!=rows.size() || allConfirmed+c.remaining!=c.capacity || c.remaining!=s.getInitialCapacity()-confirmed || stock!=s.getInitialCapacity()-active) { throw new SubmissionConflictException("CAPACITY_LEDGER_MISMATCH"); }
        jdbc.update("UPDATE contest SET registrationMode='REDIS_READY',registrationReady=true,registrationIssue=NULL,version=version+1 WHERE id=?",contest);
        if(scoreboardEnabled && "FINALIZING".equals(c.status)) { jdbc.update("UPDATE contest SET registrationReconciledAt=? WHERE id=?",executions.now(),contest); }
        audit(contest,actor,"REGISTRATION_RECONCILE_COMPLETE",reason,c.version,c.version+1,"No stock overwrite");return settings(contest);
    }
}
