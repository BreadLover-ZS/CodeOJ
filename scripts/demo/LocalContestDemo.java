import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendcodesandbox.service.impl.JavaNativeCodeSandbox;
import com.codeoj.codeojbackendjudgeservice.judge.SandboxResultMapper;
import com.codeoj.codeojbackendjudgeservice.judge.strategy.*;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import com.codeoj.codeojbackendmodel.model.dto.question.*;
import com.codeoj.codeojbackendmodel.model.entity.Question;
import com.codeoj.codeojbackendmodel.model.enums.JudgeVerdict;
import com.codeoj.codeojbackendmodel.model.reliability.*;
import com.codeoj.codeojbackendquestionservice.contest.*;
import com.codeoj.codeojbackendquestionservice.submission.*;
import com.codeoj.codeojbackendquestionservice.scoreboard.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Trusted local fixture: real transactions + real javac/java; manual dispatch, H2, no broker/HTTP. */
public class LocalContestDemo {
    public static class Clock {
        static LocalDateTime now;
        public static LocalDateTime utcTimestamp(int precision) { return now; }
    }
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource ds() { return new DriverManagerDataSource("jdbc:h2:mem:local-contest;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""); }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ExecutionRepository repo(JdbcTemplate jdbc) { return new ExecutionRepository(jdbc); }
        @Bean SubmissionAcceptance acceptance(ExecutionRepository r) { return new SubmissionAcceptance(r); }
        @Bean ExecutionCommands commands(ExecutionRepository r) { return new ExecutionCommands(r); }
        @Bean ContestTransactions contests(JdbcTemplate j,ExecutionRepository r,SubmissionAcceptance a) { return new ContestTransactions(j,r,a); }
        @Bean ScoreboardTransactions scores(JdbcTemplate j,ExecutionRepository r) { return new ScoreboardTransactions(j,r); }
        @Bean ContestService service(ContestTransactions c,ExecutionRepository r) { return new ContestService(c,r); }
    }
    public static void main(String[] args) throws Exception {
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext(Config.class)) {
            JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);DataSource ds=context.getBean(DataSource.class);
            Clock.now=LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).minusMinutes(5);
            jdbc.execute("CREATE ALIAS UTC_TIMESTAMP FOR '"+Clock.class.getName()+".utcTimestamp'");
            jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,userRole VARCHAR(32),isDelete INT DEFAULT 0)");jdbc.update("INSERT INTO `user` VALUES(10,'user',0),(99,'admin',0)");
            jdbc.execute("CREATE TABLE question(id BIGINT PRIMARY KEY,title VARCHAR(120),content TEXT,judgeConfig TEXT,judgeCase TEXT,submitNum INT DEFAULT 0,acceptedNum INT DEFAULT 0,isDelete INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE question_submit(id BIGINT PRIMARY KEY,language VARCHAR(128),code TEXT,judgeInfo TEXT,status INT,questionId BIGINT,userId BIGINT,isDelete INT)");
            for(String name:Arrays.asList("V001__reliable_judging.sql","V002__contest_core.sql","V003__async_registration.sql","V004__contest_scoreboard.sql")) {
                String sql=new String(Files.readAllBytes(Paths.get(args[0],"codeoj-backend","mysql-init","migrations",name)),StandardCharsets.UTF_8)
                        .replace(" CHARACTER SET ascii COLLATE ascii_bin","").replace(" DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","");
                try(Connection c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))); }
            }
            ContestTransactions contests=context.getBean(ContestTransactions.class);ContestService service=context.getBean(ContestService.class);
            ExecutionRepository repo=context.getBean(ExecutionRepository.class);ExecutionCommands commands=context.getBean(ExecutionCommands.class);
            ContestDraft draft=new ContestDraft();draft.setTitle("本机事务与真实 Java 判题样例");draft.setContestType("PRIVATE");draft.setCapacity(1);
            draft.setRegistrationOpenAt(utc(Clock.now.plusMinutes(1)));draft.setRegistrationCloseAt(utc(Clock.now.plusMinutes(2)));draft.setStartAt(utc(Clock.now.plusMinutes(3)));draft.setEndAt(utc(Clock.now.plusHours(1)));
            ContestDraft.Problem p=new ContestDraft.Problem();p.setProblemCode("A");p.setTitle("求和");p.setContent("输出两个整数之和。");JudgeConfig limits=new JudgeConfig();limits.setTimeLimit(1000L);limits.setMemoryLimit(131072L);p.setLimits(limits);
            JudgeCase sample=new JudgeCase();sample.setInput("1 2");sample.setOutput("3");p.setCases(Collections.singletonList(sample));draft.setProblems(Collections.singletonList(p));
            long contest=Long.parseLong((String)contests.create(draft,99).get("id"));ContestCommand publish=new ContestCommand();publish.setExpectedVersion(0L);contests.publish(contest,publish,99);
            Clock.now=Clock.now.plusMinutes(1);Map<String,Object> registration=contests.register(contest,"local-registration-key",10);
            if(!"CONFIRMED".equals(registration.get("status"))) { throw new AssertionError("Registration not confirmed"); }
            Clock.now=LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            long snapshot=jdbc.queryForObject("SELECT id FROM contest_problem_snapshot WHERE contestId=?",Long.class,contest);
            ContestSubmission request=new ContestSubmission();request.setProblemSnapshotId(snapshot);request.setPublishedVersion(1L);request.setLanguage("java");
            request.setCode("public class Main { public static void main(String[] args) throws Exception { java.util.Scanner s=new java.util.Scanner(System.in); System.out.println(s.nextInt()+s.nextInt()); Thread.sleep(100); }}");
            long id=service.submit(contest,request,"local-submission-key",10);
            if(id!=service.submit(contest,request,"local-submission-key",10)) { throw new AssertionError("Replay created another submission"); }
            String payload=jdbc.queryForObject("SELECT payload FROM reliable_outbox WHERE aggregateId=? AND eventType='JUDGE_DISPATCH'",String.class,id);
            JudgeDispatch dispatch=JSONUtil.toBean(payload,JudgeDispatch.class);JudgeLease lease=commands.claim(dispatch);
            ExecuteCodeRequest execution=ExecuteCodeRequest.builder().language(lease.getLanguage()).code(lease.getCode()).inputList(Collections.singletonList(lease.getCases().get(0).getInput()))
                    .runTimeLimitMs(lease.getLimits().getTimeLimit()).memoryLimitKiB(lease.getLimits().getMemoryLimit()).executionToken(lease.getExecutionToken()).executionDeadlineEpochMs(lease.getDeadlineEpochMs()).build();
            ExecuteCodeResponse response=new JavaNativeCodeSandbox(1).executeCode(execution);JudgeInfo verdict=SandboxResultMapper.userVerdict(response);
            if(verdict==null) {
                Question q=new Question();q.setJudgeConfig(JSONUtil.toJsonStr(lease.getLimits()));JudgeContext judge=new JudgeContext();judge.setQuestion(q);judge.setJudgeInfo(response.getJudgeInfo());
                judge.setInputList(execution.getInputList());judge.setOutputList(response.getOutputList());judge.setJudgeCaseList(lease.getCases());verdict=new JavaLanguageJudgeStrategy().doJudge(judge);
            }
            if(verdict.getVerdict()!=JudgeVerdict.AC) { throw new AssertionError("Expected AC: "+verdict.getVerdict()); }
            ExecutionCommand completed=new ExecutionCommand();completed.setSubmissionId(id);completed.setAttemptNo(lease.getAttemptNo());completed.setExecutionToken(lease.getExecutionToken());completed.setResult(verdict);
            if(!commands.complete(completed) || !commands.complete(completed)) { throw new AssertionError("Completion replay failed"); }
            ScoreboardTransactions scores=context.getBean(ScoreboardTransactions.class);
            String resultEvent=jdbc.queryForObject("SELECT eventId FROM reliable_outbox WHERE eventType='JUDGE_RESULT' AND aggregateId=?",String.class,id);
            ScoreResultEvent resultMessage=JSONUtil.toBean(jdbc.queryForObject("SELECT payload FROM reliable_outbox WHERE eventId=?",String.class,resultEvent),ScoreResultEvent.class);
            if(!scores.project(resultEvent,resultMessage)) { throw new AssertionError("Projection rejected result event"); }
            Clock.now=LocalDateTime.ofInstant(Instant.parse(draft.getEndAt()),ZoneOffset.UTC);contests.finalizeIfDue(contest);
            scores.rebuild(contest,scoreCommand(scores,contest),99,false);scores.finish(contest,scoreCommand(scores,contest),99);
            Map<String,Object> scoreboard=scores.snapshot(contest,0,20,null,10,true);
            if(!Boolean.TRUE.equals(scoreboard.get("final"))) { throw new AssertionError("Final scoreboard missing"); }
            Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("scope","H2 transactions + native javac/java; manual outbox dispatch; not MySQL/MQ/HTTP acceptance");
            evidence.put("scoreboard",scoreboard);evidence.put("registration",registration.get("status"));evidence.put("executionStatus",repo.find(id,false).getExecutionStatus());evidence.put("verdict",verdict.getVerdict());
            evidence.put("submissions",jdbc.queryForObject("SELECT COUNT(*) FROM submission_execution",Integer.class));evidence.put("attempts",jdbc.queryForObject("SELECT COUNT(*) FROM judge_attempt",Integer.class));
            evidence.put("resultEvents",jdbc.queryForObject("SELECT COUNT(*) FROM reliable_outbox WHERE eventType='JUDGE_RESULT'",Integer.class));evidence.put("remainingCapacity",jdbc.queryForObject("SELECT remainingCapacity FROM contest WHERE id=?",Integer.class,contest));
            evidence.put("wallMs",verdict.getTime());evidence.put("memoryKiB",verdict.getMemory());evidence.put("memoryMeasurement",verdict.getMemoryMeasurement());System.out.println(JSONUtil.toJsonStr(evidence));
        }
    }
    static ScoreboardCommand scoreCommand(ScoreboardTransactions scores,long contest) {
        Map<String,Object> view=scores.snapshot(contest,0,20,null,99,true);ScoreboardCommand c=new ScoreboardCommand();
        c.setExpectedVersion(((Number)view.get("contestVersion")).longValue());c.setExpectedProjectionVersion(((Number)view.get("projectionVersion")).longValue());c.setExpectedRevision(((Number)view.get("revision")).longValue());c.setReason("verified local native contest result");return c;
    }
    static String utc(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC).toString(); }
}
