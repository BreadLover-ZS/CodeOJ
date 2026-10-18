package com.codeoj.codeojbackendquestionservice.submission;

import com.codeoj.codeojbackendmodel.model.codesandbox.JudgeInfo;
import com.codeoj.codeojbackendmodel.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.codeoj.codeojbackendmodel.model.entity.User;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;
import com.codeoj.codeojbackendmodel.model.reliability.*;
import com.codeoj.codeojbackendquestionservice.submission.outbox.OutboxStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Spring/JDBC transactions on H2 MySQL mode. Not MySQL or RabbitMQ acceptance. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=ReliableExecutionTest.Config.class)
@Timeout(15)
class ReliableExecutionTest {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:reliable-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000","sa",""); }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ExecutionRepository repository(JdbcTemplate jdbc) { return new ExecutionRepository(jdbc); }
        @Bean SubmissionAcceptance acceptance(ExecutionRepository r) { return new SubmissionAcceptance(r); }
        @Bean ReliableSubmissionService submissions(SubmissionAcceptance a,ExecutionRepository r) { return new ReliableSubmissionService(a,r); }
        @Bean ExecutionCommands commands(ExecutionRepository r) { return new ExecutionCommands(r); }
        @Bean OutboxStore outbox(JdbcTemplate jdbc,ExecutionRepository r) { return new OutboxStore(jdbc,r); }
    }
    public static class H2Clock {
        public static LocalDateTime utcTimestamp(int precision) { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    }
    @Autowired DataSource ds;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReliableSubmissionService submissions;
    @Autowired ExecutionCommands commands;
    @Autowired ExecutionRepository repository;
    @Autowired OutboxStore outbox;

    @BeforeEach void schema() throws Exception {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR '"+H2Clock.class.getName()+".utcTimestamp'");
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,userRole VARCHAR(32),isDelete INT DEFAULT 0)");
        jdbc.update("INSERT INTO `user`(id,userRole) VALUES(10,'user'),(11,'user'),(99,'admin')");
        jdbc.execute("CREATE TABLE question(id BIGINT PRIMARY KEY,judgeConfig TEXT,judgeCase TEXT,submitNum INT DEFAULT 0,acceptedNum INT DEFAULT 0,isDelete INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE question_submit(id BIGINT PRIMARY KEY,language VARCHAR(128),code TEXT,judgeInfo TEXT,status INT,questionId BIGINT,userId BIGINT,isDelete INT)");
        Path migration=Paths.get("..","mysql-init","migrations","V001__reliable_judging.sql");
        String sql=new String(Files.readAllBytes(migration),StandardCharsets.UTF_8)
                .replace(" CHARACTER SET ascii COLLATE ascii_bin","")
                .replace(" DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",""); // H2 cannot verify MySQL collations.
        try(Connection c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))); }
        jdbc.update("INSERT INTO question(id,judgeConfig,judgeCase) VALUES(1,?,?)",
                "{\"timeLimit\":1000,\"memoryLimit\":131072}","[{\"input\":\"1 2\",\"output\":\"3\"}]");
    }
    private User user(long id) { User u=new User();u.setId(id);u.setUserRole("user");return u; }
    private QuestionSubmitAddRequest request(String key) {
        QuestionSubmitAddRequest r=new QuestionSubmitAddRequest();r.setQuestionId(1L);r.setLanguage("java");r.setCode("public class Main {}");r.setRequestKey(key);return r;
    }
    private long accept() { return submissions.accept(request("stable-key-1"),user(10)); }
    private JudgeDispatch dispatch(long id) {
        ExecutionRecord r=repository.find(id,false);return repository.dispatch(id,r.getDispatchGeneration(),r.getDispatchEventId());
    }
    private ExecutionCommand command(JudgeLease lease) {
        ExecutionCommand c=new ExecutionCommand();c.setSubmissionId(lease.getSubmissionId());c.setAttemptNo(lease.getAttemptNo());c.setExecutionToken(lease.getExecutionToken());return c;
    }
    private ExecutionCommand completed(JudgeLease lease,JudgeVerdict verdict) {
        ExecutionCommand c=command(lease);JudgeInfo result=new JudgeInfo();result.setVerdict(verdict);result.setTime(100L);result.setMemory(1000L);c.setResult(result);return c;
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private void due(long id) { jdbc.update("UPDATE submission_execution SET nextRunAt=? WHERE submissionId=?",repository.now().minusSeconds(1),id); }
    private void expired(long id) { jdbc.update("UPDATE submission_execution SET leaseUntil=? WHERE submissionId=?",repository.now().minusSeconds(1),id); }

    @Test void stableKeyReplaysOriginalAndConflictingContentDoesNotCreateSubmission() {
        long id=accept();assertEquals(id,accept());
        QuestionSubmitAddRequest changed=request("stable-key-1");changed.setCode("different");
        assertThrows(SubmissionConflictException.class,()->submissions.accept(changed,user(10)));
        assertEquals(1,count("question_submit"));assertEquals(1,count("submission_execution"));assertEquals(1,count("reliable_outbox"));
        assertEquals(1,jdbc.queryForObject("SELECT submitNum FROM question WHERE id=1",Integer.class));
    }
    @Test void sameKeyDifferentUsersAreIndependent() {
        assertNotEquals(accept(),submissions.accept(request("stable-key-1"),user(11)));assertEquals(2,count("question_submit"));
    }
    @Test void outboxInsertFailureRollsBackSubmissionSnapshotAndAuthority() {
        jdbc.execute("ALTER TABLE reliable_outbox ADD CONSTRAINT reject_dispatch CHECK(eventType='NEVER')");
        assertThrows(RuntimeException.class,this::accept);
        assertEquals(0,count("question_submit"));assertEquals(0,count("submission_execution"));assertEquals(0,count("judge_spec"));
        assertEquals(0,jdbc.queryForObject("SELECT submitNum FROM question WHERE id=1",Integer.class));
    }
    @Test void snapshotIsFrozenBeforeQuestionEditAndReplayDoesNotNeedCurrentQuestion() {
        long id=accept();jdbc.update("UPDATE question SET judgeCase='[{\"input\":\"changed\",\"output\":\"changed\"}]' WHERE id=1");
        JudgeLease lease=commands.claim(dispatch(id));assertTrue(lease.isClaimed());assertEquals("3",lease.getCases().get(0).getOutput());
        jdbc.update("UPDATE question SET isDelete=1 WHERE id=1");assertEquals(id,accept());
    }
    @Test void simultaneousClaimsOnlyGrantOneLease() throws Exception {
        long id=accept();JudgeDispatch event=dispatch(id);ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<JudgeLease> claim=()->{start.await();return commands.claim(event);};
            Future<JudgeLease> a=pool.submit(claim),b=pool.submit(claim);start.countDown();
            int granted=(a.get(5,TimeUnit.SECONDS).isClaimed()?1:0)+(b.get(5,TimeUnit.SECONDS).isClaimed()?1:0);
            assertEquals(1,granted);assertEquals(1,count("judge_attempt"));
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentSameKeyCreatesOneFactAndEvent() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Long> submit=()->{start.await();return accept();};Future<Long> a=pool.submit(submit),b=pool.submit(submit);start.countDown();
            assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));assertEquals(1,count("question_submit"));assertEquals(1,count("reliable_outbox"));
        } finally { pool.shutdownNow(); }
    }
    @Test void completedReplayDoesNotRepeatScoreEventOrAcceptedStatistic() {
        long id=accept();JudgeLease lease=commands.claim(dispatch(id));ExecutionCommand c=completed(lease,JudgeVerdict.AC);
        assertTrue(commands.complete(c));assertTrue(commands.complete(c));
        assertEquals(1,repository.find(id,false).getResultVersion());
        assertEquals(1,jdbc.queryForObject("SELECT acceptedNum FROM question WHERE id=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM reliable_outbox WHERE eventType='JUDGE_RESULT'",Integer.class));
        c.setResult(completed(lease,JudgeVerdict.WA).getResult());assertFalse(commands.complete(c));
        assertFalse(commands.claim(dispatch(id)).isClaimed());
    }
    @Test void resultAndScoreEventAreOneTransaction() {
        long id=accept();JudgeLease lease=commands.claim(dispatch(id));
        jdbc.execute("ALTER TABLE reliable_outbox ADD CONSTRAINT no_result CHECK(eventType<>'JUDGE_RESULT')");
        assertThrows(RuntimeException.class,()->commands.complete(completed(lease,JudgeVerdict.AC)));
        assertEquals(ExecutionStatus.RUNNING,repository.find(id,false).getExecutionStatus());
        assertEquals(0,jdbc.queryForObject("SELECT acceptedNum FROM question WHERE id=1",Integer.class));
        assertEquals("RUNNING",jdbc.queryForObject("SELECT state FROM judge_attempt",String.class));
    }
    @Test void expiredLeaseRejectsHeartbeatAndResultThenRecoveryCreatesOnlyOneGeneration() {
        long id=accept();JudgeDispatch old=dispatch(id);JudgeLease first=commands.claim(old);expired(id);
        assertFalse(commands.heartbeat(command(first)));assertFalse(commands.complete(completed(first,JudgeVerdict.AC)));
        commands.recover(id);commands.recover(id);
        assertEquals(2,repository.find(id,false).getDispatchGeneration());assertEquals(2,count("reliable_outbox"));
        assertFalse(commands.claim(old).isClaimed());JudgeLease second=commands.claim(dispatch(id));assertTrue(second.isClaimed());
        assertNotEquals(first.getExecutionToken(),second.getExecutionToken());assertFalse(commands.fail(error(first)));
        assertTrue(commands.complete(completed(second,JudgeVerdict.WA)));
        assertFalse(commands.complete(completed(first,JudgeVerdict.AC)));
    }
    private ExecutionCommand error(JudgeLease lease) { ExecutionCommand c=command(lease);c.setErrorCode("DEPENDENCY_UNAVAILABLE");return c; }
    @Test void platformFailureUsesBackoffAndReplayDoesNotResetIt() {
        long id=accept();JudgeLease first=commands.claim(dispatch(id));assertTrue(commands.fail(error(first)));
        ExecutionRecord retry=repository.find(id,false);assertEquals(ExecutionStatus.RETRY_WAIT,retry.getExecutionStatus());
        assertTrue(commands.fail(error(first)));assertEquals(retry.getStateVersion(),repository.find(id,false).getStateVersion());
        commands.recover(id);assertEquals(1,repository.find(id,false).getDispatchGeneration());
        due(id);commands.recover(id);assertEquals(ExecutionStatus.QUEUED,repository.find(id,false).getExecutionStatus());
        assertEquals(2,repository.find(id,false).getDispatchGeneration());assertEquals(1,repository.find(id,false).getActiveAttempt());
    }
    @Test void retryExhaustionNeedsExplicitFiniteAdminExtensionAndKeepsAttemptHistory() {
        long id=accept();jdbc.update("UPDATE submission_execution SET maxAttempts=1 WHERE submissionId=?",id);
        JudgeLease lease=commands.claim(dispatch(id));commands.fail(error(lease));
        ExecutionRecord stopped=repository.find(id,false);assertEquals(ExecutionStatus.NEEDS_ATTENTION,stopped.getExecutionStatus());
        ManualRetryRequest retry=new ManualRetryRequest();retry.setExpectedVersion(stopped.getStateVersion());retry.setReason("infrastructure repaired");
        assertThrows(SubmissionConflictException.class,()->commands.manualRetry(id,retry,99));
        retry.setAdditionalAttempts(1);commands.manualRetry(id,retry,99);
        assertEquals(2,repository.find(id,false).getRecoveryPolicyVersion());assertEquals(1,repository.find(id,false).getActiveAttempt());
        JudgeLease resumed=commands.claim(dispatch(id));assertEquals(2,resumed.getAttemptNo());assertEquals(2,count("judge_attempt"));
        assertThrows(SubmissionConflictException.class,()->commands.manualRetry(id,retry,99));
    }
    @Test void expiredTotalDeadlineCannotBeResetByAutomaticRecovery() {
        long id=accept();jdbc.update("UPDATE submission_execution SET recoveryDeadlineAt=? WHERE submissionId=?",repository.now().minusSeconds(1),id);
        assertTrue(repository.recoverable().contains(id));commands.recover(id);
        assertEquals(ExecutionStatus.NEEDS_ATTENTION,repository.find(id,false).getExecutionStatus());assertFalse(commands.claim(dispatch(id)).isClaimed());
    }
    @Test void brokerConfirmDoesNotHideLongQueuedSubmission() {
        long id=accept();String event=dispatch(id).getEventId();OutboxStore.Publication publication=outbox.claim(event);
        outbox.record(event,publication.getToken(),"ACK",false,null);
        jdbc.update("UPDATE submission_execution SET queuedAt=? WHERE submissionId=?",repository.now().minusSeconds(31),id);
        assertTrue(repository.recoverable().contains(id));commands.recover(id);commands.recover(id);
        assertEquals(2,repository.find(id,false).getDispatchGeneration());assertEquals(2,count("reliable_outbox"));
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT publishState FROM reliable_outbox WHERE eventId=?",String.class,event));
    }
    @Test void outboxUnknownEvidenceSurvivesLaterNackAndOldAckCanConverge() {
        String event=dispatch(accept()).getEventId();OutboxStore.Publication first=outbox.claim(event);assertNull(outbox.claim(event));
        jdbc.update("UPDATE reliable_outbox SET leaseUntil=?,nextRetryAt=? WHERE eventId=?",repository.now().minusSeconds(1),repository.now().minusSeconds(1),event);
        OutboxStore.Publication second=outbox.claim(event);assertNotNull(second);
        outbox.record(event,second.getToken(),"NACK",false,"BROKER_NACK");
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT confirmState FROM publish_attempt WHERE attemptToken=?",String.class,first.getToken()));
        outbox.record(event,first.getToken(),"ACK",false,null);
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT publishState FROM reliable_outbox WHERE eventId=?",String.class,event));
        outbox.record(event,second.getToken(),"UNKNOWN",false,"LATE_FAILURE");
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT publishState FROM reliable_outbox WHERE eventId=?",String.class,event));
    }
    @Test void returnedAckIsNotRoutableSuccessAndCannotBeOverwrittenByDuplicateCallback() {
        String event=dispatch(accept()).getEventId();OutboxStore.Publication p=outbox.claim(event);
        outbox.record(event,p.getToken(),"ACK",true,"UNROUTABLE");outbox.record(event,p.getToken(),"ACK",false,null);
        assertEquals("PENDING",jdbc.queryForObject("SELECT publishState FROM reliable_outbox WHERE eventId=?",String.class,event));
        assertTrue(jdbc.queryForObject("SELECT returned FROM publish_attempt WHERE eventId=?",Boolean.class,event));
    }
    @Test void publisherBudgetEndsInVisibleAttention() {
        String event=dispatch(accept()).getEventId();jdbc.update("UPDATE reliable_outbox SET attempts=20 WHERE eventId=?",event);
        assertNull(outbox.claim(event));assertEquals("ATTENTION",jdbc.queryForObject("SELECT publishState FROM reliable_outbox WHERE eventId=?",String.class,event));
    }
    @Test void validHeartbeatExtendsOnlyItsOwnLeaseAndCannotChangeResult() {
        long id=accept();JudgeLease lease=commands.claim(dispatch(id));assertTrue(commands.heartbeat(command(lease)));
        ExecutionCommand wrong=command(lease);wrong.setExecutionToken(UUID.randomUUID().toString());assertFalse(commands.heartbeat(wrong));
        assertEquals(ExecutionStatus.RUNNING,repository.find(id,false).getExecutionStatus());
    }
    @Test void badResultCannotBecomeAc() {
        long id=accept();JudgeLease lease=commands.claim(dispatch(id));ExecutionCommand c=completed(lease,JudgeVerdict.AC);c.getResult().setMemory(null);
        assertThrows(RuntimeException.class,()->commands.complete(c));assertEquals(ExecutionStatus.RUNNING,repository.find(id,false).getExecutionStatus());
    }
    @Test void payloadHasNoSourceOrPrivateInputs() {
        long id=accept();String payload=jdbc.queryForObject("SELECT payload FROM reliable_outbox WHERE aggregateId=?",String.class,id);
        assertFalse(payload.contains("public class"));assertFalse(payload.contains("1 2"));assertTrue(dispatch(id).valid());
    }
    @Test void capacityDeferralDoesNotClaimAttemptAndDispatchBudgetIsFinite() {
        long id=accept();JudgeDispatch original=dispatch(id);commands.defer(original);
        assertEquals(0,count("judge_attempt"));due(id);commands.recover(id);assertFalse(commands.claim(original).isClaimed());
        jdbc.update("UPDATE submission_execution SET maxDispatches=dispatchGeneration,queuedAt=? WHERE submissionId=?",repository.now().minusSeconds(31),id);
        commands.recover(id);assertEquals(ExecutionStatus.NEEDS_ATTENTION,repository.find(id,false).getExecutionStatus());
    }
    @Test void exhaustedDeadlineNeedsExplicitAuditedExtension() {
        long id=accept();LocalDateTime accepted=repository.find(id,false).getAcceptedAt();
        jdbc.update("UPDATE submission_execution SET recoveryDeadlineAt=? WHERE submissionId=?",repository.now().minusSeconds(1),id);
        commands.recover(id);ManualRetryRequest retry=new ManualRetryRequest();
        retry.setExpectedVersion(repository.find(id,false).getStateVersion());retry.setReason("database repaired");
        assertThrows(SubmissionConflictException.class,()->commands.manualRetry(id,retry,99));
        retry.setExtendRecoverySeconds(60);commands.manualRetry(id,retry,99);
        ExecutionRecord resumed=repository.find(id,false);assertEquals(ExecutionStatus.QUEUED,resumed.getExecutionStatus());
        assertEquals(accepted,resumed.getAcceptedAt());assertEquals(2,resumed.getRecoveryPolicyVersion());
    }
    @Test void exhaustedDispatchBudgetCannotBeResetByOrdinaryReplay() {
        long id=accept();jdbc.update("UPDATE submission_execution SET maxDispatches=1,queuedAt=? WHERE submissionId=?",repository.now().minusSeconds(31),id);
        commands.recover(id);ManualRetryRequest retry=new ManualRetryRequest();retry.setExpectedVersion(repository.find(id,false).getStateVersion());retry.setReason("publisher repaired");
        assertThrows(SubmissionConflictException.class,()->commands.manualRetry(id,retry,99));
        retry.setAdditionalDispatches(1);commands.manualRetry(id,retry,99);
        assertEquals(2,repository.find(id,false).getDispatchGeneration());assertEquals(2,repository.find(id,false).getMaxDispatches());
    }
    @Test void adminDiagnosticProjectionDoesNotExposeCodeCasesOrExecutionTokens() {
        long id=accept();JudgeLease lease=commands.claim(dispatch(id));commands.fail(error(lease));
        com.codeoj.codeojbackendserviceclient.service.UserFeignClient users=org.mockito.Mockito.mock(com.codeoj.codeojbackendserviceclient.service.UserFeignClient.class);
        org.springframework.mock.web.MockHttpServletRequest http=new org.springframework.mock.web.MockHttpServletRequest();
        User admin=user(99);admin.setUserRole("admin");org.mockito.Mockito.when(users.getLoginUser(http)).thenReturn(admin);
        ExecutionAdminController controller=new ExecutionAdminController(repository,commands,users);
        String json=cn.hutool.json.JSONUtil.toJsonStr(controller.detail(id,0,http).getData());
        assertFalse(json.contains(lease.getExecutionToken()));assertFalse(json.contains("public class"));assertFalse(json.contains("1 2"));
        assertFalse(json.contains("judgeSpecHash"));assertTrue(json.contains("DEPENDENCY_UNAVAILABLE"));
    }
    @Test void staleSessionCannotSubmitAfterAccountBan() {
        jdbc.update("UPDATE `user` SET userRole='ban' WHERE id=10");
        assertThrows(com.codeoj.codeojbackendcommon.exception.BusinessException.class,this::accept);
        assertEquals(0,count("question_submit"));
    }
    @Test void staleAdminSessionCannotExtendExecutionBudget() {
        long id=accept();jdbc.update("UPDATE submission_execution SET recoveryDeadlineAt=? WHERE submissionId=?",repository.now().minusSeconds(1),id);commands.recover(id);
        ManualRetryRequest retry=new ManualRetryRequest();retry.setExpectedVersion(repository.find(id,false).getStateVersion());retry.setReason("repair");retry.setExtendRecoverySeconds(60);
        jdbc.update("UPDATE `user` SET userRole='user' WHERE id=99");
        assertThrows(com.codeoj.codeojbackendcommon.exception.BusinessException.class,()->commands.manualRetry(id,retry,99));
        assertEquals(ExecutionStatus.NEEDS_ATTENTION,repository.find(id,false).getExecutionStatus());
    }
}
