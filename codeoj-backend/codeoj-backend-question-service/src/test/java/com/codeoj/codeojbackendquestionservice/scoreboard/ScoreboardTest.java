package com.codeoj.codeojbackendquestionservice.scoreboard;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.codesandbox.JudgeInfo;
import com.codeoj.codeojbackendmodel.model.dto.question.*;
import com.codeoj.codeojbackendmodel.model.entity.User;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;
import com.codeoj.codeojbackendmodel.model.reliability.*;
import com.codeoj.codeojbackendquestionservice.contest.*;
import com.codeoj.codeojbackendquestionservice.submission.*;
import com.codeoj.codeojbackendquestionservice.submission.outbox.*;
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

/** Functional concurrency tests with real Spring transactions and H2; not MySQL/MQ acceptance. */
@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=ScoreboardTest.Config.class) @Timeout(20)
class ScoreboardTest {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean static org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor repositoryProxies() { return new org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor(); }
        @Bean DataSource ds() { return new DriverManagerDataSource("jdbc:h2:mem:scoreboard-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000","sa",""); }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ExecutionRepository repo(JdbcTemplate j) { return new ExecutionRepository(j); }
        @Bean SubmissionAcceptance acceptance(ExecutionRepository r) { return new SubmissionAcceptance(r); }
        @Bean ExecutionCommands commands(ExecutionRepository r) { return new ExecutionCommands(r); }
        @Bean ContestTransactions contests(JdbcTemplate j,ExecutionRepository r,SubmissionAcceptance a) { return new ContestTransactions(j,r,a); }
        @Bean ContestService facade(ContestTransactions t,ExecutionRepository r) { return new ContestService(t,r); }
        @Bean ScoreboardTransactions scores(JdbcTemplate j,ExecutionRepository r) { return new ScoreboardTransactions(j,r); }
        @Bean ScoreboardService service(ScoreboardTransactions s) { return new ScoreboardService(s); }
        @Bean OutboxStore outbox(JdbcTemplate j,ExecutionRepository r) { return new OutboxStore(j,r); }
    }
    public static class Clock { static volatile LocalDateTime now;public static LocalDateTime utcTimestamp(int precision) { return now; } }
    @Autowired DataSource ds;@Autowired JdbcTemplate jdbc;@Autowired ContestTransactions contests;@Autowired ContestService facade;
    @Autowired ExecutionCommands executions;@Autowired ExecutionRepository repo;@Autowired ScoreboardTransactions scores;
    @Autowired SubmissionAcceptance acceptance;@Autowired ScoreboardService service;@Autowired OutboxStore outbox;
    static final LocalDateTime BASE=LocalDateTime.of(2026,10,20,0,0);
    long contest;Map<String,Long> problems;int sequence;
    @BeforeEach void schema() throws Exception {
        Clock.now=BASE;jdbc.execute("DROP ALL OBJECTS");jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR '"+Clock.class.getName()+".utcTimestamp'");
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,userRole VARCHAR(32),isDelete INT DEFAULT 0)");
        jdbc.update("INSERT INTO `user` VALUES(10,'user',0),(11,'user',0),(12,'user',0),(13,'user',0),(14,'user',0),(99,'admin',0)");
        jdbc.execute("CREATE TABLE question(id BIGINT PRIMARY KEY,title VARCHAR(120),content TEXT,judgeConfig TEXT,judgeCase TEXT,submitNum INT DEFAULT 0,acceptedNum INT DEFAULT 0,isDelete INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE question_submit(id BIGINT PRIMARY KEY,language VARCHAR(128),code TEXT,judgeInfo TEXT,status INT,questionId BIGINT,userId BIGINT,isDelete INT)");
        for(String name:Arrays.asList("V001__reliable_judging.sql","V002__contest_core.sql","V003__async_registration.sql","V004__contest_scoreboard.sql")) {
            String sql=new String(Files.readAllBytes(Paths.get("..","mysql-init","migrations",name)),StandardCharsets.UTF_8)
                    .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","");
            try(Connection c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))); }
        }
        ContestDraft d=new ContestDraft();d.setTitle("Scoreboard fixture");d.setContestType("PRIVATE");d.setCapacity(4);
        d.setRegistrationOpenAt(utc(1));d.setRegistrationCloseAt(utc(2));d.setStartAt(utc(3));d.setEndAt(utc(63));
        List<ContestDraft.Problem> ps=new ArrayList<>();for(String code:Arrays.asList("A","B")) {
            ContestDraft.Problem p=new ContestDraft.Problem();p.setProblemCode(code);p.setTitle("Secret "+code);p.setContent("hidden statement");
            JudgeConfig limits=new JudgeConfig();limits.setTimeLimit(1000L);limits.setMemoryLimit(131072L);p.setLimits(limits);
            JudgeCase sample=new JudgeCase();sample.setInput("hidden input");sample.setOutput("hidden answer");p.setCases(Collections.singletonList(sample));ps.add(p);
        }d.setProblems(ps);contest=Long.parseLong((String)contests.create(d,99).get("id"));
        ContestCommand publish=new ContestCommand();publish.setExpectedVersion(0L);contests.publish(contest,publish,99);
        Clock.now=BASE.plusMinutes(1);for(long user=10;user<14;user++) { contests.register(contest,"registration-key-"+user,user); }
        problems=new HashMap<>();jdbc.query("SELECT problemCode,id FROM contest_problem_snapshot WHERE contestId=?",rs->{problems.put(rs.getString(1),rs.getLong(2));},contest);
    }
    String utc(int minute) { return BASE.plusMinutes(minute).toInstant(ZoneOffset.UTC).toString(); }
    long submit(long user,String problem,int minutes) {
        return submitAt(user,problem,BASE.plusMinutes(3+minutes));
    }
    long submitAt(long user,String problem,LocalDateTime now) {
        Clock.now=now;ContestSubmission r=new ContestSubmission();r.setProblemSnapshotId(problems.get(problem));r.setPublishedVersion(1L);r.setLanguage("java");r.setCode("public class Main {} // private source");
        return facade.submit(contest,r,"submission-key-"+(++sequence),user);
    }
    JudgeLease claim(long id) { ExecutionRecord e=repo.find(id,false);return executions.claim(repo.dispatch(id,e.getDispatchGeneration(),e.getDispatchEventId())); }
    void complete(JudgeLease lease,JudgeVerdict verdict) {
        assertTrue(lease.isClaimed());ExecutionCommand c=new ExecutionCommand();c.setSubmissionId(lease.getSubmissionId());c.setAttemptNo(lease.getAttemptNo());c.setExecutionToken(lease.getExecutionToken());
        JudgeInfo result=new JudgeInfo();result.setVerdict(verdict);result.setTime(1L);result.setMemory(1L);c.setResult(result);assertTrue(executions.complete(c));
    }
    String result(long user,String problem,int minutes,JudgeVerdict verdict) {
        long id=submit(user,problem,minutes);complete(claim(id),verdict);
        return jdbc.queryForObject("SELECT eventId FROM reliable_outbox WHERE eventType='JUDGE_RESULT' AND aggregateId=?",String.class,id);
    }
    boolean apply(String event) { String payload=jdbc.queryForObject("SELECT payload FROM reliable_outbox WHERE eventId=?",String.class,event);return scores.project(event,JSONUtil.toBean(payload,ScoreResultEvent.class)); }
    Map<String,Object> view() { return scores.snapshot(contest,0,20,null,14,true); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> entries(Map<String,Object> view) { return (List<Map<String,Object>>)view.get("entries"); }
    ScoreboardCommand command() {
        Map<String,Object> view=view();ScoreboardCommand c=new ScoreboardCommand();c.setExpectedVersion(((Number)view.get("contestVersion")).longValue());c.setExpectedProjectionVersion(((Number)view.get("projectionVersion")).longValue());c.setExpectedRevision(((Number)view.get("revision")).longValue());c.setReason("checked independent score fixture");return c;
    }
    void ended() { Clock.now=BASE.plusMinutes(63);contests.finalizeIfDue(contest); }
    void assertRanks(long... ranks) { List<Map<String,Object>> entries=entries(view());assertEquals(ranks.length,entries.size());for(int i=0;i<ranks.length;i++) { assertEquals(ranks[i],entries.get(i).get("rank")); } }
    @Test void zeroRowsAreCreatedInConfirmationTransactionAndAllTie() {
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM contest_score_user",Integer.class));assertRanks(1,1,1,1);
        assertFalse(JSONUtil.toJsonStr(view()).contains("hidden"));
    }
    @Test void handCalculatedTieAndEarlierAcDeliveredLastChangeAchievedAt() {
        String a=result(10,"A",10,JudgeVerdict.AC),b=result(10,"B",20,JudgeVerdict.AC),earlier=result(10,"B",12,JudgeVerdict.AC);
        String c=result(11,"A",15,JudgeVerdict.AC),d=result(11,"B",20,JudgeVerdict.AC);
        for(String e:Arrays.asList(d,b,c,a)) { assertTrue(apply(e)); }assertRanks(1,1,3,3);
        assertTrue(apply(earlier));assertTrue(apply(earlier));assertRanks(1,2,3,3);
        assertEquals(utc(15),entries(view()).get(0).get("achievedAt")); // start+12 minutes = BASE+15
        assertEquals(2,entries(view()).get(0).get("solvedCount"));
    }
    @Test void competitionRanksCrossPageAndZeroTiesDoNotRestart() {
        apply(result(10,"A",1,JudgeVerdict.AC));apply(result(11,"A",1,JudgeVerdict.AC));assertRanks(1,1,3,3);
        List<Map<String,Object>> page=entries(scores.snapshot(contest,1,1,null,14,true));assertEquals(1L,page.get(0).get("rank"));assertEquals("11",page.get(0).get("userId"));
        assertEquals(3L,entries(scores.snapshot(contest,3,1,null,14,true)).get(0).get("rank"));
    }
    @Test void userIdOnlyStabilizesDisplayAndNeverBreaksTie() {
        apply(result(11,"A",2,JudgeVerdict.AC));apply(result(10,"A",2,JudgeVerdict.AC));assertRanks(1,1,3,3);assertEquals("10",entries(view()).get(0).get("userId"));
    }
    @Test void duplicateAndSameProblemAcNeverAddPoints() {
        String e=result(10,"A",1,JudgeVerdict.AC);for(int i=0;i<5;i++) { assertTrue(apply(e)); }apply(result(10,"A",3,JudgeVerdict.AC));
        assertEquals(1,entries(view()).get(0).get("solvedCount"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM contest_score_problem",Integer.class));
    }
    @Test void nonAcIsZeroAndMalformedEventCannotChangeScores() {
        String e=result(10,"A",1,JudgeVerdict.WA);assertTrue(apply(e));ScoreResultEvent wrong=new ScoreResultEvent();wrong.setSubmissionId(1L);wrong.setResultVersion(1);
        assertFalse(scores.project(e,wrong));assertFalse(scores.project("not-a-uuid",wrong));assertRanks(1,1,1,1);
    }
    @Test void concurrentDifferentProblemsForSameUserCannotLoseAggregate() throws Exception {
        String a=result(10,"A",1,JudgeVerdict.AC),b=result(10,"B",2,JudgeVerdict.AC);ExecutorService pool=Executors.newFixedThreadPool(4);CountDownLatch latch=new CountDownLatch(1);
        try { List<Future<Boolean>> work=new ArrayList<>();for(String event:Arrays.asList(a,b,a,b)) { work.add(pool.submit(()->{latch.await();return apply(event);})); }latch.countDown();for(Future<Boolean> f:work) { assertTrue(f.get(10,TimeUnit.SECONDS)); } }
        finally { pool.shutdownNow(); }assertEquals(2,entries(view()).get(0).get("solvedCount"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM scoreboard_result_receipt",Integer.class));
    }
    @Test void postDeadlineCompletionCountsByAcceptanceTimeAndBlocksPrematureFinish() {
        long id=submit(10,"A",59);Clock.now=BASE.plusMinutes(63).minusSeconds(1);JudgeLease lease=claim(id);ended();
        assertThrows(SubmissionConflictException.class,()->scores.rebuild(contest,command(),99,false));assertEquals(1L,view().get("pendingCount"));
        complete(lease,JudgeVerdict.AC);String e=jdbc.queryForObject("SELECT eventId FROM reliable_outbox WHERE eventType='JUDGE_RESULT' AND aggregateId=?",String.class,id);apply(e);
        scores.rebuild(contest,command(),99,false);scores.finish(contest,command(),99);assertEquals(true,view().get("final"));assertEquals("FINISHED",view().get("status"));assertRanks(1,2,2,2);
    }
    @Test void platformNeedsAttentionIsNeverConvertedToWa() {
        long id=submit(10,"A",1);JudgeLease lease=claim(id);ExecutionCommand fail=new ExecutionCommand();fail.setSubmissionId(id);fail.setAttemptNo(lease.getAttemptNo());fail.setExecutionToken(lease.getExecutionToken());fail.setErrorCode("SANDBOX_UNAVAILABLE");
        jdbc.update("UPDATE submission_execution SET maxAttempts=1 WHERE submissionId=?",id);assertTrue(executions.fail(fail));ended();assertEquals("NEEDS_ATTENTION",repo.find(id,false).getExecutionStatus().name());
        assertThrows(SubmissionConflictException.class,()->scores.rebuild(contest,command(),99,false));assertRanks(1,1,1,1);
    }
    @Test void finishRequiresRebuildAndDriftCannotBeSilentlyFinalized() {
        apply(result(10,"A",1,JudgeVerdict.AC));ended();assertThrows(SubmissionConflictException.class,()->scores.finish(contest,command(),99));
        scores.rebuild(contest,command(),99,false);jdbc.update("UPDATE contest_score_user SET solvedCount=7 WHERE contestId=? AND userId=10 AND projectionVersion=2",contest);
        assertThrows(SubmissionConflictException.class,()->scores.finish(contest,command(),99));Map<String,Object> rebuilt=scores.rebuild(contest,command(),99,false);assertEquals(false,rebuilt.get("matchedPrevious"));scores.finish(contest,command(),99);assertEquals(1,entries(view()).get(0).get("solvedCount"));
    }
    @Test void rebuildFromAllFactsDoesNotRequireBrokerDeliveryAndLateReplayIsHarmless() {
        String e=result(10,"A",1,JudgeVerdict.AC);ended();assertEquals(1L,view().get("projectionPendingCount"));scores.rebuild(contest,command(),99,false);
        assertEquals(1,entries(view()).get(0).get("solvedCount"));assertTrue(apply(e));scores.finish(contest,command(),99);Map<String,Object> finalView=view();assertTrue(apply(e));assertEquals(finalView,view());
    }
    @Test void rebuildOnlyInFinalizingAndOldVersionRemainsQueryable() {
        apply(result(10,"A",1,JudgeVerdict.AC));assertThrows(SubmissionConflictException.class,()->scores.rebuild(contest,command(),99,false));ended();
        scores.rebuild(contest,command(),99,false);Map<String,Object> old=scores.snapshot(contest,0,20,1L,14,true);assertEquals("RETIRED",old.get("versionState"));assertEquals(1L,old.get("projectionVersion"));assertEquals(1,entries(old).get(0).get("solvedCount"));
    }
    @Test void finalRevisionKeepsPreviousFinalImmutableAndAudited() {
        apply(result(10,"A",1,JudgeVerdict.AC));ended();scores.rebuild(contest,command(),99,false);scores.finish(contest,command(),99);
        List<Map<String,Object>> original=entries(scores.snapshot(contest,0,20,2L,14,true));ScoreboardCommand stale=command();scores.rebuild(contest,stale,99,true);
        assertEquals(original,entries(scores.snapshot(contest,0,20,2L,14,true)));assertEquals("FINAL",scores.snapshot(contest,0,20,2L,14,true).get("versionState"));assertEquals(true,scores.snapshot(contest,0,20,2L,14,true).get("final"));assertEquals(3L,view().get("finalVersion"));
        assertThrows(SubmissionConflictException.class,()->scores.rebuild(contest,stale,99,true));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit WHERE action='SCOREBOARD_FINAL_REVISION'",Integer.class));
    }
    @Test void cacheInvalidationChangesNamespaceAndRequiresFreshRoleVersionsAndReason() {
        ScoreboardCommand c=command();Map<String,Object> before=view();scores.clearCache(contest,c,99);assertEquals(((Number)before.get("cacheEpoch")).longValue()+1,view().get("cacheEpoch"));
        assertThrows(SubmissionConflictException.class,()->scores.clearCache(contest,c,99));ScoreboardCommand current=command();current.setReason(" ");assertThrows(BusinessException.class,()->scores.clearCache(contest,current,99));
        jdbc.update("UPDATE `user` SET userRole='user' WHERE id=99");assertThrows(BusinessException.class,()->scores.clearCache(contest,command(),99));
    }
    @Test void pendingRegistrationAndCapacityMismatchBlockSettlement() {
        ended();jdbc.update("UPDATE contest_registration_request SET status='MANUAL_REVIEW' WHERE contestId=? AND userId=10",contest);
        assertThrows(SubmissionConflictException.class,()->scores.rebuild(contest,command(),99,false));jdbc.update("UPDATE contest_registration_request SET status='CONFIRMED' WHERE contestId=?",contest);
        jdbc.update("UPDATE contest SET remainingCapacity=1 WHERE id=?",contest);assertThrows(SubmissionConflictException.class,()->scores.rebuild(contest,command(),99,false));
    }
    @Test void redisModeRequiresFinalizingReconciliationAndCompletedSynchronization() {
        ended();jdbc.update("UPDATE contest SET registrationMode='REDIS_READY' WHERE id=?",contest);assertThrows(SubmissionConflictException.class,()->scores.rebuild(contest,command(),99,false));
        jdbc.update("UPDATE contest SET registrationReconciledAt=? WHERE id=?",Clock.now,contest);scores.rebuild(contest,command(),99,false);scores.finish(contest,command(),99);
    }
    @Test void tamperedResultDigestAndWrongSnapshotCannotBecomeFinalFacts() {
        String e=result(10,"A",1,JudgeVerdict.AC);long id=jdbc.queryForObject("SELECT aggregateId FROM reliable_outbox WHERE eventId=?",Long.class,e);
        jdbc.update("UPDATE question_submit SET judgeInfo='{}' WHERE id=?",id);assertThrows(SubmissionConflictException.class,()->apply(e));ended();assertThrows(SubmissionConflictException.class,()->scores.rebuild(contest,command(),99,false));
    }
    @Test void cancellationKeepsScoreHistoryAndRejectsFinalization() {
        String e=result(10,"A",1,JudgeVerdict.AC);ContestCommand c=new ContestCommand();c.setExpectedVersion(1L);c.setReason("cancel fixture");contests.cancel(contest,c,99);assertTrue(apply(e));assertRanks(1,1,1,1);ended();assertThrows(SubmissionConflictException.class,()->scores.rebuild(contest,command(),99,false));
    }
    @Test void missingBusinessReceiptRepublishesOriginalEventWithFiniteBudget() {
        String e=result(10,"A",1,JudgeVerdict.AC);assertTrue(outbox.due("JUDGE_RESULT").contains(e));OutboxStore.Publication p=outbox.claim(e,"JUDGE_RESULT");outbox.record(e,p.getToken(),"ACK",false,null);
        Clock.now=Clock.now.plusSeconds(46);scores.recoverPublications();assertEquals(e,outbox.due("JUDGE_RESULT").get(0));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM scoreboard_result_receipt",Integer.class));
        jdbc.update("UPDATE reliable_outbox SET attempts=20 WHERE eventId=?",e);assertNull(outbox.claim(e,"JUDGE_RESULT"));ScoreboardCommand c=command();assertThrows(SubmissionConflictException.class,()->scores.replay(contest,c,99));
        c.setAdditionalPublishAttempts(2);assertEquals(1,scores.replay(contest,c,99));assertNotNull(outbox.claim(e,"JUDGE_RESULT"));apply(e);assertFalse(outbox.due("JUDGE_RESULT").contains(e));
    }
    @Test void loggedInNonParticipantCanReadButCannotMutateAndNoHiddenFieldsLeak() throws Exception {
        UserFeignClient users=mock(UserFeignClient.class);User user=new User();user.setId(14L);when(users.getLoginUser(any())).thenReturn(user);
        MockMvc http=MockMvcBuilders.standaloneSetup(new ScoreboardController(service,scores,users)).setControllerAdvice(new SubmissionConflictAdvice(),new ExecutionRpcAdvice()).build();
        String body=http.perform(get("/contests/"+contest+"/scoreboard")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("hidden"));assertFalse(body.contains("judgeSpec"));assertFalse(body.contains("private source"));
        http.perform(post("/contests/"+contest+"/scoreboard/cache-clear").contentType("application/json").content(JSONUtil.toJsonStr(command()))).andExpect(status().isForbidden());
        when(users.getLoginUser(any())).thenThrow(new BusinessException(com.codeoj.codeojbackendcommon.common.ErrorCode.NOT_LOGIN_ERROR));
        http.perform(get("/contests/"+contest+"/scoreboard")).andExpect(status().isUnauthorized());
    }
    public static class RejectRebuiltProblem implements org.h2.api.Trigger {
        public void fire(java.sql.Connection connection,Object[] oldRow,Object[] newRow) throws java.sql.SQLException {
            if(((Number)newRow[1]).longValue()>1) { throw new java.sql.SQLException("CONTROLLED_REBUILD_WRITE_FAILURE"); }
        }
    }
    @Test void midRebuildFailureRollsBackNewRowsAndKeepsReadVersion() {
        apply(result(10,"A",1,JudgeVerdict.AC));ended();Map<String,Object> original=view();
        jdbc.execute("CREATE TRIGGER fail_rebuilt_problem BEFORE INSERT ON contest_score_problem FOR EACH ROW CALL '"+RejectRebuiltProblem.class.getName()+"'");
        assertThrows(org.springframework.dao.DataAccessException.class,()->scores.rebuild(contest,command(),99,false));
        assertEquals(original,view());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM contest_scoreboard_version WHERE contestId=?",Integer.class,contest));
    }
    @Test void delayedProjectionAndRebuildRaceEitherConflictsExplicitlyOrProducesSameFacts() throws Exception {
        String event=result(10,"A",1,JudgeVerdict.AC);ended();ScoreboardCommand command=command();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<?> projection=pool.submit(()->{try { start.await();assertTrue(apply(event)); }catch(InterruptedException e) { throw new RuntimeException(e); }});
            Future<?> rebuilding=pool.submit(()->{try { start.await();scores.rebuild(contest,command,99,false); }catch(SubmissionConflictException freshReadRequired) { }catch(InterruptedException e) { throw new RuntimeException(e); }});
            start.countDown();projection.get(10,TimeUnit.SECONDS);rebuilding.get(10,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        if(((Number)view().get("projectionVersion")).longValue()==1) { scores.rebuild(contest,command(),99,false); }
        scores.finish(contest,command(),99);assertRanks(1,2,2,2);assertEquals(1,entries(view()).get(0).get("solvedCount"));
    }

    @Test void deletingAllScoreRowsCanBeRebuiltFromFactsIncludingZeroParticipants() {
        apply(result(10,"A",1,JudgeVerdict.AC));ended();
        jdbc.update("DELETE FROM contest_score_problem WHERE contestId=?",contest);jdbc.update("DELETE FROM contest_score_user WHERE contestId=?",contest);
        Map<String,Object> repaired=scores.rebuild(contest,command(),99,false);assertEquals(false,repaired.get("matchedPrevious"));scores.finish(contest,command(),99);assertRanks(1,2,2,2);
    }
    @Test void microsecondAcceptanceTimeBreaksOnlyActualTimeTies() {
        long a=submitAt(10,"A",BASE.plusMinutes(4));complete(claim(a),JudgeVerdict.AC);
        long b=submitAt(11,"A",BASE.plusMinutes(4).plusNanos(1000));complete(claim(b),JudgeVerdict.AC);
        String ea=jdbc.queryForObject("SELECT eventId FROM reliable_outbox WHERE eventType='JUDGE_RESULT' AND aggregateId=?",String.class,a);
        String eb=jdbc.queryForObject("SELECT eventId FROM reliable_outbox WHERE eventType='JUDGE_RESULT' AND aggregateId=?",String.class,b);
        apply(eb);apply(ea);assertRanks(1,2,3,3);assertEquals("10",entries(view()).get(0).get("userId"));
    }
    @Test void practiceResultIsReceiptedWithoutChangingAnyContestScore() {
        jdbc.update("INSERT INTO question(id,title,content,judgeConfig,judgeCase) VALUES(1,'public','public','{\"timeLimit\":1000,\"memoryLimit\":131072}','[{\"input\":\"1\",\"output\":\"1\"}]')");
        com.codeoj.codeojbackendmodel.model.dto.questionsubmit.QuestionSubmitAddRequest request=new com.codeoj.codeojbackendmodel.model.dto.questionsubmit.QuestionSubmitAddRequest();
        request.setQuestionId(1L);request.setRequestKey("practice-stable-key");request.setLanguage("java");request.setCode("public class Main {}");User u=new User();u.setId(10L);
        long id=acceptance.accept(request,u);complete(claim(id),JudgeVerdict.AC);
        String event=jdbc.queryForObject("SELECT eventId FROM reliable_outbox WHERE eventType='JUDGE_RESULT' AND aggregateId=?",String.class,id);assertTrue(apply(event));assertTrue(apply(event));assertRanks(1,1,1,1);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM scoreboard_result_receipt WHERE contestId IS NULL",Integer.class));assertEquals(0L,view().get("projectionPendingCount"));
    }

}
