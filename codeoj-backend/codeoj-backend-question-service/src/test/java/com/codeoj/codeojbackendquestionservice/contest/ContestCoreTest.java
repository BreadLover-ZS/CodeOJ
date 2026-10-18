package com.codeoj.codeojbackendquestionservice.contest;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.codesandbox.JudgeInfo;
import com.codeoj.codeojbackendmodel.model.dto.question.*;
import com.codeoj.codeojbackendmodel.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.codeoj.codeojbackendmodel.model.entity.User;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;
import com.codeoj.codeojbackendmodel.model.reliability.*;
import com.codeoj.codeojbackendquestionservice.submission.*;
import com.codeoj.codeojbackendserviceclient.service.UserFeignClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Spring/JDBC transactions on H2, deterministic database UTC; no MySQL/MQ acceptance claim. */
@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=ContestCoreTest.Config.class) @Timeout(20)
class ContestCoreTest {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource ds() { return new DriverManagerDataSource("jdbc:h2:mem:contest-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000","sa",""); }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ExecutionRepository repo(JdbcTemplate j) { return new ExecutionRepository(j); }
        @Bean SubmissionAcceptance acceptance(ExecutionRepository r) { return new SubmissionAcceptance(r); }
        @Bean ReliableSubmissionService practice(SubmissionAcceptance a,ExecutionRepository r) { return new ReliableSubmissionService(a,r); }
        @Bean ExecutionCommands commands(ExecutionRepository r) { return new ExecutionCommands(r); }
        @Bean ContestTransactions contests(JdbcTemplate j,ExecutionRepository r,SubmissionAcceptance a) { return new ContestTransactions(j,r,a); }
        @Bean ContestService facade(ContestTransactions t,ExecutionRepository r) { return new ContestService(t,r); }
    }
    public static class Clock {
        static volatile LocalDateTime now;
        public static LocalDateTime utcTimestamp(int precision) { return now; }
    }
    @Autowired DataSource ds; @Autowired JdbcTemplate jdbc; @Autowired ContestTransactions contests;
    @Autowired ContestService facade; @Autowired ExecutionRepository repo; @Autowired ExecutionCommands commands;
    @Autowired ReliableSubmissionService practice;
    private static final LocalDateTime BASE=LocalDateTime.of(2026,10,20,0,0);
    private final String KEY="stable-registration-key";
    @BeforeEach void schema() throws Exception {
        Clock.now=BASE;jdbc.execute("DROP ALL OBJECTS");jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR '"+Clock.class.getName()+".utcTimestamp'");
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,userRole VARCHAR(32),isDelete INT DEFAULT 0)");
        for(long id=10;id<20;id++) { jdbc.update("INSERT INTO `user` VALUES(?,'user',0)",id); }jdbc.update("INSERT INTO `user` VALUES(99,'admin',0)");
        jdbc.execute("CREATE TABLE question(id BIGINT PRIMARY KEY,title VARCHAR(120),content TEXT,judgeConfig TEXT,judgeCase TEXT,submitNum INT DEFAULT 0,acceptedNum INT DEFAULT 0,isDelete INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE question_submit(id BIGINT PRIMARY KEY,language VARCHAR(128),code TEXT,judgeInfo TEXT,status INT,questionId BIGINT,userId BIGINT,isDelete INT)");
        for(String name:Arrays.asList("V001__reliable_judging.sql","V002__contest_core.sql")) {
            String sql=new String(Files.readAllBytes(Paths.get("..","mysql-init","migrations",name)),StandardCharsets.UTF_8)
                    .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","");
            try(Connection c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))); }
        }
        jdbc.update("INSERT INTO question(id,title,content,judgeConfig,judgeCase) VALUES(1,'Public sum','Already public',?,?)",
                "{\"timeLimit\":1000,\"memoryLimit\":131072}","[{\"input\":\"1 2\",\"output\":\"3\"}]");
    }
    private String time(int minutes) { return BASE.plusMinutes(minutes).toInstant(ZoneOffset.UTC).toString(); }
    private ContestDraft draft() {
        ContestDraft d=new ContestDraft();d.setTitle("Contest");d.setContestType("PRIVATE");d.setCapacity(2);
        d.setRegistrationOpenAt(time(1));d.setRegistrationCloseAt(time(2));d.setStartAt(time(3));d.setEndAt(time(4));
        ContestDraft.Problem p=new ContestDraft.Problem();p.setProblemCode("A");p.setTitle("Secret sum");p.setContent("Secret statement");
        JudgeConfig limits=new JudgeConfig();limits.setTimeLimit(1000L);limits.setMemoryLimit(131072L);p.setLimits(limits);
        JudgeCase sample=new JudgeCase();sample.setInput("private-input");sample.setOutput("private-answer");p.setCases(Collections.singletonList(sample));
        d.setProblems(Collections.singletonList(p));return d;
    }
    private long create() { return Long.parseLong((String)contests.create(draft(),99).get("id")); }
    private ContestCommand command(long version) { ContestCommand c=new ContestCommand();c.setExpectedVersion(version);c.setReason("local functional test");return c; }
    private long published() { long id=create();contests.publish(id,command(0),99);return id; }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private long snapshot(long id) { return jdbc.queryForObject("SELECT id FROM contest_problem_snapshot WHERE contestId=?",Long.class,id); }
    private ContestSubmission input(long id) {
        ContestSubmission r=new ContestSubmission();r.setProblemSnapshotId(snapshot(id));r.setPublishedVersion(1L);r.setLanguage("java");r.setCode("public class Main {}");return r;
    }
    private void register(long id,long user) { Clock.now=BASE.plusMinutes(1);contests.register(id,KEY,user); }
    private long submit(long id) { return facade.submit(id,input(id),"stable-submit-key",10); }
    private JudgeLease claim(long id) { ExecutionRecord r=repo.find(id,false);return commands.claim(repo.dispatch(id,r.getDispatchGeneration(),r.getDispatchEventId())); }
    private void complete(JudgeLease lease) {
        ExecutionCommand c=new ExecutionCommand();c.setSubmissionId(lease.getSubmissionId());c.setAttemptNo(lease.getAttemptNo());c.setExecutionToken(lease.getExecutionToken());
        JudgeInfo result=new JudgeInfo();result.setVerdict(JudgeVerdict.AC);result.setTime(1L);result.setMemory(1L);c.setResult(result);assertTrue(commands.complete(c));
    }
    @Test void invalidTimesCapacityAndDuplicateCodesNeverCreateContest() {
        ContestDraft d=draft();d.setEndAt(time(3));assertThrows(BusinessException.class,()->contests.create(d,99));
        d.setEndAt(time(4));d.setCapacity(0);assertThrows(BusinessException.class,()->contests.create(d,99));
        d.setCapacity(2);d.setProblems(Arrays.asList(d.getProblems().get(0),d.getProblems().get(0)));assertThrows(BusinessException.class,()->contests.create(d,99));assertEquals(0,count("contest"));
    }
    @Test void currentRoleControlsEveryAdminMutation() {
        assertThrows(BusinessException.class,()->contests.create(draft(),10));long id=create();jdbc.update("UPDATE `user` SET userRole='user' WHERE id=99");
        assertThrows(BusinessException.class,()->contests.publish(id,command(0),99));assertThrows(BusinessException.class,()->contests.detail(id,99,true));
    }
    @Test void draftEditChecksVersionAndPublishedConfigurationIsFrozen() {
        long id=create();ContestDraft changed=draft();changed.setExpectedVersion(1L);assertThrows(SubmissionConflictException.class,()->contests.update(id,changed,99));
        changed.setExpectedVersion(0L);contests.update(id,changed,99);contests.publish(id,command(1),99);
        changed.setExpectedVersion(2L);assertThrows(SubmissionConflictException.class,()->contests.update(id,changed,99));
        assertEquals(2L,contests.load(id,false).publishedVersion.longValue());
    }
    @Test void publishReplayCreatesOneSnapshotAndOneAudit() {
        long id=published();contests.publish(id,command(0),99);assertEquals(1,count("contest_problem_snapshot"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit WHERE action='CONTEST_PUBLISH'",Integer.class));
    }
    @Test void privateProblemsNeverEnterPracticeAndPrestartOrUnqualifiedDetailHasNoStatement() {
        long id=create();assertThrows(BusinessException.class,()->contests.detail(id,10,false));assertTrue(contests.list(0,false,0).isEmpty());
        contests.publish(id,command(0),99);register(id,10);
        assertFalse(JSONUtil.toJsonStr(contests.detail(id,10,false)).contains("Secret"));Clock.now=BASE.plusMinutes(3);
        String own=JSONUtil.toJsonStr(contests.detail(id,10,false));assertTrue(own.contains("Secret statement"));assertFalse(own.contains("private-answer"));assertFalse(own.contains("private-input"));
        assertFalse(JSONUtil.toJsonStr(contests.detail(id,11,false)).contains("Secret"));assertEquals(1,count("question"));
        QuestionSubmitAddRequest r=new QuestionSubmitAddRequest();r.setQuestionId(snapshot(id));r.setLanguage("java");r.setCode("class Main {}");r.setRequestKey("practice-bypass-key");
        User u=new User();u.setId(10L);u.setUserRole("user");assertThrows(BusinessException.class,()->practice.accept(r,u));assertEquals(0,count("question_submit"));
    }
    @Test void publicTrainingSnapshotSurvivesSourceEditAndContestAcDoesNotAffectPracticeStatistics() {
        ContestDraft d=draft();d.setContestType("PUBLIC_TRAINING");ContestDraft.Problem p=new ContestDraft.Problem();p.setProblemCode("A");p.setSourceQuestionId(1L);d.setProblems(Collections.singletonList(p));
        long id=Long.parseLong((String)contests.create(d,99).get("id"));contests.publish(id,command(0),99);register(id,10);
        jdbc.update("UPDATE question SET content='changed',judgeCase='[]',isDelete=1 WHERE id=1");Clock.now=BASE.plusMinutes(3);
        assertTrue(JSONUtil.toJsonStr(contests.detail(id,10,false)).contains("Already public"));long submission=submit(id);JudgeLease lease=claim(submission);assertEquals("3",lease.getCases().get(0).getOutput());complete(lease);
        assertEquals(0,jdbc.queryForObject("SELECT submitNum+acceptedNum FROM question WHERE id=1",Integer.class));
    }
    @Test void publishFailureRollsBackAllSnapshotsAndSpecs() {
        long id=create();Clock.now=BASE.plusMinutes(1);assertThrows(SubmissionConflictException.class,()->contests.publish(id,command(0),99));
        assertEquals("DRAFT",contests.load(id,false).status);assertEquals(0,count("judge_spec"));assertEquals(0,count("contest_problem_snapshot"));
    }
    @Test void registrationUsesHalfOpenDatabaseWindowAndReplayAfterCloseIsStable() {
        long id=published();assertThrows(SubmissionConflictException.class,()->contests.register(id,KEY,10));
        register(id,10);String request=(String)contests.registration(id,10).get("requestId");Clock.now=BASE.plusMinutes(2);
        assertEquals(request,contests.register(id,KEY,10).get("requestId"));assertThrows(SubmissionConflictException.class,()->contests.register(id,KEY,11));
        assertThrows(SubmissionConflictException.class,()->contests.register(id,"different-key",10));assertEquals(1,contests.load(id,false).remaining);
    }
    @Test void concurrentRegistrationCannotOversellAndEachUserHasOneFact() throws Exception {
        long id=published();Clock.now=BASE.plusMinutes(1);ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();for(long user=10;user<18;user++) { final long actor=user;results.add(pool.submit(()->{start.await();try { facade.retry(()->contests.register(id,KEY,actor));return true; }catch(SubmissionConflictException full) { return false; }})); }
            start.countDown();int confirmed=0;for(Future<Boolean> f:results) { if(f.get(10,TimeUnit.SECONDS)) { confirmed++; } }
            assertEquals(2,confirmed);assertEquals(0,contests.load(id,false).remaining);assertEquals(2,count("contest_registration"));assertEquals(2,count("contest_registration_request"));
        } finally { pool.shutdownNow(); }
    }
    @Test void duplicateRegistrationNeverConsumesCapacityTwice() throws Exception {
        long id=published();register(id,10);contests.register(id,KEY,10);assertEquals(1,contests.load(id,false).remaining);assertEquals(1,count("contest_registration"));
    }
    @Test void cancellationKeepsConfirmationHistoryButImmediatelyInvalidatesQualification() {
        long id=published();register(id,10);contests.cancel(id,command(1),99);contests.cancel(id,command(1),99);
        Map<String,Object> registration=contests.registration(id,10);assertEquals("CONFIRMED",registration.get("status"));assertEquals(false,registration.get("qualificationValid"));
        assertThrows(SubmissionConflictException.class,()->contests.register(id,KEY,11));Clock.now=BASE.plusMinutes(3);assertThrows(SubmissionConflictException.class,()->submit(id));
        assertFalse(JSONUtil.toJsonStr(contests.detail(id,10,false)).contains("Secret statement"));assertEquals(1,count("contest_registration"));
    }
    @Test void registrationAndCancellationRaceAlwaysLeavesClosedAuthorityAndConservedCapacity() throws Exception {
        long id=published();Clock.now=BASE.plusMinutes(1);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<?> a=pool.submit(()->{try { start.await();contests.register(id,KEY,10); }catch(SubmissionConflictException expected) { }catch(InterruptedException e) { throw new RuntimeException(e); }});
            Future<?> b=pool.submit(()->{try { start.await();contests.cancel(id,command(1),99); }catch(InterruptedException e) { throw new RuntimeException(e); }});start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertEquals("CANCELLED",contests.load(id,false).status);assertEquals(2,contests.load(id,false).remaining+count("contest_registration"));
            assertEquals(false,contests.registration(id,10).get("qualificationValid"));assertThrows(SubmissionConflictException.class,()->contests.register(id,KEY,11));
        } finally { pool.shutdownNow(); }
    }
    @Test void globalReadinessBlocksAllNewSubmissionsAndRequiresAuditedVersion() {
        long id=published();register(id,10);Clock.now=BASE.plusMinutes(3);ContestCommand change=command(1);change.setRegistrationReady(false);contests.readiness(id,change,99);
        assertThrows(SubmissionConflictException.class,()->submit(id));assertThrows(SubmissionConflictException.class,()->contests.readiness(id,change,99));
        change.setExpectedVersion(2L);change.setRegistrationReady(true);contests.readiness(id,change,99);assertTrue(submit(id)>0);
    }
    @Test void cancellationAndSubmissionRaceSerializesAcceptanceAndDoesNotStopAcceptedJudging() throws Exception {
        long id=published();register(id,10);Clock.now=BASE.plusMinutes(3);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Long> a=pool.submit(()->{start.await();try { return submit(id); }catch(SubmissionConflictException closed) { return null; }});
            Future<?> b=pool.submit(()->{try { start.await();contests.cancel(id,command(1),99); }catch(InterruptedException e) { throw new RuntimeException(e); }});
            start.countDown();Long accepted=a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals("CANCELLED",contests.load(id,false).status);
            assertThrows(SubmissionConflictException.class,()->facade.submit(id,input(id),"another-submit-key",10));
            if(accepted!=null) { assertEquals(accepted.longValue(),submit(id));complete(claim(accepted));assertEquals(1,count("submission_execution")); }
            else { assertEquals(0,count("submission_execution")); }
        } finally { pool.shutdownNow(); }
    }
    @Test void readinessCannotBeRestoredOverBrokenCapacityLedger() {
        long id=published();ContestCommand change=command(1);change.setRegistrationReady(false);contests.readiness(id,change,99);
        jdbc.update("UPDATE contest SET remainingCapacity=1 WHERE id=?",id);change.setExpectedVersion(2L);change.setRegistrationReady(true);
        assertThrows(SubmissionConflictException.class,()->contests.readiness(id,change,99));assertFalse(contests.load(id,false).ready);
    }
    @Test void pendingOtherUserDoesNotBlockConfirmedParticipant() {
        long id=published();register(id,10);jdbc.update("INSERT INTO contest_registration_request(id,contestId,publishedVersion,userId,eventId,requestKey,requestHash,reservedAt,status,stateVersion,reclaimState,createdAt) VALUES(55,?,1,11,?,'pending-key','hash',?,'PROCESSING',0,'NOT_REQUIRED',?)",id,UUID.randomUUID().toString(),Clock.now,Clock.now);
        Clock.now=BASE.plusMinutes(3);assertTrue(submit(id)>0);assertThrows(SubmissionConflictException.class,()->facade.submit(id,input(id),"other-submission-key",11));
    }
    @Test void submissionsUseHalfOpenWindowAndOriginalKeyReplaysAfterEnd() {
        long id=published();register(id,10);Clock.now=BASE.plusMinutes(3).minusNanos(1000);assertThrows(SubmissionConflictException.class,()->submit(id));
        Clock.now=BASE.plusMinutes(3);long accepted=submit(id);assertEquals(Clock.now,repo.find(accepted,false).getAcceptedAt());Clock.now=BASE.plusMinutes(4);
        assertEquals(accepted,submit(id));assertThrows(SubmissionConflictException.class,()->facade.submit(id,input(id),"new-submission-key",10));
        contests.finalizeIfDue(id);contests.finalizeIfDue(id);assertEquals("FINALIZING",contests.load(id,false).status);complete(claim(accepted));assertEquals(ExecutionStatus.COMPLETED,repo.find(accepted,false).getExecutionStatus());
    }
    @Test void submittingWrongSnapshotVersionOrChangedPayloadIsRejected() {
        long id=published();register(id,10);Clock.now=BASE.plusMinutes(3);ContestSubmission r=input(id);r.setPublishedVersion(2L);
        assertThrows(SubmissionConflictException.class,()->facade.submit(id,r,"stable-submit-key",10));r.setPublishedVersion(1L);r.setProblemSnapshotId(123L);
        assertThrows(BusinessException.class,()->facade.submit(id,r,"stable-submit-key",10));submit(id);ContestSubmission changed=input(id);changed.setCode("different");
        assertThrows(SubmissionConflictException.class,()->facade.submit(id,changed,"stable-submit-key",10));assertEquals(1,count("question_submit"));
    }
    @Test void outboxFailureRollsBackContestSubmissionAndContext() {
        long id=published();register(id,10);Clock.now=BASE.plusMinutes(3);jdbc.execute("ALTER TABLE reliable_outbox ADD CONSTRAINT no_events CHECK(eventType='NEVER')");
        assertThrows(RuntimeException.class,()->submit(id));assertEquals(0,count("question_submit"));assertEquals(0,count("submission_execution"));
    }
    @Test void staleAccountCannotRegisterOrSubmit() {
        long id=published();register(id,10);jdbc.update("UPDATE `user` SET userRole='ban' WHERE id IN(10,11)");
        assertThrows(BusinessException.class,()->contests.register(id,KEY,11));Clock.now=BASE.plusMinutes(3);assertThrows(BusinessException.class,()->submit(id));
    }
    @Test void concurrentSameSubmissionKeyHasOneExecutionAndDispatch() throws Exception {
        long id=published();register(id,10);Clock.now=BASE.plusMinutes(3);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Long> call=()->{start.await();return submit(id);};Future<Long> a=pool.submit(call),b=pool.submit(call);start.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(1,count("question_submit"));assertEquals(1,count("reliable_outbox"));
        } finally { pool.shutdownNow(); }
    }
    @Test void httpContractReturns201202And403409WithoutLeakingDatabaseErrors() throws Exception {
        UserFeignClient users=mock(UserFeignClient.class);User user=new User();user.setId(10L);when(users.getLoginUser(any())).thenReturn(user);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new ContestController(contests,facade,users)).setControllerAdvice(new ExecutionRpcAdvice(),new SubmissionConflictAdvice()).build();
        mvc.perform(post("/contests").contentType("application/json").content(JSONUtil.toJsonStr(draft()))).andExpect(status().isForbidden());
        user.setId(99L);mvc.perform(post("/contests").contentType("application/json").content(JSONUtil.toJsonStr(draft()))).andExpect(status().isCreated());
        long id=published();user.setId(10L);Clock.now=BASE.plusMinutes(1);
        mvc.perform(post("/contests/"+id+"/registrations").header("Idempotency-Key",KEY)).andExpect(status().isAccepted());
        Clock.now=BASE.plusMinutes(3);mvc.perform(post("/contests/"+id+"/submissions").header("Idempotency-Key","stable-submit-key").contentType("application/json").content(JSONUtil.toJsonStr(input(id)))).andExpect(status().isAccepted());
        mvc.perform(post("/contests/"+id+"/submissions").header("Idempotency-Key","stable-submit-key").contentType("application/json").content(JSONUtil.toJsonStr(new ContestSubmission()))).andExpect(status().isBadRequest());
        jdbc.execute("DROP TABLE contest_registration");mvc.perform(get("/contests/"+id+"/registrations/me")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("SUBMISSION_DATABASE_UNAVAILABLE"));
    }
}
