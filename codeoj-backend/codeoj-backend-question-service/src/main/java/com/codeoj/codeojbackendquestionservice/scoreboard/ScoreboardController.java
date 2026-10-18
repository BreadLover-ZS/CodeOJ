package com.codeoj.codeojbackendquestionservice.scoreboard;

import com.codeoj.codeojbackendcommon.common.*;
import com.codeoj.codeojbackendserviceclient.service.UserFeignClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/contests/{id}/scoreboard")
@ConditionalOnProperty(name="codeoj.scoreboard.enabled",havingValue="true")
public class ScoreboardController {
    private final ScoreboardService service;private final ScoreboardTransactions transactions;private final UserFeignClient users;
    public ScoreboardController(ScoreboardService service,ScoreboardTransactions transactions,UserFeignClient users) { this.service=service;this.transactions=transactions;this.users=users; }
    private long actor(HttpServletRequest http) { return users.getLoginUser(http).getId(); }
    @GetMapping public BaseResponse<Map<String,Object>> page(@PathVariable long id,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,@RequestParam(required=false) Long version,HttpServletRequest http) {
        return ResultUtils.success(service.page(id,page,size,version,actor(http)));
    }
    @GetMapping("/operations") public BaseResponse<Map<String,Object>> operations(@PathVariable long id,@RequestParam(defaultValue="0") int page,HttpServletRequest http) {
        return ResultUtils.success(transactions.operations(id,page,actor(http)));
    }
    @PostMapping("/rebuild") public BaseResponse<Map<String,Object>> rebuild(@PathVariable long id,@RequestBody ScoreboardCommand command,HttpServletRequest http) {
        return ResultUtils.success(transactions.rebuild(id,command,actor(http),false));
    }
    @PostMapping("/revise") public BaseResponse<Map<String,Object>> revise(@PathVariable long id,@RequestBody ScoreboardCommand command,HttpServletRequest http) {
        return ResultUtils.success(transactions.rebuild(id,command,actor(http),true));
    }
    @PostMapping("/finish") public BaseResponse<Boolean> finish(@PathVariable long id,@RequestBody ScoreboardCommand command,HttpServletRequest http) {
        transactions.finish(id,command,actor(http));return ResultUtils.success(true);
    }
    @PostMapping("/cache-clear") public BaseResponse<Boolean> clear(@PathVariable long id,@RequestBody ScoreboardCommand command,HttpServletRequest http) {
        transactions.clearCache(id,command,actor(http));return ResultUtils.success(true);
    }
    @PostMapping("/replay") public BaseResponse<Integer> replay(@PathVariable long id,@RequestBody ScoreboardCommand command,HttpServletRequest http) {
        return ResultUtils.success(transactions.replay(id,command,actor(http)));
    }
}
