package com.codeoj.codeojbackendquestionservice.scoreboard;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;
import com.codeoj.codeojbackendquestionservice.submission.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** DB authority only. Contest -> current account -> score-user lock order.
 * A contest lock deliberately serializes first-version projection/rebuild/qualification changes.
 * No network calls occur here. Rebuilds are bounded and require immutable completed facts. */
@Service
@Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
@ConditionalOnProperty(name="codeoj.scoreboard.enabled",havingValue="true")
public class ScoreboardTransactions {
    private final JdbcTemplate jdbc;
    private final ExecutionRepository executions;
    public ScoreboardTransactions(JdbcTemplate jdbc,ExecutionRepository executions) { this.jdbc=jdbc;this.executions=executions; }
    static class State {
        long id,contestVersion,published,version,revision,epoch;Long finalVersion;
        String status,rule,mode;boolean ready;LocalDateTime start,end,asOf,sealed;
    }
    private State lock(long id) {
        List<State> rows=jdbc.query("SELECT * FROM contest WHERE id=? FOR UPDATE",(rs,n)->{
            State s=new State();s.id=id;s.contestVersion=rs.getLong("version");s.published=rs.getLong("publishedVersion");
            s.status=rs.getString("status");s.rule=rs.getString("ruleVersion");s.mode=rs.getString("registrationMode");s.ready=rs.getBoolean("registrationReady");
            s.start=rs.getObject("startAt",LocalDateTime.class);s.end=rs.getObject("endAt",LocalDateTime.class);s.sealed=rs.getObject("registrationReconciledAt",LocalDateTime.class);return s;
        },id);
        if(rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND_ERROR); }
        State s=rows.get(0);
        if("DRAFT".equals(s.status)) { throw new BusinessException(ErrorCode.NOT_FOUND_ERROR); }
        if(!"FIRST_AC_V1".equals(s.rule)) { conflict("SCORING_RULE_UNSUPPORTED"); }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM contest_scoreboard_state WHERE contestId=?",Integer.class,id)==0) {
            LocalDateTime now=executions.now();
            jdbc.update("INSERT INTO contest_scoreboard_version VALUES(?,1,'LIVE',NULL,?,?)",id,now,now);
            jdbc.update("INSERT INTO contest_scoreboard_state VALUES(?,1,0,0,NULL,?)",id,now);
        }
        jdbc.query("SELECT * FROM contest_scoreboard_state WHERE contestId=?",rs->{
            s.version=rs.getLong("activeVersion");s.revision=rs.getLong("revision");s.epoch=rs.getLong("cacheEpoch");
            s.finalVersion=rs.getObject("finalVersion")==null?null:rs.getLong("finalVersion");s.asOf=rs.getObject("asOf",LocalDateTime.class);
        },id);
        return s;
    }
    private static void conflict(String message) { throw new SubmissionConflictException(message); }
    private static String utc(LocalDateTime value) { return value==null?null:value.toInstant(ZoneOffset.UTC).toString(); }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2) { m.put((String)pairs[i],pairs[i+1]); }return m;
    }
    private void bump(State s) {
        s.revision++;s.asOf=executions.now();
        jdbc.update("UPDATE contest_scoreboard_state SET revision=?,asOf=? WHERE contestId=?",s.revision,s.asOf,s.id);
        jdbc.update("UPDATE contest_scoreboard_version SET asOf=? WHERE contestId=? AND projectionVersion=?",s.asOf,s.id,s.version);
    }
    private void zeros(State s) {
        if("FINISHED".equals(s.status) || "CANCELLED".equals(s.status)) { return; }
        int added=jdbc.update("INSERT INTO contest_score_user(contestId,projectionVersion,userId,solvedCount,achievedAt)"
                +" SELECT r.contestId,?,r.userId,0,NULL FROM contest_registration r WHERE r.contestId=? AND r.status='CONFIRMED'"
                +" AND NOT EXISTS(SELECT 1 FROM contest_score_user u WHERE u.contestId=r.contestId AND u.projectionVersion=? AND u.userId=r.userId)",s.version,s.id,s.version);
        if(added>0) { bump(s); }
    }
    /** Called in the same registration-confirmation transaction when J5 is enabled. */
    public void qualificationConfirmed(long contest) { State s=lock(contest);zeros(s); }

    static class Fact {
        long submission,user,problem,published;int resultVersion;String status,digest,result,spec,expectedSpec;
        LocalDateTime accepted;boolean contextValid;
    }
    private List<Fact> facts(long contest,Long submission) {
        return jdbc.query("SELECT e.submissionId,e.userId,e.problemSnapshotId,e.publishedVersion,e.acceptedAt,e.executionStatus,e.resultVersion,e.resultDigest,e.judgeSpecHash,q.judgeInfo,"
                +" p.judgeSpecHash AS expectedSpec,CASE WHEN p.id IS NOT NULL AND g.id IS NOT NULL AND q.userId=e.userId THEN true ELSE false END AS contextValid"
                +" FROM submission_execution e JOIN question_submit q ON q.id=e.submissionId"
                +" LEFT JOIN contest_problem_snapshot p ON p.id=e.problemSnapshotId AND p.contestId=e.contestId AND p.publishedVersion=e.publishedVersion"
                +" LEFT JOIN contest_registration g ON g.contestId=e.contestId AND g.userId=e.userId AND g.status='CONFIRMED'"
                +" WHERE e.contestId=?"+(submission==null?"":" AND e.submissionId=?")+" ORDER BY e.submissionId",(rs,n)->{
            Fact f=new Fact();f.submission=rs.getLong("submissionId");f.user=rs.getLong("userId");f.problem=rs.getLong("problemSnapshotId");f.published=rs.getLong("publishedVersion");
            f.accepted=rs.getObject("acceptedAt",LocalDateTime.class);f.status=rs.getString("executionStatus");f.resultVersion=rs.getInt("resultVersion");
            f.digest=rs.getString("resultDigest");f.result=rs.getString("judgeInfo");f.spec=rs.getString("judgeSpecHash");f.expectedSpec=rs.getString("expectedSpec");f.contextValid=rs.getBoolean("contextValid");return f;
        },submission==null?new Object[]{contest}:new Object[]{contest,submission});
    }
    private JudgeVerdict verdict(State s,Fact f) {
        if(!f.contextValid || f.published!=s.published || f.accepted.isBefore(s.start) || !f.accepted.isBefore(s.end)
                || !Objects.equals(f.spec,f.expectedSpec) || !"COMPLETED".equals(f.status) || f.resultVersion<1
                || f.result==null || !Objects.equals(f.digest,ExecutionRepository.hash(f.result))) { conflict("INVALID_OR_UNRESOLVED_RESULT_FACT"); }
        try { return JudgeVerdict.valueOf(JSONUtil.parseObj(f.result).getStr("verdict")); }
        catch(RuntimeException invalid) { throw new SubmissionConflictException("INVALID_RESULT_VERDICT"); }
    }
    public boolean project(String eventId,ScoreResultEvent message) {
        if(eventId==null || !eventId.matches("[a-fA-F0-9-]{36}") || message==null || !message.valid()) { return false; }
        List<String> events=jdbc.query("SELECT payload FROM reliable_outbox WHERE eventId=? AND eventType='JUDGE_RESULT' AND aggregateId=? AND aggregateVersion=? AND payloadVersion=1",
                (rs,n)->rs.getString(1),eventId,message.getSubmissionId(),message.getResultVersion());
        if(events.isEmpty()) { return false; }
        ScoreResultEvent authority=JSONUtil.toBean(events.get(0),ScoreResultEvent.class);
        if(!Objects.equals(authority.getSubmissionId(),message.getSubmissionId()) || !Objects.equals(authority.getResultVersion(),message.getResultVersion())) { return false; }
        ExecutionRecord e=executions.find(message.getSubmissionId(),false);
        if(e==null || e.getResultVersion()!=message.getResultVersion() || e.getExecutionStatus()!=com.codeoj.codeojbackendmodel.model.reliability.ExecutionStatus.COMPLETED) { return false; }
        if(e.getContestId()==null) {
            // Practice results are deliberately outside all contest projections.
            jdbc.update("INSERT INTO scoreboard_result_receipt(eventId,submissionId,resultVersion,processedAt) SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM scoreboard_result_receipt WHERE eventId=?)",
                    eventId,e.getSubmissionId(),e.getResultVersion(),executions.now(),eventId);return true;
        }
        State s=lock(e.getContestId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM scoreboard_result_receipt WHERE eventId=?",Integer.class,eventId)>0) { return true; }
        if(!"CANCELLED".equals(s.status) && !"FINISHED".equals(s.status)) {
            zeros(s);Fact f=facts(s.id,e.getSubmissionId()).get(0);JudgeVerdict verdict=verdict(s,f);
            jdbc.queryForObject("SELECT userId FROM contest_score_user WHERE contestId=? AND projectionVersion=? AND userId=? FOR UPDATE",Long.class,s.id,s.version,f.user);
            if(verdict==JudgeVerdict.AC) {
                List<LocalDateTime> prior=jdbc.query("SELECT firstAcSubmittedAt FROM contest_score_problem WHERE contestId=? AND projectionVersion=? AND userId=? AND problemSnapshotId=?",
                        (rs,n)->rs.getObject(1,LocalDateTime.class),s.id,s.version,f.user,f.problem);
                if(prior.isEmpty()) { jdbc.update("INSERT INTO contest_score_problem VALUES(?,?,?,?,?)",s.id,s.version,f.user,f.problem,f.accepted); }
                else if(f.accepted.isBefore(prior.get(0))) { jdbc.update("UPDATE contest_score_problem SET firstAcSubmittedAt=? WHERE contestId=? AND projectionVersion=? AND userId=? AND problemSnapshotId=?",f.accepted,s.id,s.version,f.user,f.problem); }
                jdbc.update("UPDATE contest_score_user SET solvedCount=(SELECT COUNT(*) FROM contest_score_problem p WHERE p.contestId=? AND p.projectionVersion=? AND p.userId=?),"
                        +" achievedAt=(SELECT MAX(firstAcSubmittedAt) FROM contest_score_problem p WHERE p.contestId=? AND p.projectionVersion=? AND p.userId=?) WHERE contestId=? AND projectionVersion=? AND userId=?",
                        s.id,s.version,f.user,s.id,s.version,f.user,s.id,s.version,f.user);
            }
            bump(s);
        }
        jdbc.update("INSERT INTO scoreboard_result_receipt VALUES(?,?,?,?,?,?)",eventId,e.getSubmissionId(),e.getResultVersion(),s.id,s.version,executions.now());return true;
    }
    private long pending(State s) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM submission_execution WHERE contestId=? AND executionStatus<>'COMPLETED'",Long.class,s.id);
    }
    private long lag(State s) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reliable_outbox o JOIN submission_execution e ON e.submissionId=o.aggregateId WHERE o.eventType='JUDGE_RESULT' AND e.contestId=?"
                +" AND NOT EXISTS(SELECT 1 FROM scoreboard_result_receipt r WHERE r.eventId=o.eventId)",Long.class,s.id);
    }
    public Map<String,Object> snapshot(long id,int page,int size,Long selectedVersion,long actor,boolean withRows) {
        if(page<0 || page>100000 || size<1 || size>100) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_SCOREBOARD_PAGE"); }
        State s=lock(id);executions.requireAccount(actor,null,false);zeros(s);
        long version=selectedVersion==null?s.version:selectedVersion;
        List<Map<String,Object>> versions=jdbc.query("SELECT state,asOf FROM contest_scoreboard_version WHERE contestId=? AND projectionVersion=?",(rs,n)->map("state",rs.getString(1),"asOf",utc(rs.getObject(2,LocalDateTime.class))),id,version);
        if(versions.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND_ERROR); }
        Map<String,Object> result=map("contestId",Long.toString(id),"contestVersion",s.contestVersion,"status",s.status,"ruleVersion",s.rule,
                "projectionVersion",version,"activeProjectionVersion",s.version,"revision",s.revision,"cacheEpoch",s.epoch,"finalVersion",s.finalVersion,
                "asOf",versions.get(0).get("asOf"),"pendingCount",pending(s),"projectionPendingCount",lag(s),"final","FINAL".equals(versions.get(0).get("state")),
                "versionState",versions.get(0).get("state"),"page",page,"pageSize",size,
                "total",jdbc.queryForObject("SELECT COUNT(*) FROM contest_score_user WHERE contestId=? AND projectionVersion=?",Long.class,id,version));
        if(withRows) {
            result.put("entries",jdbc.query("SELECT s.userId,s.solvedCount,s.achievedAt,1+(SELECT COUNT(*) FROM contest_score_user t WHERE t.contestId=s.contestId AND t.projectionVersion=s.projectionVersion"
                    +" AND (t.solvedCount>s.solvedCount OR (t.solvedCount=s.solvedCount AND s.solvedCount>0 AND t.achievedAt<s.achievedAt))) AS ranking"
                    +" FROM contest_score_user s WHERE s.contestId=? AND s.projectionVersion=? ORDER BY s.solvedCount DESC,s.achievedAt ASC,s.userId ASC LIMIT ? OFFSET ?",
                    (rs,n)->map("userId",rs.getString(1),"solvedCount",rs.getInt(2),"achievedAt",utc(rs.getObject(3,LocalDateTime.class)),"rank",rs.getLong(4)),id,version,size,(long)page*size));
        }
        return result;
    }
    private void command(State s,ScoreboardCommand c,long actor) {
        executions.requireAccount(actor,"admin",true);
        if(c==null || c.getExpectedVersion()==null || c.getExpectedProjectionVersion()==null || c.getExpectedRevision()==null
                || c.getReason()==null || c.getReason().trim().isEmpty() || c.getReason().length()>500) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"SCOREBOARD_REASON_AND_VERSIONS_REQUIRED"); }
        if(c.getExpectedVersion()!=s.contestVersion || c.getExpectedProjectionVersion()!=s.version || c.getExpectedRevision()!=s.revision) { conflict("SCOREBOARD_VERSION_CONFLICT"); }
    }
    private void audit(State s,ScoreboardCommand c,long actor,String action,String details) {
        jdbc.update("INSERT INTO operation_audit VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),actor,action,s.id,c.getReason(),s.contestVersion,s.contestVersion+1,details,executions.now());
        jdbc.update("UPDATE contest SET version=version+1 WHERE id=?",s.id);
    }
    private void settledFacts(State s) {
        if(executions.now().isBefore(s.end) || !("FINALIZING".equals(s.status)||"FINISHED".equals(s.status))) { conflict("FINALIZING_AFTER_END_REQUIRED"); }
        if(!s.ready || pending(s)>0) { conflict("UNRESOLVED_SUBMISSIONS_OR_REGISTRATION_HOLD"); }
        long unresolved=jdbc.queryForObject("SELECT COUNT(*) FROM contest_registration_request WHERE contestId=? AND (status IN ('PROCESSING','MANUAL_REVIEW')"
                +" OR (reservationEpoch IS NOT NULL AND reclaimState NOT IN ('COMPLETE','RELEASED')))",Long.class,s.id);
        long participants=jdbc.queryForObject("SELECT COUNT(*) FROM contest_registration WHERE contestId=? AND status='CONFIRMED'",Long.class,s.id);
        long distinct=jdbc.queryForObject("SELECT COUNT(DISTINCT userId) FROM contest_registration WHERE contestId=? AND status='CONFIRMED'",Long.class,s.id);
        long capacity=jdbc.queryForObject("SELECT capacity-remainingCapacity FROM contest WHERE id=?",Long.class,s.id);
        long invalidQualifications=jdbc.queryForObject("SELECT COUNT(*) FROM contest_registration g LEFT JOIN contest_registration_request r ON r.id=g.requestId"
                +" WHERE g.contestId=? AND g.status='CONFIRMED' AND (r.id IS NULL OR r.status<>'CONFIRMED' OR r.contestId<>g.contestId OR r.userId<>g.userId OR r.publishedVersion<>?)",Long.class,s.id,s.published);
        if(unresolved>0 || participants!=distinct || participants!=capacity || invalidQualifications>0) { conflict("UNRESOLVED_OR_INCONSISTENT_REGISTRATION"); }
        if(s.mode.startsWith("REDIS_") && (!"REDIS_READY".equals(s.mode) || s.sealed==null || s.sealed.isBefore(s.end))) { conflict("FINALIZING_REGISTRATION_RECONCILIATION_REQUIRED"); }
        if(participants>10000 || jdbc.queryForObject("SELECT COUNT(*) FROM submission_execution WHERE contestId=?",Long.class,s.id)>50000) { conflict("LOCAL_REBUILD_BOUND_EXCEEDED"); }
    }
    private SortedMap<String,LocalDateTime> authoritative(State s) {
        SortedMap<String,LocalDateTime> values=new TreeMap<>();
        for(Fact f:facts(s.id,null)) {
            if(verdict(s,f)==JudgeVerdict.AC) { String key=f.user+":"+f.problem;LocalDateTime prior=values.get(key);if(prior==null || f.accepted.isBefore(prior)) { values.put(key,f.accepted); } }
        }
        return values;
    }
    private SortedMap<String,LocalDateTime> projected(State s) {
        SortedMap<String,LocalDateTime> values=new TreeMap<>();
        jdbc.query("SELECT userId,problemSnapshotId,firstAcSubmittedAt FROM contest_score_problem WHERE contestId=? AND projectionVersion=?",rs->{
            values.put(rs.getLong(1)+":"+rs.getLong(2),rs.getObject(3,LocalDateTime.class));
        },s.id,s.version);return values;
    }
    private SortedMap<Long,List<LocalDateTime>> totals(State s,SortedMap<String,LocalDateTime> values) {
        SortedMap<Long,List<LocalDateTime>> users=new TreeMap<>();
        for(long user:jdbc.queryForList("SELECT userId FROM contest_registration WHERE contestId=? AND status='CONFIRMED' ORDER BY userId",Long.class,s.id)) { users.put(user,new ArrayList<>()); }
        values.forEach((key,time)->users.get(Long.parseLong(key.split(":")[0])).add(time));return users;
    }
    private boolean matches(State s,SortedMap<String,LocalDateTime> values) {
        if(!values.equals(projected(s))) { return false; }
        SortedMap<Long,List<LocalDateTime>> totals=totals(s,values);
        List<Map<String,Object>> stored=jdbc.query("SELECT userId,solvedCount,achievedAt FROM contest_score_user WHERE contestId=? AND projectionVersion=?",(rs,n)->map("id",rs.getLong(1),"count",rs.getInt(2),"at",rs.getObject(3,LocalDateTime.class)),s.id,s.version);
        if(stored.size()!=totals.size()) { return false; }
        for(Map<String,Object> row:stored) {
            List<LocalDateTime> times=totals.get((Long)row.get("id"));
            if(times==null || times.size()!=(Integer)row.get("count") || !Objects.equals(times.isEmpty()?null:Collections.max(times),row.get("at"))) { return false; }
        }
        return true;
    }
    public Map<String,Object> rebuild(long id,ScoreboardCommand c,long actor,boolean revision) {
        State s=lock(id);command(s,c,actor);settledFacts(s);
        if(revision!="FINISHED".equals(s.status)) { conflict("USE_EXPLICIT_FINAL_REVISION"); }
        SortedMap<String,LocalDateTime> values=authoritative(s);boolean matched=matches(s,values);long old=s.version;
        String digest=ExecutionRepository.hash(JSONUtil.toJsonStr(map("participants",totals(s,values),"firstAc",values)));
        s.version++;LocalDateTime now=executions.now();
        jdbc.update("INSERT INTO contest_scoreboard_version VALUES(?,?,?,?,?,?)",id,s.version,revision?"FINAL":"REBUILT",digest,now,now);
        for(Map.Entry<Long,List<LocalDateTime>> user:totals(s,values).entrySet()) {
            List<LocalDateTime> times=user.getValue();jdbc.update("INSERT INTO contest_score_user VALUES(?,?,?,?,?)",id,s.version,user.getKey(),times.size(),times.isEmpty()?null:Collections.max(times));
        }
        values.forEach((key,time)->{String[] parts=key.split(":");jdbc.update("INSERT INTO contest_score_problem VALUES(?,?,?,?,?)",id,s.version,Long.parseLong(parts[0]),Long.parseLong(parts[1]),time);});
        if(!matches(s,values)) { throw new IllegalStateException("REBUILT_PROJECTION_VALIDATION_FAILED"); }
        jdbc.update("UPDATE contest_scoreboard_state SET activeVersion=?,finalVersion=? WHERE contestId=?",s.version,revision?Long.valueOf(s.version):s.finalVersion,id);bump(s);
        jdbc.update("UPDATE contest_scoreboard_version SET state='RETIRED' WHERE contestId=? AND projectionVersion=? AND state<>'FINAL'",id,old);
        audit(s,c,actor,revision?"SCOREBOARD_FINAL_REVISION":"SCOREBOARD_REBUILD",JSONUtil.toJsonStr(map("previousVersion",old,"newVersion",s.version,"matchedPrevious",matched,"digest",digest)));
        return map("projectionVersion",s.version,"revision",s.revision,"matchedPrevious",matched,"digest",digest);
    }
    public void finish(long id,ScoreboardCommand c,long actor) {
        State s=lock(id);command(s,c,actor);settledFacts(s);
        if(!"FINALIZING".equals(s.status) || s.version<2) { conflict("REBUILD_BEFORE_FINALIZATION"); }
        if(!matches(s,authoritative(s))) { conflict("PROJECTION_MISMATCH_REBUILD_REQUIRED"); }
        jdbc.update("UPDATE contest_scoreboard_version SET state='FINAL' WHERE contestId=? AND projectionVersion=?",id,s.version);
        jdbc.update("UPDATE contest_scoreboard_state SET finalVersion=? WHERE contestId=?",s.version,id);bump(s);
        jdbc.update("UPDATE contest SET status='FINISHED' WHERE id=?",id);audit(s,c,actor,"CONTEST_FINISHED","Authoritative facts and projection agree");
    }
    public void clearCache(long id,ScoreboardCommand c,long actor) {
        State s=lock(id);command(s,c,actor);jdbc.update("UPDATE contest_scoreboard_state SET cacheEpoch=cacheEpoch+1 WHERE contestId=?",id);audit(s,c,actor,"SCOREBOARD_CACHE_CLEAR","Versioned cache namespace advanced");
    }
    public int replay(long id,ScoreboardCommand c,long actor) {
        State s=lock(id);command(s,c,actor);
        if("FINISHED".equals(s.status)||"CANCELLED".equals(s.status)) { conflict("SCOREBOARD_NOT_REPLAYABLE"); }
        if(c.getAdditionalPublishAttempts()<0 || c.getAdditionalPublishAttempts()>20) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_SCORE_REPLAY_BUDGET"); }
        List<String> events=jdbc.queryForList("SELECT o.eventId FROM reliable_outbox o JOIN submission_execution e ON e.submissionId=o.aggregateId WHERE o.eventType='JUDGE_RESULT' AND e.contestId=?"
                +" AND NOT EXISTS(SELECT 1 FROM scoreboard_result_receipt r WHERE r.eventId=o.eventId) ORDER BY e.acceptedAt LIMIT 20",String.class,id);
        for(String event:events) {
            int changed=jdbc.update("UPDATE reliable_outbox SET maxPublishAttempts=maxPublishAttempts+?,publishState='PENDING',nextRetryAt=?,lastError=NULL WHERE eventId=?"
                    +" AND maxPublishAttempts+?<=100 AND attempts<maxPublishAttempts+? AND (leaseUntil IS NULL OR leaseUntil<=?)",c.getAdditionalPublishAttempts(),executions.now(),event,c.getAdditionalPublishAttempts(),c.getAdditionalPublishAttempts(),executions.now());
            if(changed!=1) { conflict("SCORE_REPLAY_BUDGET_OR_LEASE_CONFLICT"); }
        }
        audit(s,c,actor,"SCOREBOARD_EVENT_REPLAY",JSONUtil.toJsonStr(map("eventIds",events,"additionalPublishAttempts",c.getAdditionalPublishAttempts())));return events.size();
    }
    public void recoverPublications() {
        LocalDateTime now=executions.now();
        List<String> events=jdbc.queryForList("SELECT o.eventId FROM reliable_outbox o WHERE o.eventType='JUDGE_RESULT' AND o.publishState='CONFIRMED' AND o.nextRetryAt<=?"
                +" AND NOT EXISTS(SELECT 1 FROM scoreboard_result_receipt r WHERE r.eventId=o.eventId) ORDER BY o.nextRetryAt LIMIT 20",String.class,now.minusSeconds(30));
        for(String event:events) { jdbc.update("UPDATE reliable_outbox SET publishState=CASE WHEN attempts<maxPublishAttempts THEN 'PENDING' ELSE 'ATTENTION' END,nextRetryAt=?,lastError='PROJECTION_RECEIPT_MISSING' WHERE eventId=? AND publishState='CONFIRMED'",now,event); }
    }
    public Map<String,Object> operations(long id,int page,long actor) {
        State s=lock(id);executions.requireAccount(actor,"admin",true);if(page<0 || page>100000) { throw new BusinessException(ErrorCode.PARAMS_ERROR); }zeros(s);
        return map("pendingCount",pending(s),"projectionPendingCount",lag(s),"pending",jdbc.query("SELECT submissionId,executionStatus,stateVersion,lastError FROM submission_execution WHERE contestId=? AND executionStatus<>'COMPLETED' ORDER BY acceptedAt LIMIT 20 OFFSET ?",
                (rs,n)->map("submissionId",rs.getString(1),"executionStatus",rs.getString(2),"stateVersion",rs.getLong(3),"errorCode",rs.getString(4)),id,(long)page*20),
                "publications",jdbc.query("SELECT o.eventId,o.aggregateId,o.publishState,o.attempts,o.maxPublishAttempts,o.lastError FROM reliable_outbox o JOIN submission_execution e ON e.submissionId=o.aggregateId"
                        +" WHERE o.eventType='JUDGE_RESULT' AND e.contestId=? AND NOT EXISTS(SELECT 1 FROM scoreboard_result_receipt r WHERE r.eventId=o.eventId) ORDER BY e.acceptedAt LIMIT 20 OFFSET ?",
                        (rs,n)->map("eventId",rs.getString(1),"submissionId",rs.getString(2),"publishState",rs.getString(3),"attempts",rs.getInt(4),"maxPublishAttempts",rs.getInt(5),"errorCode",rs.getString(6)),id,(long)page*20));
    }
}
