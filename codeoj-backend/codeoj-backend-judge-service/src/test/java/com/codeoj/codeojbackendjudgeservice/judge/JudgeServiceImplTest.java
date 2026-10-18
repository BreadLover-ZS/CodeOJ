package com.codeoj.codeojbackendjudgeservice.judge;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendjudgeservice.judge.codesandbox.*;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import com.codeoj.codeojbackendmodel.model.entity.*;
import com.codeoj.codeojbackendmodel.model.enums.*;
import com.codeoj.codeojbackendserviceclient.service.QuestionFeignClient;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service contract with Feign/sandbox doubles; not database or broker acceptance. */
class JudgeServiceImplTest {
    private QuestionFeignClient client;
    private CodeSandbox sandbox;
    private JudgeServiceImpl service;

    @BeforeEach void setup() {
        client = mock(QuestionFeignClient.class);
        sandbox = mock(CodeSandbox.class);
        CodeSandboxFactory factory = mock(CodeSandboxFactory.class);
        when(factory.newInstance("remote")).thenReturn(sandbox);
        service = new JudgeServiceImpl();
        ReflectionTestUtils.setField(service, "questionFeignClient", client);
        ReflectionTestUtils.setField(service, "codeSandboxFactory", factory);
        ReflectionTestUtils.setField(service, "judgeManager", new JudgeManager());
        ReflectionTestUtils.setField(service, "type", "remote");
        QuestionSubmit submission = new QuestionSubmit();
        submission.setId(1L);
        submission.setQuestionId(2L);
        submission.setLanguage("java");
        submission.setCode("private-test-source");
        submission.setStatus(QuestionSubmitStatusEnum.WAITING.getValue());
        Question question = new Question();
        question.setId(2L);
        question.setJudgeCase("[{\"input\":\"private-input\",\"output\":\"private-output\"}]");
        question.setJudgeConfig("{\"timeLimit\":1000,\"memoryLimit\":131072}");
        when(client.getQuestionSubmitById(1L)).thenReturn(submission);
        when(client.getQuestionById(2L)).thenReturn(question);
        when(client.updateQuestionSubmitById(any())).thenReturn(true);
    }

    @Test void ceCompletesAsUserVerdictInsteadOfWa() {
        when(sandbox.executeCode(any())).thenReturn(ExecuteCodeResponse.builder()
                .outcome(SandboxOutcome.CE).status(2).build());
        service.doJudge(1L);
        ArgumentCaptor<QuestionSubmit> writes = ArgumentCaptor.forClass(QuestionSubmit.class);
        verify(client, times(2)).updateQuestionSubmitById(writes.capture());
        List<QuestionSubmit> records = writes.getAllValues();
        assertEquals(QuestionSubmitStatusEnum.RUNNING.getValue(), records.get(0).getStatus());
        assertEquals(QuestionSubmitStatusEnum.SUCCEED.getValue(), records.get(1).getStatus());
        assertEquals(JudgeVerdict.CE, JSONUtil.toBean(records.get(1).getJudgeInfo(), JudgeInfo.class).getVerdict());
        verify(client, never()).updateQuestionStats(anyLong(), anyInt(), anyInt());
    }

    @Test void platformFailureDoesNotWriteCompletedVerdict() {
        when(sandbox.executeCode(any())).thenReturn(ExecuteCodeResponse.builder()
                .outcome(SandboxOutcome.PLATFORM_ERROR).status(3).build());
        assertThrows(BusinessException.class, () -> service.doJudge(1L));
        verify(client, times(1)).updateQuestionSubmitById(any());
    }

    @Test void unknownMemoryDoesNotWriteAcOrIncrementAcceptedCount() {
        JudgeInfo metrics = new JudgeInfo();
        metrics.setTime(100L);
        when(sandbox.executeCode(any())).thenReturn(ExecuteCodeResponse.builder()
                .outcome(SandboxOutcome.EXECUTED).status(1).judgeInfo(metrics)
                .outputList(java.util.Collections.singletonList("private-output")).build());
        assertThrows(BusinessException.class, () -> service.doJudge(1L));
        verify(client, times(1)).updateQuestionSubmitById(any());
        verify(client, never()).updateQuestionStats(anyLong(), anyInt(), anyInt());
    }
}
