package com.codeoj.codeojbackendjudgeservice.judge;

import com.codeoj.codeojbackendjudgeservice.judge.strategy.DefaultJudgeStrategy;
import com.codeoj.codeojbackendjudgeservice.judge.strategy.JavaLanguageJudgeStrategy;
import com.codeoj.codeojbackendjudgeservice.judge.strategy.JudgeContext;
import com.codeoj.codeojbackendjudgeservice.judge.strategy.JudgeStrategy;
import com.codeoj.codeojbackendmodel.model.codesandbox.JudgeInfo;
import com.codeoj.codeojbackendmodel.model.entity.QuestionSubmit;
import org.springframework.stereotype.Service;

/**
 * 判题管理（简化调用）
 */
@Service
public class JudgeManager {

    /**
     * 执行判题
     *
     * @param judgeContext
     * @return
     */
    JudgeInfo doJudge(JudgeContext judgeContext) {
        QuestionSubmit questionSubmit = judgeContext.getQuestionSubmit();
        String language = questionSubmit.getLanguage();
        JudgeStrategy judgeStrategy = new DefaultJudgeStrategy();
        if ("java".equals(language)) {
            judgeStrategy = new JavaLanguageJudgeStrategy();
        }
        return judgeStrategy.doJudge(judgeContext);
    }

}
