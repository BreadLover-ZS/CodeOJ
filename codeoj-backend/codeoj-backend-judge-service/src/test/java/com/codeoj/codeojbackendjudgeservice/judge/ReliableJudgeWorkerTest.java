package com.codeoj.codeojbackendjudgeservice.judge;

import com.codeoj.codeojbackendjudgeservice.judge.codesandbox.*;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import com.codeoj.codeojbackendmodel.model.dto.question.*;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;
import com.codeoj.codeojbackendmodel.model.reliability.*;
import com.codeoj.codeojbackendserviceclient.service.QuestionFeignClient;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class ReliableJudgeWorkerTest {
    private QuestionFeignClient client;
    private CodeSandbox sandbox;
    private ReliableJudgeWorker worker;
    private JudgeDispatch event;
    private JudgeLease lease;
    @BeforeEach void setup() {
        client=mock(QuestionFeignClient.class);sandbox=mock(CodeSandbox.class);
        CodeSandboxFactory factory=mock(CodeSandboxFactory.class);when(factory.newInstance("remote")).thenReturn(sandbox);
        worker=new ReliableJudgeWorker(client,factory,new JudgeManager(),1);
        event=new JudgeDispatch();event.setSubmissionId(1L);event.setEventId(UUID.randomUUID().toString());
        event.setDispatchGeneration(1);event.setPayloadVersion(1);event.setTraceId(event.getEventId());
        lease=new JudgeLease();lease.setClaimed(true);lease.setSubmissionId(1L);lease.setAttemptNo(1);
        lease.setExecutionToken(UUID.randomUUID().toString());lease.setDeadlineEpochMs(System.currentTimeMillis()+60000);
        lease.setLanguage("java");lease.setCode("private-source");JudgeCase c=new JudgeCase();c.setInput("private-input");c.setOutput("private-output");
        lease.setCases(Collections.singletonList(c));JudgeConfig limits=new JudgeConfig();limits.setTimeLimit(1000L);limits.setMemoryLimit(131072L);lease.setLimits(limits);
        when(client.claimExecution(event)).thenReturn(lease);
        when(client.deferExecution(event)).thenReturn(true);
    }
    @AfterEach void close() { worker.close(); }
    @Test void staleOrCompletedMessageDoesNotExecuteOrFail() throws Exception {
        JudgeLease skipped=new JudgeLease();skipped.setClaimed(false);
        when(client.claimExecution(event)).thenReturn(skipped);worker.process(event);
        verifyNoInteractions(sandbox);verify(client,never()).failExecution(any());verify(client,never()).completeExecution(any());
    }
    @Test void ceUsesFrozenClaimAndFencedCompletion() throws Exception {
        when(sandbox.executeCode(any())).thenReturn(ExecuteCodeResponse.builder().outcome(SandboxOutcome.CE).status(2).build());
        worker.process(event);
        ArgumentCaptor<ExecuteCodeRequest> request=ArgumentCaptor.forClass(ExecuteCodeRequest.class);verify(sandbox).executeCode(request.capture());
        assertEquals(lease.getExecutionToken(),request.getValue().getExecutionToken());assertEquals(lease.getDeadlineEpochMs(),request.getValue().getExecutionDeadlineEpochMs());
        ArgumentCaptor<ExecutionCommand> complete=ArgumentCaptor.forClass(ExecutionCommand.class);verify(client).completeExecution(complete.capture());
        assertEquals(JudgeVerdict.CE,complete.getValue().getResult().getVerdict());assertEquals(lease.getExecutionToken(),complete.getValue().getExecutionToken());
        verify(client,never()).getQuestionById(anyLong());verify(client,never()).updateQuestionSubmitById(any());
    }
    @Test void missingClaimDecisionIsUnknownAndCannotBeAcknowledged() {
        when(client.claimExecution(event)).thenReturn(new JudgeLease());
        assertThrows(IllegalStateException.class,()->worker.process(event));
        verifyNoInteractions(sandbox);
    }
    @Test void platformFailurePersistsRetryBeforeReturning() throws Exception {
        when(sandbox.executeCode(any())).thenReturn(ExecuteCodeResponse.builder().outcome(SandboxOutcome.PLATFORM_ERROR).status(3).build());
        worker.process(event);verify(client).failExecution(any());verify(client,never()).completeExecution(any());
    }
    @Test void attributedUserReCompletesOnceAndNeverSchedulesPlatformRetry() throws Exception {
        when(sandbox.executeCode(any())).thenReturn(ExecuteCodeResponse.builder().outcome(SandboxOutcome.RE)
                .status(2).errorCode("USER_MAIN_EXCEPTION").build());
        worker.process(event);
        ArgumentCaptor<ExecutionCommand> complete=ArgumentCaptor.forClass(ExecutionCommand.class);
        verify(client,times(1)).completeExecution(complete.capture());
        assertEquals(JudgeVerdict.RE,complete.getValue().getResult().getVerdict());
        assertEquals(lease.getExecutionToken(),complete.getValue().getExecutionToken());
        verify(client,never()).failExecution(any());
    }
    @Test void cannotAcknowledgeWhenPlatformFailureCouldNotBePersisted() {
        when(sandbox.executeCode(any())).thenThrow(new IllegalStateException("transport unavailable"));
        when(client.failExecution(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,()->worker.process(event));
    }
    @Test void capacityIsReservedBeforeClaimAndNoSlotUsesDurableDeferral() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        when(sandbox.executeCode(any())).thenAnswer(invocation->{entered.countDown();release.await();return ExecuteCodeResponse.builder().outcome(SandboxOutcome.CE).status(2).build();});
        try {
            Future<?> first=pool.submit(()->{try { worker.process(event); } catch(InterruptedException e) { throw new RuntimeException(e); }});
            assertTrue(entered.await(2,TimeUnit.SECONDS));worker.process(event);
            verify(client,times(1)).claimExecution(event);verify(client).deferExecution(event);
            release.countDown();first.get(2,TimeUnit.SECONDS);
        } finally { release.countDown();pool.shutdownNow(); }
    }
}
