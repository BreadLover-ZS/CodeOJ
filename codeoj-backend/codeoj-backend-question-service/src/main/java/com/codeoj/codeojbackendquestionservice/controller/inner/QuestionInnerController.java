package com.codeoj.codeojbackendquestionservice.controller.inner;

import com.codeoj.codeojbackendmodel.model.entity.Question;
import com.codeoj.codeojbackendmodel.model.entity.QuestionSubmit;
import com.codeoj.codeojbackendquestionservice.service.QuestionService;
import com.codeoj.codeojbackendquestionservice.service.QuestionSubmitService;
import com.codeoj.codeojbackendserviceclient.service.QuestionFeignClient;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 该服务仅内部调用，不是给前端的
 */
@RestController
@RequestMapping("/inner")
public class QuestionInnerController implements QuestionFeignClient {

    @Resource
    private QuestionService questionService;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @GetMapping("/get/id")
    @Override
    public Question getQuestionById(@RequestParam("questionId") long questionId) {
        return questionService.getById(questionId);
    }

    @GetMapping("/question_submit/get/id")
    @Override
    public QuestionSubmit getQuestionSubmitById(@RequestParam("questionId") long questionSubmitId) {
        return questionSubmitService.getById(questionSubmitId);
    }

    @PostMapping("/question_submit/update")
    @Override
    public boolean updateQuestionSubmitById(@RequestBody QuestionSubmit questionSubmit) {
        return questionSubmitService.updateById(questionSubmit);
    }

    @PostMapping("/update/stats")
    @Override
    public boolean updateQuestionStats(@RequestParam("questionId") long questionId,
                                       @RequestParam("submitNumDelta") int submitNumDelta,
                                       @RequestParam("acceptedNumDelta") int acceptedNumDelta) {
        if (submitNumDelta == 0 && acceptedNumDelta == 0) {
            return true;
        }
        // 增量更新提交数/通过数，MySQL IF 避免统计被减为负数
        UpdateWrapper<Question> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", questionId);
        if (submitNumDelta != 0) {
            updateWrapper.setSql("submit_num = IF(submit_num + " + submitNumDelta + " < 0, 0, submit_num + " + submitNumDelta + ")");
        }
        if (acceptedNumDelta != 0) {
            updateWrapper.setSql("accepted_num = IF(accepted_num + " + acceptedNumDelta + " < 0, 0, accepted_num + " + acceptedNumDelta + ")");
        }
        return questionService.update(updateWrapper);
    }

}
