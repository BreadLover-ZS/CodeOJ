package com.codeoj.codeojbackendjudgeservice.judge;

import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SandboxResultMapperTest {
    @Test void compilerErrorIsCompletedCeRatherThanWa() {
        ExecuteCodeResponse response = ExecuteCodeResponse.builder().outcome(SandboxOutcome.CE).status(2).build();
        assertEquals(JudgeVerdict.CE, SandboxResultMapper.userVerdict(response).getVerdict());
    }
    @Test void trustedExecutionDeadlineIsTle() {
        ExecuteCodeResponse response = ExecuteCodeResponse.builder().outcome(SandboxOutcome.TLE).status(2).build();
        assertEquals(JudgeVerdict.TLE, SandboxResultMapper.userVerdict(response).getVerdict());
    }
    @Test void platformFailureHasNoUserVerdict() {
        ExecuteCodeResponse response = ExecuteCodeResponse.builder().outcome(SandboxOutcome.PLATFORM_ERROR).status(3).build();
        assertThrows(BusinessException.class, () -> SandboxResultMapper.userVerdict(response));
    }
    @Test void oldOrContradictoryProtocolIsNotImplicitSuccess() {
        assertThrows(BusinessException.class, () -> SandboxResultMapper.userVerdict(new ExecuteCodeResponse()));
        ExecuteCodeResponse response = ExecuteCodeResponse.builder().outcome(SandboxOutcome.CE).status(1).build();
        assertThrows(BusinessException.class, () -> SandboxResultMapper.userVerdict(response));
    }
    @Test void successfulExecutionStillNeedsAnswerAndResourceJudging() {
        ExecuteCodeResponse response = ExecuteCodeResponse.builder().outcome(SandboxOutcome.EXECUTED).status(1).build();
        assertNull(SandboxResultMapper.userVerdict(response));
    }
}
