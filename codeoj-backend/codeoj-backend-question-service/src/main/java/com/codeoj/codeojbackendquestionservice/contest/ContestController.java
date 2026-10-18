package com.codeoj.codeojbackendquestionservice.contest;

import com.codeoj.codeojbackendcommon.common.*;
import com.codeoj.codeojbackendserviceclient.service.UserFeignClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/contests")
@ConditionalOnProperty(name="codeoj.reliable-judge.enabled",havingValue="true")
public class ContestController {
    private final ContestTransactions transactions;
    private final ContestService service;
    private final UserFeignClient users;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private RegistrationService registrations;
    public ContestController(ContestTransactions transactions,ContestService service,UserFeignClient users) {
        this.transactions=transactions;this.service=service;this.users=users;
    }
    private long actor(HttpServletRequest request) { return users.getLoginUser(request).getId(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public BaseResponse<Map<String,Object>> create(@RequestBody ContestDraft draft,HttpServletRequest http) {
        long actor=actor(http);return ResultUtils.success(service.retry(()->transactions.create(draft,actor)));
    }
    @PutMapping("/{id}")
    public BaseResponse<Map<String,Object>> update(@PathVariable long id,@RequestBody ContestDraft draft,HttpServletRequest http) {
        long actor=actor(http);return ResultUtils.success(service.retry(()->transactions.update(id,draft,actor)));
    }
    @PostMapping("/{id}/publish")
    public BaseResponse<Map<String,Object>> publish(@PathVariable long id,@RequestBody ContestCommand command,HttpServletRequest http) {
        long actor=actor(http);return ResultUtils.success(service.retry(()->transactions.publish(id,command,actor)));
    }
    @PostMapping("/{id}/cancel")
    public BaseResponse<Map<String,Object>> cancel(@PathVariable long id,@RequestBody ContestCommand command,HttpServletRequest http) {
        long actor=actor(http);return ResultUtils.success(service.retry(()->transactions.cancel(id,command,actor)));
    }
    @PostMapping("/{id}/readiness")
    public BaseResponse<Map<String,Object>> readiness(@PathVariable long id,@RequestBody ContestCommand command,HttpServletRequest http) {
        long actor=actor(http);return ResultUtils.success(service.retry(()->transactions.readiness(id,command,actor)));
    }
    @GetMapping
    public BaseResponse<List<Map<String,Object>>> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="false") boolean admin,HttpServletRequest http) {
        return ResultUtils.success(transactions.list(page,admin,admin?actor(http):0));
    }
    @GetMapping("/{id}")
    public BaseResponse<Map<String,Object>> detail(@PathVariable long id,@RequestParam(defaultValue="false") boolean admin,HttpServletRequest http) {
        return ResultUtils.success(transactions.detail(id,actor(http),admin));
    }
    @PostMapping("/{id}/registrations") @ResponseStatus(HttpStatus.ACCEPTED)
    public BaseResponse<Map<String,Object>> register(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,HttpServletRequest http) {
        long actor=actor(http);return ResultUtils.success(registrations==null?service.retry(()->transactions.register(id,key,actor)):registrations.register(id,key,actor));
    }
    @GetMapping("/{id}/registrations/me")
    public BaseResponse<Map<String,Object>> registration(@PathVariable long id,HttpServletRequest http) {
        long actor=actor(http);return ResultUtils.success(registrations==null?transactions.registration(id,actor):registrations.status(id,actor));
    }
    @PostMapping("/{id}/submissions") @ResponseStatus(HttpStatus.ACCEPTED)
    public BaseResponse<String> submit(@PathVariable long id,@RequestBody ContestSubmission submission,@RequestHeader("Idempotency-Key") String key,HttpServletRequest http) {
        return ResultUtils.success(Long.toString(service.submit(id,submission,key,actor(http))));
    }
}
