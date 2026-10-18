package com.codeoj.codeojbackendquestionservice.submission;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.codesandbox.JudgeInfo;
import com.codeoj.codeojbackendmodel.model.dto.question.*;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;
import com.codeoj.codeojbackendmodel.model.reliability.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** All commands lock the authority row before reading DB time. No network calls in transactions. */
@Service
public class ExecutionCommands {
    private final ExecutionRepository repository;
    public ExecutionCommands(ExecutionRepository repository) { this.repository=repository; }

    @Transactional(rollbackFor=Exception.class)
    public JudgeLease claim(JudgeDispatch message) {
        requireMessage(message);
        ExecutionRecord r=repository.find(message.getSubmissionId(),true);
        JudgeLease lease=new JudgeLease();
        lease.setClaimed(false);
        if(!currentMessage(r,message) || r.getExecutionStatus()!=ExecutionStatus.QUEUED) { return lease; }
        LocalDateTime now=repository.now();
        if(now.isBefore(r.getNextRunAt())) { return lease; }
        if(exhausted(r,now)) { attention(r,now,"RECOVERY_BUDGET_EXHAUSTED"); return lease; }
        String spec=repository.jdbc.queryForObject("SELECT payload FROM judge_spec WHERE specHash=?",String.class,r.getJudgeSpecHash());
        if(!ExecutionRepository.hash(spec).equals(r.getJudgeSpecHash())) {
            attention(r,now,"JUDGE_SPEC_HASH_MISMATCH"); return lease;
        }
        cn.hutool.json.JSONObject snapshot=JSONUtil.parseObj(spec);
        if(snapshot.getInt("version",0)!=1 || !"TEXT_V1".equals(snapshot.getStr("comparator"))) {
            attention(r,now,"JUDGE_SPEC_VERSION_UNSUPPORTED"); return lease;
        }
        Map<String,String> source=repository.jdbc.queryForObject("SELECT code,language FROM question_submit WHERE id=?",
                (rs,row)->{Map<String,String> m=new HashMap<>();m.put("code",rs.getString("code"));m.put("language",rs.getString("language"));return m;},r.getSubmissionId());
        r.setActiveAttempt(r.getActiveAttempt()+1);
        r.setExecutionToken(UUID.randomUUID().toString());
        r.setAttemptDeadlineAt(min(now.plusSeconds(60),r.getRecoveryDeadlineAt()));
        r.setLeaseUntil(min(now.plusSeconds(15),r.getAttemptDeadlineAt()));
        r.setExecutionStatus(ExecutionStatus.RUNNING);
        repository.save(r);
        repository.jdbc.update("INSERT INTO judge_attempt(submissionId,attemptNo,executionToken,state,startedAt,deadlineAt)"
                +" VALUES (?,?,?,'RUNNING',?,?)",r.getSubmissionId(),r.getActiveAttempt(),r.getExecutionToken(),now,r.getAttemptDeadlineAt());
        repository.jdbc.update("UPDATE question_submit SET status=1,judgeInfo='{}' WHERE id=?",r.getSubmissionId());
        lease.setClaimed(true);lease.setSubmissionId(r.getSubmissionId());lease.setAttemptNo(r.getActiveAttempt());
        lease.setExecutionToken(r.getExecutionToken());lease.setDeadlineEpochMs(r.getAttemptDeadlineAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        lease.setCode(source.get("code"));lease.setLanguage(source.get("language"));lease.setJudgeSpecHash(r.getJudgeSpecHash());
        lease.setCases(snapshot.getJSONArray("cases").toList(JudgeCase.class));
        lease.setLimits(snapshot.getBean("limits",JudgeConfig.class));
        return lease;
    }

    @Transactional(rollbackFor=Exception.class)
    public boolean heartbeat(ExecutionCommand command) {
        requireCommand(command);
        ExecutionRecord r=repository.find(command.getSubmissionId(),true);
        LocalDateTime now=repository.now();
        if(!owned(r,command,now)) { return false; }
        r.setLeaseUntil(min(now.plusSeconds(15),r.getAttemptDeadlineAt()));
        repository.save(r);return true;
    }

    @Transactional(rollbackFor=Exception.class)
    public boolean complete(ExecutionCommand command) {
        requireCommand(command);
        JudgeInfo result=canonicalResult(command.getResult());
        String json=JSONUtil.toJsonStr(result);
        String digest=ExecutionRepository.hash(json);
        ExecutionRecord r=repository.find(command.getSubmissionId(),true);
        LocalDateTime now=repository.now();
        if(r!=null && r.getExecutionStatus()==ExecutionStatus.COMPLETED && sameToken(r,command)) {
            boolean same=digest.equals(r.getResultDigest());
            if(!same) { repository.audit(r,null,"REJECT_RESULT_CONFLICT","IMMUTABLE_RESULT",r.getStateVersion(),null); }
            return same;
        }
        if(!owned(r,command,now)) {
            if(r!=null) { repository.audit(r,null,"REJECT_STALE_RESULT","TOKEN_OR_LEASE_INVALID",r.getStateVersion(),null); }
            return false;
        }
        finishAttempt(r,now,"COMPLETED",null,digest,json);
        r.setExecutionStatus(ExecutionStatus.COMPLETED);r.setResultVersion(r.getResultVersion()+1);
        r.setResultDigest(digest);r.setLeaseUntil(null);r.setLastError(null);
        repository.save(r);
        repository.jdbc.update("UPDATE question_submit SET status=2,judgeInfo=? WHERE id=?",json,r.getSubmissionId());
        if(result.getVerdict()==JudgeVerdict.AC && r.getContestId()==null) {
            repository.jdbc.update("UPDATE question SET acceptedNum=acceptedNum+1 WHERE id=(SELECT questionId FROM question_submit WHERE id=?)",r.getSubmissionId());
        }
        // Retain result events for the later projection stage; dispatch relay never sends these to judge workers.
        Map<String,Object> event=new LinkedHashMap<>();event.put("submissionId",r.getSubmissionId());event.put("resultVersion",r.getResultVersion());
        repository.jdbc.update("INSERT INTO reliable_outbox(eventId,eventType,aggregateId,aggregateVersion,payloadVersion,payload,publishState,nextRetryAt,attempts)"
                +" VALUES (?,'JUDGE_RESULT',?,?,1,?,'PENDING',?,0)",UUID.randomUUID().toString(),r.getSubmissionId(),r.getResultVersion(),JSONUtil.toJsonStr(event),now);
        return true;
    }

    @Transactional(rollbackFor=Exception.class)
    public boolean fail(ExecutionCommand command) {
        requireCommand(command);
        if(command.getErrorCode()==null || !command.getErrorCode().matches("[A-Z0-9_]{1,128}")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_PLATFORM_ERROR_CODE");
        }
        ExecutionRecord r=repository.find(command.getSubmissionId(),true);
        LocalDateTime now=repository.now();
        if(r!=null && sameToken(r,command) && (r.getExecutionStatus()==ExecutionStatus.RETRY_WAIT
                || r.getExecutionStatus()==ExecutionStatus.NEEDS_ATTENTION) && command.getErrorCode().equals(r.getLastError())) { return true; }
        if(!owned(r,command,now)) { return false; }
        finishAttempt(r,now,"FAILED",command.getErrorCode(),null,null);
        r.setLeaseUntil(null);r.setLastError(command.getErrorCode());
        if(exhausted(r,now)) { attention(r,now,command.getErrorCode()); }
        else {
            r.setExecutionStatus(ExecutionStatus.RETRY_WAIT);
            r.setNextRunAt(now.plusSeconds(Math.min(60,1L<<Math.min(6,r.getActiveAttempt()))));
            repository.save(r);
            repository.jdbc.update("UPDATE question_submit SET status=0,judgeInfo='{\"message\":\"平台暂时异常，等待重试\"}' WHERE id=?",r.getSubmissionId());
        }
        return true;
    }

    @Transactional(rollbackFor=Exception.class)
    public boolean defer(JudgeDispatch message) {
        requireMessage(message);
        ExecutionRecord r=repository.find(message.getSubmissionId(),true);
        if(!currentMessage(r,message) || r.getExecutionStatus()!=ExecutionStatus.QUEUED) { return true; }
        LocalDateTime now=repository.now();
        if(exhausted(r,now)) { attention(r,now,"CAPACITY_BUDGET_EXHAUSTED"); }
        else {
            r.setExecutionStatus(ExecutionStatus.RETRY_WAIT);r.setNextRunAt(now.plusSeconds(2));
            r.setLastError("WORKER_CAPACITY_UNAVAILABLE");repository.save(r);
        }
        return true;
    }

    @Transactional(rollbackFor=Exception.class)
    public void recover(long id) {
        ExecutionRecord r=repository.find(id,true);
        if(r==null || r.getExecutionStatus()==ExecutionStatus.COMPLETED || r.getExecutionStatus()==ExecutionStatus.NEEDS_ATTENTION) { return; }
        LocalDateTime now=repository.now();
        if(r.getExecutionStatus()==ExecutionStatus.RUNNING && r.getLeaseUntil()!=null && now.isBefore(r.getLeaseUntil())) { return; }
        if(r.getExecutionStatus()==ExecutionStatus.RETRY_WAIT && now.isBefore(r.getNextRunAt()) && now.isBefore(r.getRecoveryDeadlineAt())) { return; }
        if(r.getExecutionStatus()==ExecutionStatus.QUEUED && now.isBefore(r.getQueuedAt().plusSeconds(30)) && now.isBefore(r.getRecoveryDeadlineAt())) { return; }
        if(r.getExecutionStatus()==ExecutionStatus.RUNNING) { finishAttempt(r,now,"EXPIRED","LEASE_EXPIRED",null,null); }
        if(exhausted(r,now) || r.getDispatchGeneration()>=r.getMaxDispatches()) { attention(r,now,"RECOVERY_BUDGET_EXHAUSTED");return; }
        requeue(r,now);
    }

    @Transactional(rollbackFor=Exception.class)
    public void manualRetry(long id,ManualRetryRequest request,long actorId) {
        ExecutionRecord r=repository.find(id,true);
        if(r==null) { throw new BusinessException(ErrorCode.NOT_FOUND_ERROR); }
        repository.requireAccount(actorId,"admin",true);
        if(request==null || request.getReason()==null || request.getReason().trim().isEmpty() || request.getReason().length()>512
                || request.getAdditionalAttempts()<0 || request.getAdditionalAttempts()>10 || request.getAdditionalDispatches()<0
                || request.getAdditionalDispatches()>10 || request.getExtendRecoverySeconds()<0 || request.getExtendRecoverySeconds()>3600) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_RETRY_POLICY");
        }
        if(r.getExecutionStatus()!=ExecutionStatus.NEEDS_ATTENTION || r.getStateVersion()!=request.getExpectedVersion()) {
            throw new SubmissionConflictException("RETRY_STATE_OR_VERSION_CONFLICT");
        }
        LocalDateTime now=repository.now();long before=r.getStateVersion();
        String oldPolicy=policy(r);
        r.setMaxAttempts(r.getMaxAttempts()+request.getAdditionalAttempts());
        r.setMaxDispatches(r.getMaxDispatches()+request.getAdditionalDispatches());
        if(request.getExtendRecoverySeconds()>0) {
            r.setRecoveryDeadlineAt(min(max(now,r.getRecoveryDeadlineAt()).plusSeconds(request.getExtendRecoverySeconds()),now.plusHours(1)));
        }
        if(r.getMaxAttempts()>100 || r.getMaxDispatches()>100 || exhausted(r,now) || r.getDispatchGeneration()>=r.getMaxDispatches()) {
            throw new SubmissionConflictException("EXPLICIT_FINITE_BUDGET_EXTENSION_REQUIRED");
        }
        if(request.getAdditionalAttempts()>0 || request.getAdditionalDispatches()>0 || request.getExtendRecoverySeconds()>0) {
            r.setRecoveryPolicyVersion(r.getRecoveryPolicyVersion()+1);
        }
        requeue(r,now);
        repository.audit(r,actorId,"MANUAL_RETRY",request.getReason(),before,"{\"before\":"+oldPolicy+",\"after\":"+policy(r)+"}");
    }

    private String policy(ExecutionRecord r) {
        Map<String,Object> values=new LinkedHashMap<>();values.put("policyVersion",r.getRecoveryPolicyVersion());
        values.put("maxAttempts",r.getMaxAttempts());values.put("maxDispatches",r.getMaxDispatches());
        values.put("recoveryDeadlineAt",r.getRecoveryDeadlineAt().toString());return JSONUtil.toJsonStr(values);
    }
    private void requeue(ExecutionRecord r,LocalDateTime now) {
        r.setExecutionStatus(ExecutionStatus.QUEUED);r.setDispatchGeneration(r.getDispatchGeneration()+1);
        r.setDispatchEventId(UUID.randomUUID().toString());r.setExecutionToken(null);r.setLeaseUntil(null);
        r.setAttemptDeadlineAt(null);r.setNextRunAt(now);r.setQueuedAt(now);repository.save(r);repository.insertDispatch(r,now);
        repository.jdbc.update("UPDATE question_submit SET status=0,judgeInfo='{}' WHERE id=?",r.getSubmissionId());
    }
    private void attention(ExecutionRecord r,LocalDateTime now,String code) {
        long before=r.getStateVersion();
        r.setExecutionStatus(ExecutionStatus.NEEDS_ATTENTION);r.setLeaseUntil(null);r.setLastError(code);repository.save(r);
        repository.jdbc.update("UPDATE question_submit SET status=3,judgeInfo='{\"message\":\"平台判题异常，需管理员处理\"}' WHERE id=?",r.getSubmissionId());
        repository.audit(r,null,"NEEDS_ATTENTION",code,before,policy(r));
    }
    private void finishAttempt(ExecutionRecord r,LocalDateTime now,String state,String error,String digest,String metrics) {
        int changed=repository.jdbc.update("UPDATE judge_attempt SET state=?,finishedAt=?,errorCode=?,resultDigest=?,resourceMetrics=?"
                +" WHERE submissionId=? AND attemptNo=? AND executionToken=? AND state='RUNNING'",state,now,error,digest,metrics,
                r.getSubmissionId(),r.getActiveAttempt(),r.getExecutionToken());
        if(changed!=1) { throw new IllegalStateException("ATTEMPT_STATE_CONFLICT"); }
    }
    private boolean exhausted(ExecutionRecord r,LocalDateTime now) {
        return !now.isBefore(r.getRecoveryDeadlineAt()) || r.getActiveAttempt()>=r.getMaxAttempts();
    }
    private boolean currentMessage(ExecutionRecord r,JudgeDispatch message) {
        return r!=null && r.getDispatchGeneration()==message.getDispatchGeneration() && r.getDispatchEventId().equals(message.getEventId());
    }
    private boolean sameToken(ExecutionRecord r,ExecutionCommand command) {
        return r.getActiveAttempt()==command.getAttemptNo() && Objects.equals(r.getExecutionToken(),command.getExecutionToken());
    }
    private boolean owned(ExecutionRecord r,ExecutionCommand command,LocalDateTime now) {
        return r!=null && r.getExecutionStatus()==ExecutionStatus.RUNNING && sameToken(r,command)
                && r.getLeaseUntil()!=null && now.isBefore(r.getLeaseUntil()) && now.isBefore(r.getAttemptDeadlineAt())
                && now.isBefore(r.getRecoveryDeadlineAt());
    }
    private JudgeInfo canonicalResult(JudgeInfo result) {
        if(result==null || result.getVerdict()==null || (result.getTime()!=null && result.getTime()<0)
                || (result.getMemory()!=null && result.getMemory()<0)
                || ((result.getVerdict()==JudgeVerdict.AC || result.getVerdict()==JudgeVerdict.WA)
                && (result.getTime()==null || result.getMemory()==null))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_USER_VERDICT");
        }
        JudgeInfo canonical=new JudgeInfo();canonical.setVerdict(result.getVerdict());canonical.setMessage(result.getVerdict().getMessage());
        canonical.setTime(result.getTime());canonical.setMemory(result.getMemory());
        String method=result.getMemoryMeasurement();
        if(method!=null && !method.matches("[A-Z0-9_]{1,64}")) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_MEASUREMENT_METHOD"); }
        canonical.setMemoryMeasurement(method);return canonical;
    }
    private void requireMessage(JudgeDispatch m) { if(m==null || !m.valid()) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_DISPATCH"); } }
    private void requireCommand(ExecutionCommand c) { if(c==null || !c.valid()) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_EXECUTION_COMMAND"); } }
    private static LocalDateTime min(LocalDateTime a,LocalDateTime b) { return a.isBefore(b)?a:b; }
    private static LocalDateTime max(LocalDateTime a,LocalDateTime b) { return a.isAfter(b)?a:b; }
}
