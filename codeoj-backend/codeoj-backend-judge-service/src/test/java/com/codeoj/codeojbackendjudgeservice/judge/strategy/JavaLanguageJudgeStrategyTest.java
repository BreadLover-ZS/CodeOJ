package com.codeoj.codeojbackendjudgeservice.judge.strategy;

import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.codesandbox.JudgeInfo;
import com.codeoj.codeojbackendmodel.model.dto.question.JudgeCase;
import com.codeoj.codeojbackendmodel.model.entity.Question;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class JavaLanguageJudgeStrategyTest {
    private JudgeContext context(String expected, String actual) {
        JudgeInfo metrics = new JudgeInfo();
        metrics.setTime(100L);
        metrics.setMemory(1024L);
        Question question = new Question();
        question.setJudgeConfig("{\"timeLimit\":1000,\"memoryLimit\":131072}");
        JudgeCase testCase = new JudgeCase();
        testCase.setInput("");
        testCase.setOutput(expected);
        JudgeContext context = new JudgeContext();
        context.setJudgeInfo(metrics);
        context.setQuestion(question);
        context.setInputList(Collections.singletonList(""));
        context.setOutputList(Collections.singletonList(actual));
        context.setJudgeCaseList(Collections.singletonList(testCase));
        return context;
    }

    @Test void acceptsOnlyPermittedNewlineNormalization() {
        assertEquals(JudgeVerdict.AC, new JavaLanguageJudgeStrategy().doJudge(context("1\n2", "1\r\n2\r\n")).getVerdict());
        assertEquals(JudgeVerdict.WA, new JavaLanguageJudgeStrategy().doJudge(context("1", " 1\n")).getVerdict());
    }
    @Test void startupTimeIsNeverDiscountedByTenSeconds() {
        JudgeContext context = context("1", "1");
        context.getJudgeInfo().setTime(1001L);
        assertEquals(JudgeVerdict.TLE, new JavaLanguageJudgeStrategy().doJudge(context).getVerdict());
    }
    @Test void memoryLimitUsesKibibytes() {
        JudgeContext context = context("1", "1");
        context.getJudgeInfo().setMemory(131073L);
        assertEquals(JudgeVerdict.MLE, new JavaLanguageJudgeStrategy().doJudge(context).getVerdict());
    }
    @Test void missingMemoryCannotBecomeZeroOrAc() {
        JudgeContext context = context("1", "1");
        context.getJudgeInfo().setMemory(null);
        assertThrows(BusinessException.class, () -> new JavaLanguageJudgeStrategy().doJudge(context));
    }
    @Test void missingTimeCannotBecomeZeroOrAc() {
        JudgeContext context = context("1", "1");
        context.getJudgeInfo().setTime(null);
        assertThrows(BusinessException.class, () -> new JavaLanguageJudgeStrategy().doJudge(context));
    }
    @Test void missingOutputIsPlatformProtocolFailureRatherThanWa() {
        JudgeContext context = context("1", "1");
        context.setOutputList(Collections.emptyList());
        assertThrows(BusinessException.class, () -> new JavaLanguageJudgeStrategy().doJudge(context));
    }
}
