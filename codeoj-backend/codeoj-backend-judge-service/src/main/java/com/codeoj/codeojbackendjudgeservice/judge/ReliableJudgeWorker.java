package com.codeoj.codeojbackendjudgeservice.judge;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendjudgeservice.judge.codesandbox.*;
import com.codeoj.codeojbackendjudgeservice.judge.strategy.JudgeContext;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import com.codeoj.codeojbackendmodel.model.entity.Question;
import com.codeoj.codeojbackendmodel.model.reliability.*;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendserviceclient.service.QuestionFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
@Slf4j
@ConditionalOnProperty(name="codeoj.reliable-judge.enabled",havingValue="true")
public class ReliableJudgeWorker {
    private final QuestionFeignClient questions;
    private final CodeSandboxFactory factory;
    private final JudgeManager manager;
    private final Semaphore slots;
    private final ScheduledThreadPoolExecutor heartbeats;
    @Value("${codesandbox.type:remote}") private String sandboxType="remote";

    public ReliableJudgeWorker(QuestionFeignClient questions,CodeSandboxFactory factory,JudgeManager manager,
                               @Value("${codeoj.reliable-judge.worker-slots:2}") int capacity) {
        if(capacity<1 || capacity>16) { throw new IllegalArgumentException("INVALID_WORKER_CAPACITY"); }
        this.questions=questions;this.factory=factory;this.manager=manager;this.slots=new Semaphore(capacity);
        this.heartbeats=new ScheduledThreadPoolExecutor(capacity,task->{Thread t=new Thread(task,"judge-lease-heartbeat");t.setDaemon(true);return t;});
        heartbeats.setRemoveOnCancelPolicy(true);
    }

    public void process(JudgeDispatch message) throws InterruptedException {
        if(!slots.tryAcquire(200,TimeUnit.MILLISECONDS)) {
            if(!questions.deferExecution(message)) { throw new IllegalStateException("DEFERRAL_RESULT_UNKNOWN"); }
            return;
        }
        ScheduledFuture<?> heartbeat=null;
        try {
            JudgeLease lease=questions.claimExecution(message);
            if(lease==null || lease.getClaimed()==null) { throw new IllegalStateException("CLAIM_RESPONSE_UNKNOWN"); }
            if(!lease.isClaimed()) { return; } // Terminal, stale generation, not due or another valid owner.
            AtomicBoolean lost=new AtomicBoolean();
            heartbeat=heartbeats.scheduleWithFixedDelay(()->{
                try { if(!questions.heartbeatExecution(command(lease))) { lost.set(true); } }
                catch(RuntimeException e) { log.warn("Judge heartbeat unavailable: submissionId={}",lease.getSubmissionId()); }
            },5,5,TimeUnit.SECONDS);
            String platformError=null;
            try {
                ExecuteCodeRequest request=ExecuteCodeRequest.builder().language(lease.getLanguage()).code(lease.getCode())
                        .inputList(lease.getCases().stream().map(c->c.getInput()).collect(Collectors.toList()))
                        .runTimeLimitMs(lease.getLimits().getTimeLimit()).memoryLimitKiB(lease.getLimits().getMemoryLimit())
                        .executionToken(lease.getExecutionToken()).executionDeadlineEpochMs(lease.getDeadlineEpochMs()).build();
                CodeSandbox sandbox=new CodeSandboxProxy(factory.newInstance(sandboxType));
                ExecuteCodeResponse response=sandbox.executeCode(request);
                if(response!=null && response.getErrorCode()!=null && response.getErrorCode().matches("[A-Z0-9_]{1,128}")) {
                    platformError=response.getErrorCode();
                }
                JudgeInfo result=SandboxResultMapper.userVerdict(response);
                if(result==null) {
                    Question snapshot=new Question();snapshot.setJudgeConfig(JSONUtil.toJsonStr(lease.getLimits()));
                    com.codeoj.codeojbackendmodel.model.entity.QuestionSubmit submission=new com.codeoj.codeojbackendmodel.model.entity.QuestionSubmit();
                    submission.setLanguage(lease.getLanguage());
                    JudgeContext context=new JudgeContext();context.setQuestion(snapshot);context.setQuestionSubmit(submission);
                    context.setInputList(request.getInputList());context.setJudgeCaseList(lease.getCases());
                    context.setOutputList(response.getOutputList());context.setJudgeInfo(response.getJudgeInfo());
                    result=manager.doJudge(context);
                }
                if(!lost.get()) {
                    ExecutionCommand complete=command(lease);complete.setResult(result);
                    platformError="RESULT_PERSISTENCE_UNAVAILABLE";
                    questions.completeExecution(complete); // DB rejects expired tokens, including late remote results.
                }
            } catch(RuntimeException e) {
                if(!lost.get()) {
                    if(platformError==null && e instanceof BusinessException && e.getMessage()!=null
                            && e.getMessage().matches("[A-Z0-9_]{1,128}")) { platformError=e.getMessage(); }
                    ExecutionCommand failed=command(lease);
                    failed.setErrorCode(platformError==null?"SANDBOX_OR_RESULT_UNAVAILABLE":platformError);
                    questions.failExecution(failed); // If this cannot persist, exception reaches consumer; no ACK.
                }
            }
        } finally {
            if(heartbeat!=null) { heartbeat.cancel(true); }
            slots.release();
        }
    }

    private ExecutionCommand command(JudgeLease lease) {
        ExecutionCommand command=new ExecutionCommand();command.setSubmissionId(lease.getSubmissionId());
        command.setAttemptNo(lease.getAttemptNo());command.setExecutionToken(lease.getExecutionToken());return command;
    }
    @PreDestroy public void close() { heartbeats.shutdownNow(); }
}
