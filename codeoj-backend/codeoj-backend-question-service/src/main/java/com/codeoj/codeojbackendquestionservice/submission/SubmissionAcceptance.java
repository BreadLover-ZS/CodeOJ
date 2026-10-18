package com.codeoj.codeojbackendquestionservice.submission;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.dto.question.*;
import com.codeoj.codeojbackendmodel.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.codeoj.codeojbackendmodel.model.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/** Separate proxied bean: no broker, Redis, Feign or sandbox calls inside this transaction. */
@Service
public class SubmissionAcceptance {
    private final ExecutionRepository repository;
    public SubmissionAcceptance(ExecutionRepository repository) { this.repository = repository; }

    public static String requestHash(QuestionSubmitAddRequest request) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "PRACTICE");
        content.put("questionId", request.getQuestionId());
        content.put("language", request.getLanguage());
        content.put("code", request.getCode());
        return ExecutionRepository.hash(JSONUtil.toJsonStr(content));
    }

    @Transactional(rollbackFor = Exception.class)
    public long accept(QuestionSubmitAddRequest request, User user) {
        String hash = requestHash(request);
        ExecutionRecord prior = repository.byKey(user.getId(), request.getRequestKey());
        if (prior != null) { return replay(prior, hash); }
        repository.requireAccount(user.getId(),null,true);
        List<Map<String,String>> rows=repository.jdbc.query(
                "SELECT judgeConfig,judgeCase FROM question WHERE id=? AND isDelete=0", (rs, row) -> {
                    Map<String,String> q = new HashMap<>();
                    q.put("limits",rs.getString("judgeConfig")); q.put("cases",rs.getString("judgeCase")); return q;
                }, request.getQuestionId());
        if(rows.isEmpty()) { throw new BusinessException(ErrorCode.NOT_FOUND_ERROR); }
        Map<String,String> question=rows.get(0);
        JudgeConfig limits = JSONUtil.toBean(question.get("limits"), JudgeConfig.class);
        List<JudgeCase> cases = JSONUtil.toList(question.get("cases"), JudgeCase.class);
        String specHash = freezeSpec(limits,cases);
        long id = write(request,user.getId(),hash,specHash,null,null,null,repository.now());
        repository.jdbc.update("UPDATE question SET submitNum=submitNum+1 WHERE id=?",request.getQuestionId());
        return id;
    }

    /** Caller must hold the business authority locks in an existing transaction. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public long write(QuestionSubmitAddRequest request,long userId,String hash,String specHash,
                      Long contestId,Long snapshotId,Long publishedVersion,LocalDateTime now) {
        long id = IdWorker.getId();
        repository.jdbc.update("INSERT INTO question_submit(id,language,code,judgeInfo,status,questionId,userId,isDelete)"
                        + " VALUES (?,?,?,'{}',0,?,?,0)",id,request.getLanguage(),request.getCode(),request.getQuestionId(),userId);
        String eventId = UUID.randomUUID().toString();
        repository.jdbc.update("INSERT INTO submission_execution(submissionId,userId,requestKey,requestHash,judgeSpecHash,"
                        + "executionStatus,dispatchGeneration,dispatchEventId,activeAttempt,nextRunAt,queuedAt,acceptedAt,"
                        + "recoveryDeadlineAt,maxAttempts,maxDispatches,recoveryPolicyVersion,resultVersion,stateVersion)"
                        + " VALUES (?,?,?,?,?,'QUEUED',1,?,0,?,?,?,?,3,10,1,0,0)",id,userId,request.getRequestKey(),hash,
                specHash,eventId,now,now,now,now.plusMinutes(15));
        if(contestId!=null) {
            repository.jdbc.update("UPDATE submission_execution SET contestId=?,problemSnapshotId=?,publishedVersion=? WHERE submissionId=?",
                    contestId,snapshotId,publishedVersion,id);
        }
        ExecutionRecord record = repository.find(id,false);
        repository.insertDispatch(record,now);
        return id;
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public String freezeSpec(JudgeConfig limits,List<JudgeCase> cases) {
        validateSpec(limits,cases);
        Map<String,Object> spec=new LinkedHashMap<>();
        spec.put("version",1);spec.put("comparator","TEXT_V1");spec.put("limits",limits);spec.put("cases",cases);
        String payload=JSONUtil.toJsonStr(spec),hash=ExecutionRepository.hash(payload);
        repository.jdbc.update("INSERT INTO judge_spec(specHash,payload) VALUES (?,?) ON DUPLICATE KEY UPDATE specHash=specHash",hash,payload);
        return hash;
    }

    public static long replay(ExecutionRecord prior,String hash) {
        if (!prior.getRequestHash().equals(hash)) { throw new SubmissionConflictException("IDEMPOTENCY_CONTENT_CONFLICT"); }
        return prior.getSubmissionId();
    }

    public static void validateSpec(JudgeConfig limits,List<JudgeCase> cases) {
        if (limits==null || limits.getTimeLimit()==null || limits.getTimeLimit()<=0 || limits.getMemoryLimit()==null
                || limits.getMemoryLimit()<=0 || limits.getMemoryLimit()>1048576 || cases==null || cases.isEmpty() || cases.size()>20) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"INVALID_PRIVATE_JUDGE_SPEC");
        }
        long inputBytes=0;
        for (JudgeCase c:cases) {
            if (c==null || c.getInput()==null || c.getOutput()==null || c.getOutput().getBytes(StandardCharsets.UTF_8).length>65536) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,"INVALID_PRIVATE_JUDGE_CASE");
            }
            inputBytes+=c.getInput().getBytes(StandardCharsets.UTF_8).length;
        }
        if(inputBytes>1024*1024) { throw new BusinessException(ErrorCode.OPERATION_ERROR,"JUDGE_INPUT_BUDGET_EXCEEDED"); }
    }
}
