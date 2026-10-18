package com.codeoj.codeojbackendjudgeservice.judge.strategy;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.codesandbox.JudgeInfo;
import com.codeoj.codeojbackendmodel.model.codesandbox.TextOutputComparator;
import com.codeoj.codeojbackendmodel.model.dto.question.JudgeCase;
import com.codeoj.codeojbackendmodel.model.dto.question.JudgeConfig;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;

import java.util.List;

/** Java v1: wall time includes startup; unknown resource metrics never imply AC. */
public class JavaLanguageJudgeStrategy implements JudgeStrategy {
    @Override
    public JudgeInfo doJudge(JudgeContext context) {
        JudgeInfo metrics = context.getJudgeInfo();
        if (metrics == null || metrics.getTime() == null || metrics.getMemory() == null
                || metrics.getTime() < 0 || metrics.getMemory() < 0) {
            throw platformError("RESOURCE_METRICS_UNAVAILABLE");
        }
        List<String> output = context.getOutputList();
        List<JudgeCase> cases = context.getJudgeCaseList();
        if (cases == null || cases.isEmpty() || output == null || output.size() != cases.size()) {
            throw platformError("SANDBOX_OUTPUT_COUNT_MISMATCH");
        }
        JudgeConfig limits = JSONUtil.toBean(context.getQuestion().getJudgeConfig(), JudgeConfig.class);
        if (limits == null || limits.getTimeLimit() == null || limits.getTimeLimit() <= 0
                || limits.getMemoryLimit() == null || limits.getMemoryLimit() <= 0) {
            throw platformError("INVALID_JUDGE_LIMITS");
        }
        JudgeInfo result = new JudgeInfo();
        result.setTime(metrics.getTime());
        result.setMemory(metrics.getMemory());
        result.setMemoryMeasurement(metrics.getMemoryMeasurement());
        if (metrics.getTime() > limits.getTimeLimit()) {
            return verdict(result, JudgeVerdict.TLE);
        }
        if (metrics.getMemory() > limits.getMemoryLimit()) {
            return verdict(result, JudgeVerdict.MLE);
        }
        for (int i = 0; i < cases.size(); i++) {
            if (cases.get(i) == null || cases.get(i).getOutput() == null || output.get(i) == null) {
                throw platformError("MISSING_JUDGE_OUTPUT");
            }
            if (!TextOutputComparator.matches(cases.get(i).getOutput(), output.get(i))) {
                return verdict(result, JudgeVerdict.WA);
            }
        }
        return verdict(result, JudgeVerdict.AC);
    }

    private JudgeInfo verdict(JudgeInfo info, JudgeVerdict verdict) {
        info.setVerdict(verdict);
        info.setMessage(verdict.getMessage());
        return info;
    }

    private BusinessException platformError(String errorCode) {
        return new BusinessException(ErrorCode.SYSTEM_ERROR, errorCode);
    }
}
