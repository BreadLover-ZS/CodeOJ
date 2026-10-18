package com.codeoj.codeojbackendquestionservice.contest;

import com.codeoj.codeojbackendcommon.common.*;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendquestionservice.submission.ExecutionRepository;
import com.codeoj.codeojbackendserviceclient.service.UserFeignClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController @RequestMapping("/contests/{contest}/registration-admin")
@ConditionalOnExpression("${codeoj.reliable-judge.enabled:false} && ${codeoj.registration.enabled:false}")
public class RegistrationAdminController {
    private final RegistrationService service;private final RegistrationTransactions transactions;private final ExecutionRepository executions;
    private final UserFeignClient users;private final JdbcTemplate jdbc;
    public RegistrationAdminController(RegistrationService service,RegistrationTransactions transactions,ExecutionRepository executions,UserFeignClient users,JdbcTemplate jdbc) { this.service=service;this.transactions=transactions;this.executions=executions;this.users=users;this.jdbc=jdbc; }
    @PostMapping("/prepare")
    public BaseResponse<RegistrationSettings> prepare(@PathVariable long contest,@RequestBody ContestCommand command,HttpServletRequest request) { return ResultUtils.success(service.prepare(contest,command,users.getLoginUser(request).getId())); }
    @PostMapping("/reconcile")
    public BaseResponse<RegistrationSettings> reconcile(@PathVariable long contest,@RequestBody ContestCommand command,HttpServletRequest request) { return ResultUtils.success(service.reconcile(contest,command,users.getLoginUser(request).getId())); }
    @PostMapping("/requests/{id}/review")
    public BaseResponse<Boolean> review(@PathVariable long contest,@PathVariable long id,@RequestBody RegistrationReview command,HttpServletRequest request) {
        long actor=users.getLoginUser(request).getId();service.retry(()->{transactions.review(contest,id,command,actor);return true;});return ResultUtils.success(true);
    }
    @GetMapping
    public BaseResponse<Map<String,Object>> detail(@PathVariable long contest,@RequestParam(defaultValue="0") int page,HttpServletRequest request) {
        executions.requireAccount(users.getLoginUser(request).getId(),"admin",false);if(page<0 || page>10000) { throw new BusinessException(ErrorCode.PARAMS_ERROR); }
        List<Map<String,Object>> rows=jdbc.query("SELECT id,userId,status,stateVersion,reclaimState,lastError,eventId,maxPublishAttempts FROM contest_registration_request WHERE contestId=? ORDER BY createdAt,id LIMIT 20 OFFSET ?",(rs,n)->ContestTransactions.map("requestId",rs.getString(1),"userId",rs.getString(2),"status",rs.getString(3),"stateVersion",rs.getLong(4),"reclaimState",rs.getString(5),"lastError",rs.getString(6),"eventId",rs.getString(7),"maxPublishAttempts",rs.getInt(8)),contest,page*20);
        for(Map<String,Object> row:rows) { row.put("attempts",executions.safeRows("SELECT attemptNo,confirmState,returned,errorCode,createdAt FROM publish_attempt WHERE eventId=? ORDER BY attemptNo DESC LIMIT 20",row.get("eventId"))); }
        return ResultUtils.success(ContestTransactions.map("settings",transactions.settings(contest),"requests",rows));
    }
}
