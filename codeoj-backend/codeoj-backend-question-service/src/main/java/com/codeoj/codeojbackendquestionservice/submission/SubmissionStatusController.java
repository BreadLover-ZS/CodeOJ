package com.codeoj.codeojbackendquestionservice.submission;

import com.codeoj.codeojbackendcommon.common.*;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.entity.*;
import com.codeoj.codeojbackendmodel.model.vo.QuestionSubmitVO;
import com.codeoj.codeojbackendquestionservice.service.QuestionSubmitService;
import com.codeoj.codeojbackendserviceclient.service.UserFeignClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/submissions")
@ConditionalOnProperty(name="codeoj.reliable-judge.enabled",havingValue="true")
public class SubmissionStatusController {
    private final QuestionSubmitService submissions;
    private final UserFeignClient users;
    private final ExecutionRepository repository;
    public SubmissionStatusController(QuestionSubmitService submissions,UserFeignClient users,ExecutionRepository repository) {
        this.submissions=submissions;this.users=users;this.repository=repository;
    }
    @GetMapping("/{id}")
    public BaseResponse<QuestionSubmitVO> get(@PathVariable long id,HttpServletRequest request) {
        User user=users.getLoginUser(request);
        QuestionSubmit submission=submissions.getById(id);
        if(submission==null) { throw new BusinessException(ErrorCode.NOT_FOUND_ERROR); }
        if(!user.getId().equals(submission.getUserId()) && !"admin".equals(user.getUserRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR);
        }
        if(!user.getId().equals(submission.getUserId())) { repository.requireAccount(user.getId(),"admin",false); }
        return ResultUtils.success(submissions.getQuestionSubmitVO(submission,user));
    }
}
