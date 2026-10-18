package com.codeoj.codeojbackendjudgeservice.judge;

import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;

/** Validate protocol before comparing answers; platform errors have no user verdict. */
public final class SandboxResultMapper {
    private SandboxResultMapper() { }

    public static JudgeInfo userVerdict(ExecuteCodeResponse response) {
        if (response == null || response.getOutcome() == null) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "SANDBOX_PROTOCOL_ERROR");
        }
        SandboxOutcome outcome = response.getOutcome();
        int expectedStatus = outcome == SandboxOutcome.EXECUTED ? 1
                : outcome == SandboxOutcome.PLATFORM_ERROR || outcome == SandboxOutcome.REJECTED ? 3 : 2;
        if (!Integer.valueOf(expectedStatus).equals(response.getStatus())) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "SANDBOX_STATUS_MISMATCH");
        }
        if (outcome == SandboxOutcome.PLATFORM_ERROR || outcome == SandboxOutcome.REJECTED) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "SANDBOX_PLATFORM_FAILURE");
        }
        if (outcome == SandboxOutcome.EXECUTED) {
            return null; // Needs answer comparison and complete resource measurements.
        }
        JudgeVerdict verdict = JudgeVerdict.valueOf(outcome.name());
        JudgeInfo result = new JudgeInfo();
        if (response.getJudgeInfo() != null) {
            result.setTime(response.getJudgeInfo().getTime());
            result.setMemory(response.getJudgeInfo().getMemory());
            result.setMemoryMeasurement(response.getJudgeInfo().getMemoryMeasurement());
        }
        result.setVerdict(verdict);
        result.setMessage(verdict.getMessage());
        return result;
    }
}
