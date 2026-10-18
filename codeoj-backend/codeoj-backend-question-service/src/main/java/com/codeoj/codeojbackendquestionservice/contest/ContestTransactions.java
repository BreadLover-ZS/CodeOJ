package com.codeoj.codeojbackendquestionservice.contest;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.dto.question.*;
import com.codeoj.codeojbackendmodel.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.codeoj.codeojbackendquestionservice.submission.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Single-DB authority. Lock order: contest -> account -> registration -> submission.
 * No Feign, Redis, MQ or sandbox calls belong in these transactions. */
@Service
public class ContestTransactions {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.codeoj.codeojbackendquestionservice.scoreboard.ScoreboardTransactions scoreboard;
    private final JdbcTemplate jdbc;
    private final ExecutionRepository executions;
    private final SubmissionAcceptance acceptance;
    public ContestTransactions(JdbcTemplate jdbc,ExecutionRepository executions,SubmissionAcceptance acceptance) {
        this.jdbc=jdbc;this.executions=executions;this.acceptance=acceptance;
    }
    static class Contest {
        long id,version; Long publishedVersion; String title,type,status,draft,mode,rule;
        int capacity,remaining; boolean ready;
        LocalDateTime open,close,start,end;
    }
    Contest load(long id,boolean lock) {
        List<Contest> rows=jdbc.query("SELECT * FROM contest WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->{
            Contest c=new Contest();c.id=rs.getLong("id");c.version=rs.getLong("version");
            c.publishedVersion=rs.getObject("publishedVersion")==null?null:rs.getLong("publishedVersion");
            c.title=rs.getString("title");c.type=rs.getString("contestType");c.status=rs.getString("status");
            c.draft=rs.getString("draftPayload");c.mode=rs.getString("registrationMode");c.rule=rs.getString("ruleVersion");
            c.capacity=rs.getInt("capacity");c.remaining=rs.getInt("remainingCapacity");c.ready=rs.getBoolean("registrationReady");
            c.open=rs.getObject("registrationOpenAt",LocalDateTime.class);c.close=rs.getObject("registrationCloseAt",LocalDateTime.class);
            c.start=rs.getObject("startAt",LocalDateTime.class);c.end=rs.getObject("endAt",LocalDateTime.class);return c;
        },id);
        if(rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND_ERROR); } return rows.get(0);
    }
    static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) { result.put((String)pairs[i],pairs[i+1]); }return result;
    }
    private static String utc(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC).toString(); }
    private static LocalDateTime instant(String value) {
        try { return LocalDateTime.ofInstant(Instant.parse(value),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
        catch(RuntimeException error) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"EXPLICIT_UTC_TIME_REQUIRED"); }
    }
    private static void invalid(String code) { throw new BusinessException(ErrorCode.PARAMS_ERROR,code); }
    private static void conflict(String code) { throw new SubmissionConflictException(code); }
    private void validate(ContestDraft d) {
        if(d==null || d.getTitle()==null || d.getTitle().trim().isEmpty() || d.getTitle().length()>120
                || d.getCapacity()==null || d.getCapacity()<1 || d.getCapacity()>1000000
                || !("PRIVATE".equals(d.getContestType()) || "PUBLIC_TRAINING".equals(d.getContestType()))
                || d.getProblems()==null || d.getProblems().isEmpty() || d.getProblems().size()>20) { invalid("INVALID_CONTEST_DRAFT"); }
        LocalDateTime open=instant(d.getRegistrationOpenAt()),close=instant(d.getRegistrationCloseAt()),start=instant(d.getStartAt()),end=instant(d.getEndAt());
        if(!(open.isBefore(close)&&close.isBefore(start)&&start.isBefore(end))) { invalid("INVALID_CONTEST_TIME_ORDER"); }
        Set<String> codes=new HashSet<>();Set<Long> sources=new HashSet<>();
        for(ContestDraft.Problem p:d.getProblems()) {
            if(p==null || p.getProblemCode()==null || !p.getProblemCode().matches("[A-Z][A-Z0-9]{0,7}")
                    || !codes.add(p.getProblemCode())) { invalid("INVALID_OR_DUPLICATE_PROBLEM_CODE"); }
            if("PRIVATE".equals(d.getContestType())) {
                if(p.getSourceQuestionId()!=null || p.getTitle()==null || p.getTitle().trim().isEmpty() || p.getTitle().length()>120
                        || p.getContent()==null || p.getContent().trim().isEmpty()
                        || p.getContent().getBytes(StandardCharsets.UTF_8).length>256*1024) { invalid("INVALID_PRIVATE_PROBLEM"); }
                SubmissionAcceptance.validateSpec(p.getLimits(),p.getCases());
            } else if(p.getSourceQuestionId()==null || p.getSourceQuestionId()<=0 || !sources.add(p.getSourceQuestionId())
                    || p.getTitle()!=null || p.getContent()!=null || p.getLimits()!=null || p.getCases()!=null) { invalid("PUBLIC_TRAINING_REQUIRES_SOURCE_ONLY"); }
        }
    }
    private void expected(Contest c,Long version) { if(version==null || version!=c.version) { conflict("CONTEST_VERSION_CONFLICT"); } }
    private void audit(Contest c,long actor,String action,String reason,long before) {
        jdbc.update("INSERT INTO operation_audit(id,actorId,action,targetId,reason,beforeVersion,afterVersion,policyDetails,createdAt) VALUES(?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),actor,action,c.id,reason,before,c.version,null,executions.now());
    }
    private void reason(ContestCommand command) {
        if(command==null || command.getReason()==null || command.getReason().trim().isEmpty() || command.getReason().length()>500) { invalid("OPERATION_REASON_REQUIRED"); }
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> create(ContestDraft d,long actor) {
        validate(d);executions.requireAccount(actor,"admin",true);long id=IdWorker.getId();
        jdbc.update("INSERT INTO contest(id,title,contestType,capacity,remainingCapacity,registrationOpenAt,registrationCloseAt,startAt,endAt,status,version,publishedVersion,registrationReady,ruleVersion,registrationMode,draftPayload,createdBy,createdAt)"
                +" VALUES(?,?,?,?,?,?,?,?,?,'DRAFT',0,NULL,false,'FIRST_AC_V1','DB_SYNC',?,?,?)",id,d.getTitle(),d.getContestType(),d.getCapacity(),d.getCapacity(),
                instant(d.getRegistrationOpenAt()),instant(d.getRegistrationCloseAt()),instant(d.getStartAt()),instant(d.getEndAt()),JSONUtil.toJsonStr(d),actor,executions.now());
        Contest c=load(id,false);audit(c,actor,"CONTEST_CREATE","DRAFT_CREATED",0);return view(c,executions.now());
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> update(long id,ContestDraft d,long actor) {
        Contest c=load(id,true);executions.requireAccount(actor,"admin",true);validate(d);expected(c,d.getExpectedVersion());
        if(!"DRAFT".equals(c.status)) { conflict("PUBLISHED_CONTEST_IS_FROZEN"); }
        jdbc.update("UPDATE contest SET title=?,contestType=?,capacity=?,remainingCapacity=?,registrationOpenAt=?,registrationCloseAt=?,startAt=?,endAt=?,draftPayload=?,version=version+1 WHERE id=?",
                d.getTitle(),d.getContestType(),d.getCapacity(),d.getCapacity(),instant(d.getRegistrationOpenAt()),instant(d.getRegistrationCloseAt()),instant(d.getStartAt()),instant(d.getEndAt()),JSONUtil.toJsonStr(d),id);
        Contest updated=load(id,false);audit(updated,actor,"CONTEST_EDIT","DRAFT_UPDATED",c.version);return view(updated,executions.now());
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> publish(long id,ContestCommand command,long actor) {
        Contest c=load(id,true);executions.requireAccount(actor,"admin",true);
        if(command==null || command.getExpectedVersion()==null) { invalid("EXPECTED_VERSION_REQUIRED"); }
        if(("PUBLISHED".equals(c.status)||"FINALIZING".equals(c.status)) && c.publishedVersion!=null
                && command.getExpectedVersion()+1==c.publishedVersion) { return view(c,executions.now()); }
        expected(c,command.getExpectedVersion());if(!"DRAFT".equals(c.status)) { conflict("CONTEST_NOT_DRAFT"); }
        ContestDraft draft=JSONUtil.toBean(c.draft,ContestDraft.class);validate(draft);long published=c.version+1;
        for(ContestDraft.Problem p:draft.getProblems()) {
            String title=p.getTitle(),content=p.getContent();JudgeConfig limits=p.getLimits();List<JudgeCase> cases=p.getCases();
            if("PUBLIC_TRAINING".equals(c.type)) {
                List<ContestDraft.Problem> source=jdbc.query("SELECT title,content,judgeConfig,judgeCase FROM question WHERE id=? AND isDelete=0 FOR UPDATE",(rs,n)->{
                    ContestDraft.Problem q=new ContestDraft.Problem();q.setTitle(rs.getString(1));q.setContent(rs.getString(2));
                    q.setLimits(JSONUtil.toBean(rs.getString(3),JudgeConfig.class));q.setCases(JSONUtil.toList(rs.getString(4),JudgeCase.class));return q;
                },p.getSourceQuestionId());
                if(source.isEmpty()) { invalid("PUBLIC_SOURCE_NOT_FOUND"); }
                title=source.get(0).getTitle();content=source.get(0).getContent();limits=source.get(0).getLimits();cases=source.get(0).getCases();
            }
            String spec=acceptance.freezeSpec(limits,cases);
            String hash=ExecutionRepository.hash(JSONUtil.toJsonStr(map("problemCode",p.getProblemCode(),"title",title,"content",content,"judgeSpecHash",spec)));
            jdbc.update("INSERT INTO contest_problem_snapshot(id,contestId,publishedVersion,problemCode,sourceQuestionId,title,content,judgeSpecHash,snapshotHash) VALUES(?,?,?,?,?,?,?,?,?)",
                    IdWorker.getId(),id,published,p.getProblemCode(),p.getSourceQuestionId(),title,content,spec,hash);
        }
        LocalDateTime now=executions.now();if(!now.isBefore(c.open)) { conflict("PUBLISH_BEFORE_REGISTRATION_OPENS"); }
        jdbc.update("UPDATE contest SET status='PUBLISHED',version=?,publishedVersion=?,registrationReady=true WHERE id=?",published,published,id);
        Contest updated=load(id,false);audit(updated,actor,"CONTEST_PUBLISH","SNAPSHOTS_FROZEN",c.version);return view(updated,now);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> cancel(long id,ContestCommand command,long actor) {
        Contest c=load(id,true);executions.requireAccount(actor,"admin",true);reason(command);
        if("CANCELLED".equals(c.status)) { return view(c,executions.now()); }
        expected(c,command.getExpectedVersion());if("FINISHED".equals(c.status)) { conflict("CONTEST_ALREADY_FINISHED"); }
        jdbc.update("UPDATE contest SET status='CANCELLED',registrationReady=false,version=version+1 WHERE id=?",id);
        // Confirmed rows are retained; qualification always also checks the contest authority.
        jdbc.update("UPDATE contest_registration_request SET status='CANCELLED',stateVersion=stateVersion+1 WHERE contestId=? AND status='PROCESSING'",id);
        Contest updated=load(id,false);audit(updated,actor,"CONTEST_CANCEL",command.getReason(),c.version);return view(updated,executions.now());
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> readiness(long id,ContestCommand command,long actor) {
        Contest c=load(id,true);executions.requireAccount(actor,"admin",true);reason(command);expected(c,command.getExpectedVersion());
        if(!"PUBLISHED".equals(c.status) || command.getRegistrationReady()==null) { conflict("INVALID_READINESS_CHANGE"); }
        if(command.getRegistrationReady() && c.mode.startsWith("REDIS_")) { conflict("USE_REGISTRATION_RECONCILIATION_TO_RESUME"); }
        if(command.getRegistrationReady()) {
            int confirmed=jdbc.queryForObject("SELECT COUNT(*) FROM contest_registration WHERE contestId=? AND status='CONFIRMED'",Integer.class,id);
            if(confirmed+c.remaining!=c.capacity) { conflict("REGISTRATION_LEDGER_MISMATCH"); }
        }
        jdbc.update("UPDATE contest SET registrationReady=?,version=version+1 WHERE id=?",command.getRegistrationReady(),id);
        Contest updated=load(id,false);audit(updated,actor,"CONTEST_READINESS",command.getReason(),c.version);return view(updated,executions.now());
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> register(long id,String key,long user) {
        requireKey(key);Contest c=load(id,true);executions.requireAccount(user,null,true);
        List<Map<String,Object>> prior=registrationRows(id,user);
        if(!prior.isEmpty()) {
            String original=jdbc.queryForObject("SELECT requestKey FROM contest_registration_request WHERE contestId=? AND userId=?",String.class,id,user);
            if(!key.equals(original)) { conflict("REGISTRATION_ALREADY_HAS_ANOTHER_KEY"); }
            return registrationView(c,prior);
        }
        LocalDateTime now=executions.now();
        if(!"PUBLISHED".equals(c.status) || !c.ready || !"DB_SYNC".equals(c.mode)) { conflict("REGISTRATION_UNAVAILABLE"); }
        if(now.isBefore(c.open) || !now.isBefore(c.close)) { conflict("REGISTRATION_WINDOW_CLOSED"); }
        long request=IdWorker.getId();String event=UUID.randomUUID().toString();
        String hash=ExecutionRepository.hash(JSONUtil.toJsonStr(map("type","REGISTRATION","contestId",id,"publishedVersion",c.publishedVersion)));
        jdbc.update("INSERT INTO contest_registration_request(id,contestId,publishedVersion,userId,eventId,requestKey,requestHash,reservedAt,status,stateVersion,reclaimState,createdAt) VALUES(?,?,?,?,?,?,?,?,'PROCESSING',0,'NOT_REQUIRED',?)",
                request,id,c.publishedVersion,user,event,key,hash,now,now);
        confirmRegistration(c,request,user,now);
        return registrationView(load(id,false),registrationRows(id,user));
    }
    /** Caller owns the contest lock and transaction. J4 must enter this authority boundary after verifying its reservation identity. */
    void confirmRegistration(Contest c,long request,long user,LocalDateTime now) {
        if(!"PUBLISHED".equals(c.status) && !"FINALIZING".equals(c.status)) { conflict("CONTEST_NOT_CONFIRMABLE"); }
        List<String> states=jdbc.queryForList("SELECT status FROM contest_registration_request WHERE id=? AND contestId=? AND publishedVersion=? AND userId=? FOR UPDATE",
                String.class,request,c.id,c.publishedVersion,user);
        if(states.isEmpty()) { conflict("REGISTRATION_IDENTITY_MISMATCH"); }
        if("CONFIRMED".equals(states.get(0))) { return; }
        if(!"PROCESSING".equals(states.get(0))) { conflict("REGISTRATION_NOT_CONFIRMABLE"); }
        if(jdbc.update("UPDATE contest SET remainingCapacity=remainingCapacity-1 WHERE id=? AND remainingCapacity>0",c.id)!=1) { conflict("CONTEST_FULL"); }
        jdbc.update("INSERT INTO contest_registration(id,contestId,userId,requestId,confirmedAt,status) VALUES(?,?,?,?,?,'CONFIRMED')",IdWorker.getId(),c.id,user,request,now);
        if(jdbc.update("UPDATE contest_registration_request SET status='CONFIRMED',stateVersion=stateVersion+1 WHERE id=? AND status='PROCESSING'",request)!=1) { conflict("REGISTRATION_STATE_CONFLICT"); }
        if(scoreboard!=null) { scoreboard.qualificationConfirmed(c.id); }
    }
    public static void requireKey(String key) { if(key==null || !key.matches("[A-Za-z0-9._:-]{8,128}")) { invalid("INVALID_IDEMPOTENCY_KEY"); } }
    private List<Map<String,Object>> registrationRows(long id,long user) {
        return jdbc.query("SELECT r.id,r.status,q.status AS qualificationStatus FROM contest_registration_request r LEFT JOIN contest_registration q ON q.requestId=r.id WHERE r.contestId=? AND r.userId=?",
                (rs,n)->map("requestId",rs.getString(1),"status",rs.getString(2),"confirmed","CONFIRMED".equals(rs.getString(3))),id,user);
    }
    private Map<String,Object> registrationView(Contest c,List<Map<String,Object>> rows) {
        Map<String,Object> result=rows.isEmpty()?map("requestId",null,"status","NOT_REGISTERED","confirmed",false):rows.get(0);
        boolean active="PUBLISHED".equals(c.status)||"FINALIZING".equals(c.status)||"FINISHED".equals(c.status);
        result.put("qualificationValid",active && c.ready && Boolean.TRUE.equals(result.remove("confirmed")));
        result.put("contestStatus",c.status);result.put("publishedVersion",c.publishedVersion);
        result.put("statusUrl","/api/contests/"+c.id+"/registrations/me");return result;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> registration(long id,long user) {
        Contest c=load(id,true);executions.requireAccount(user,null,false);if("DRAFT".equals(c.status)) { throw new BusinessException(ErrorCode.NOT_FOUND_ERROR); }
        return registrationView(c,registrationRows(id,user));
    }
    public static String submissionHash(long id,ContestSubmission request) {
        return ExecutionRepository.hash(JSONUtil.toJsonStr(map("type","CONTEST","contestId",id,"snapshotId",request.getProblemSnapshotId(),
                "publishedVersion",request.getPublishedVersion(),"language",request.getLanguage(),"code",request.getCode())));
    }
    @Transactional(rollbackFor=Exception.class)
    public long submit(long id,ContestSubmission request,String key,long user) {
        requireKey(key);
        if(request==null || request.getProblemSnapshotId()==null || request.getPublishedVersion()==null || !"java".equals(request.getLanguage())
                || request.getCode()==null || request.getCode().trim().isEmpty() || request.getCode().getBytes(StandardCharsets.UTF_8).length>256*1024) { invalid("INVALID_CONTEST_SUBMISSION"); }
        String hash=submissionHash(id,request);ExecutionRecord previous=executions.byKey(user,key);
        if(previous!=null) { return SubmissionAcceptance.replay(previous,hash); }
        Contest c=load(id,true);executions.requireAccount(user,null,true);
        List<Map<String,Object>> registrations=registrationRows(id,user);
        if(registrations.isEmpty() || !Boolean.TRUE.equals(registrations.get(0).get("confirmed"))) { conflict("CONFIRMED_REGISTRATION_REQUIRED"); }
        if(!"PUBLISHED".equals(c.status) || !c.ready || !request.getPublishedVersion().equals(c.publishedVersion)) { conflict("CONTEST_NOT_ACCEPTING_SUBMISSIONS"); }
        List<Map<String,Object>> snapshots=jdbc.query("SELECT sourceQuestionId,judgeSpecHash FROM contest_problem_snapshot WHERE id=? AND contestId=? AND publishedVersion=?",
                (rs,n)->map("source",rs.getObject(1)==null?0L:rs.getLong(1),"spec",rs.getString(2)),request.getProblemSnapshotId(),id,c.publishedVersion);
        if(snapshots.isEmpty()) { invalid("SNAPSHOT_NOT_IN_CONTEST"); }
        LocalDateTime now=executions.now();if(now.isBefore(c.start) || !now.isBefore(c.end)) { conflict("CONTEST_SUBMISSION_WINDOW_CLOSED"); }
        QuestionSubmitAddRequest input=new QuestionSubmitAddRequest();input.setQuestionId((Long)snapshots.get(0).get("source"));
        input.setRequestKey(key);input.setLanguage(request.getLanguage());input.setCode(request.getCode());
        // Private snapshots have no practice question: legacy questionId=0, authoritative context in the sidecar.
        return acceptance.write(input,user,hash,(String)snapshots.get(0).get("spec"),id,request.getProblemSnapshotId(),c.publishedVersion,now);
    }
    private Map<String,Object> view(Contest c,LocalDateTime now) {
        String phase=c.status;
        if("PUBLISHED".equals(c.status)) { phase=now.isBefore(c.open)?"BEFORE_REGISTRATION":now.isBefore(c.close)?"REGISTRATION_OPEN":now.isBefore(c.start)?"WAITING_START":now.isBefore(c.end)?"RUNNING":"FINALIZING"; }
        return map("id",Long.toString(c.id),"title",c.title,"contestType",c.type,"capacity",c.capacity,"remainingCapacity",c.remaining,
                "registrationOpenAt",utc(c.open),"registrationCloseAt",utc(c.close),"startAt",utc(c.start),"endAt",utc(c.end),"status",c.status,
                "phase",phase,"version",c.version,"publishedVersion",c.publishedVersion,"registrationReady",c.ready,"ruleVersion",c.rule,"registrationMode",c.mode,"serverTime",utc(now));
    }
    public List<Map<String,Object>> list(int page,boolean admin,long user) {
        if(page<0 || page>10000) { invalid("INVALID_PAGE"); }if(admin) { executions.requireAccount(user,"admin",false); }
        List<Long> ids=jdbc.queryForList("SELECT id FROM contest"+(admin?"":" WHERE status<>'DRAFT'")+" ORDER BY createdAt DESC,id DESC LIMIT 20 OFFSET ?",Long.class,page*20);
        LocalDateTime now=executions.now();List<Map<String,Object>> result=new ArrayList<>();for(long id:ids) { result.add(view(load(id,false),now)); }return result;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> detail(long id,long user,boolean admin) {
        Contest c=load(id,true);if(admin) { executions.requireAccount(user,"admin",false); }
        if(!admin && "DRAFT".equals(c.status)) { throw new BusinessException(ErrorCode.NOT_FOUND_ERROR); }
        LocalDateTime now=executions.now();Map<String,Object> result=view(c,now);
        if(admin) { result.put("draft",JSONUtil.parseObj(c.draft)); }
        boolean visible=false;
        if(!admin && !"CANCELLED".equals(c.status) && !"DRAFT".equals(c.status) && !now.isBefore(c.start)) {
            executions.requireAccount(user,null,false);List<Map<String,Object>> registration=registrationRows(id,user);
            visible=!registration.isEmpty() && Boolean.TRUE.equals(registration.get(0).get("confirmed"));
        }
        if(admin || visible) {
            result.put("problems",jdbc.query("SELECT p.id,p.problemCode,p.title,p.content,s.payload FROM contest_problem_snapshot p JOIN judge_spec s ON s.specHash=p.judgeSpecHash WHERE p.contestId=? AND p.publishedVersion=? ORDER BY p.problemCode",
                    (rs,n)->map("id",rs.getString(1),"problemCode",rs.getString(2),"title",rs.getString(3),"content",rs.getString(4),"limits",JSONUtil.parseObj(rs.getString(5)).get("limits")),id,c.publishedVersion));
        } else { result.put("problems",Collections.emptyList()); }
        return result;
    }
    @Transactional(rollbackFor=Exception.class)
    public void finalizeIfDue(long id) {
        Contest c=load(id,true);LocalDateTime now=executions.now();
        if("PUBLISHED".equals(c.status) && !now.isBefore(c.end)) {
            jdbc.update("UPDATE contest SET status='FINALIZING',version=version+1 WHERE id=?",id);
            Contest updated=load(id,false);audit(updated,0,"CONTEST_FINALIZING","END_AT_REACHED",c.version);
        }
    }
}
