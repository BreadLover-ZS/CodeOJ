package com.codeoj.codeojbackendquestionservice.submission;

import com.codeoj.codeojbackendcommon.annotation.AuthCheck;
import com.codeoj.codeojbackendcommon.common.*;
import com.codeoj.codeojbackendmodel.model.reliability.ManualRetryRequest;
import com.codeoj.codeojbackendserviceclient.service.UserFeignClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/admin/operations")
@ConditionalOnProperty(name="codeoj.reliable-judge.enabled",havingValue="true")
public class ExecutionAdminController {
    private final ExecutionRepository repository;
    private final ExecutionCommands commands;
    private final UserFeignClient users;
    public ExecutionAdminController(ExecutionRepository repository,ExecutionCommands commands,UserFeignClient users) {
        this.repository=repository;this.commands=commands;this.users=users;
    }
    @GetMapping @AuthCheck(mustRole="admin")
    public BaseResponse<List<Map<String,Object>>> list(@RequestParam(defaultValue="0") int page,HttpServletRequest request) {
        repository.requireAccount(users.getLoginUser(request).getId(),"admin",false);
        if(page<0 || page>100000) {
            throw new com.codeoj.codeojbackendcommon.exception.BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_PAGE");
        }
        return ResultUtils.success(repository.safeRows("SELECT submissionId,executionStatus,activeAttempt,dispatchGeneration,"
                +"stateVersion,lastError,acceptedAt,recoveryDeadlineAt FROM submission_execution WHERE executionStatus<>'COMPLETED'"
                +" ORDER BY acceptedAt LIMIT 50 OFFSET ?",page*50));
    }
    @GetMapping("/{id}") @AuthCheck(mustRole="admin")
    public BaseResponse<Map<String,Object>> detail(@PathVariable long id,@RequestParam(defaultValue="0") int page,HttpServletRequest request) {
        repository.requireAccount(users.getLoginUser(request).getId(),"admin",false);
        if(page<0 || page>100000) {
            throw new com.codeoj.codeojbackendcommon.exception.BusinessException(ErrorCode.PARAMS_ERROR,"INVALID_PAGE");
        }
        List<Map<String,Object>> execution=repository.safeRows("SELECT submissionId,executionStatus,activeAttempt,dispatchGeneration,"
                +"stateVersion,lastError,acceptedAt,recoveryDeadlineAt,maxAttempts,maxDispatches,recoveryPolicyVersion"
                +" FROM submission_execution WHERE submissionId=?",id);
        if(execution.isEmpty()) { throw new com.codeoj.codeojbackendcommon.exception.BusinessException(ErrorCode.NOT_FOUND_ERROR); }
        Map<String,Object> details=new LinkedHashMap<>();details.put("execution",execution.get(0));
        details.put("attempts",repository.safeRows("SELECT attemptNo,state,startedAt,deadlineAt,finishedAt,errorCode,resourceMetrics"
                +" FROM judge_attempt WHERE submissionId=? ORDER BY attemptNo LIMIT 100 OFFSET ?",id,page*100));
        details.put("publications",repository.safeRows("SELECT o.eventId,o.aggregateVersion,o.publishState,o.lastError,"
                +"p.attemptNo,p.confirmState,p.returned,p.errorCode,p.createdAt FROM reliable_outbox o LEFT JOIN publish_attempt p ON o.eventId=p.eventId"
                +" WHERE o.aggregateId=? AND o.eventType='JUDGE_DISPATCH' ORDER BY o.aggregateVersion,p.attemptNo LIMIT 100 OFFSET ?",id,page*100));
        details.put("audit",repository.safeRows("SELECT actorId,action,reason,beforeVersion,afterVersion,policyDetails,createdAt"
                +" FROM operation_audit WHERE targetId=? ORDER BY createdAt LIMIT 100 OFFSET ?",id,page*100));
        return ResultUtils.success(details);
    }
    @PostMapping("/{id}/retry") @AuthCheck(mustRole="admin")
    public BaseResponse<Boolean> retry(@PathVariable long id,@RequestBody ManualRetryRequest request,HttpServletRequest http) {
        long actor=users.getLoginUser(http).getId(); // Auth outside the command's database transaction.
        repository.requireAccount(actor,"admin",false);
        commands.manualRetry(id,request,actor);return ResultUtils.success(true);
    }
}
