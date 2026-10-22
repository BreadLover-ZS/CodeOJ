package com.codeoj.codeojbackendquestionservice.contest;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.entity.User;
import com.codeoj.codeojbackendserviceclient.service.UserFeignClient;
import com.codeoj.codeojbackendmodel.model.dto.question.*;
import com.codeoj.codeojbackendquestionservice.submission.*;
import com.codeoj.codeojbackendquestionservice.submission.outbox.OutboxStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual isolated Redis/Lua + H2 transactions; not MySQL/RabbitMQ acceptance or load testing. */
@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=RegistrationRedisIntegrationTest.Config.class)
@TestPropertySource(properties={"codeoj.registration.enabled=true","codeoj.scoreboard.enabled=true"}) @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named="codeoj.real-redis",matches="true") @Timeout(20)
class RegistrationRedisIntegrationTest {
    static class RedisHarness implements AutoCloseable {
        final Path dir;final int port;final String password=UUID.randomUUID().toString();Process process;
        RedisHarness() throws Exception { dir=Files.createTempDirectory("codeoj-registration-redis-");try(ServerSocket socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())) { port=socket.getLocalPort(); }start(); }
        void start() throws Exception {
            String binary=System.getProperty("codeoj.redis-server");if(binary==null) { throw new IllegalStateException("Provide -Dcodeoj.redis-server=/absolute/path/to/redis-server"); }
            process=new ProcessBuilder(binary,"--bind","127.0.0.1","--port",Integer.toString(port),"--requirepass",password,"--save","","--appendonly","yes","--appendfsync","always","--dir",dir.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("redis.log").toFile())).start();
            long deadline=System.nanoTime()+5000000000L;
            while(System.nanoTime()<deadline) { try(Socket socket=new Socket("127.0.0.1",port)) {
                socket.setSoTimeout(200);
                String auth="*2\r\n$4\r\nAUTH\r\n$"+password.length()+"\r\n"+password+"\r\n*1\r\n$4\r\nPING\r\n";
                socket.getOutputStream().write(auth.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
                java.io.BufferedReader input=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                if("+OK".equals(input.readLine()) && "+PONG".equals(input.readLine())) { return; }
                Thread.sleep(10);
            }catch(java.io.IOException starting) { if(!process.isAlive()) { throw new IllegalStateException(new String(Files.readAllBytes(dir.resolve("redis.log")),StandardCharsets.UTF_8)); }Thread.sleep(10); } }
            throw new IllegalStateException("TEST_REDIS_START_TIMEOUT");
        }
        void stop() throws Exception { if(process!=null) { process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS)) { process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS); } } }
        void restart() throws Exception { stop();start(); }
        public void close() throws Exception { stop();try(java.util.stream.Stream<Path> files=Files.walk(dir)) { for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator) { Files.deleteIfExists(p); } } }
    }
    @Configuration @Import(ContestCoreTest.Config.class)
    static class Config {
        @Bean(destroyMethod="close") RedisHarness harness() throws Exception { return new RedisHarness(); }
        @Bean LettuceConnectionFactory redisConnection(RedisHarness harness) {
            RedisStandaloneConfiguration connection=new RedisStandaloneConfiguration("127.0.0.1",harness.port);connection.setPassword(RedisPassword.of(harness.password));
            return new LettuceConnectionFactory(connection,LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ofMillis(100)).build());
        }
        @Bean StringRedisTemplate redis(LettuceConnectionFactory factory) { return new StringRedisTemplate(factory); }
        @Bean RedisRegistrationLedger ledger(StringRedisTemplate redis) { return new RedisRegistrationLedger(redis); }
        @Bean RegistrationTransactions registrationTransactions(JdbcTemplate j,ContestTransactions c,ExecutionRepository e) { return new RegistrationTransactions(j,c,e); }
        @Bean RegistrationService registrationService(ContestTransactions c,RegistrationTransactions t,RedisRegistrationLedger r,ExecutionRepository e,JdbcTemplate j) { return new RegistrationService(c,t,r,e,j); }
        @Bean com.codeoj.codeojbackendquestionservice.scoreboard.ScoreboardTransactions scores(JdbcTemplate j,ExecutionRepository e) { return new com.codeoj.codeojbackendquestionservice.scoreboard.ScoreboardTransactions(j,e); }
        @Bean OutboxStore outbox(JdbcTemplate j,ExecutionRepository e) { return new OutboxStore(j,e); }
    }
    @Autowired DataSource ds;@Autowired JdbcTemplate jdbc;@Autowired ContestTransactions contests;@Autowired RegistrationTransactions transactions;
    @Autowired RegistrationService service;@Autowired RedisRegistrationLedger ledger;@Autowired StringRedisTemplate redis;@Autowired RedisHarness harness;@Autowired OutboxStore outbox;
    LocalDateTime base;
    @BeforeEach void schema() throws Exception {
        ContestCoreTest seed=new ContestCoreTest();seed.ds=ds;seed.jdbc=jdbc;seed.schema();
        String sql=new String(Files.readAllBytes(Paths.get("..","mysql-init","migrations","V003__async_registration.sql")),StandardCharsets.UTF_8);
        try(Connection c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))); }
        String fenceSql=new String(Files.readAllBytes(Paths.get("..","mysql-init","migrations","V005__registration_recovery_fence.sql")),StandardCharsets.UTF_8);
        try(Connection c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new ByteArrayResource(fenceSql.getBytes(StandardCharsets.UTF_8))); }
        String scoreSql=new String(Files.readAllBytes(Paths.get("..","mysql-init","migrations","V004__contest_scoreboard.sql")),StandardCharsets.UTF_8).replace(" DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci","");
        try(Connection c=ds.getConnection()) { ScriptUtils.executeSqlScript(c,new ByteArrayResource(scoreSql.getBytes(StandardCharsets.UTF_8))); }
        try(RedisConnection c=redis.getConnectionFactory().getConnection()) { c.flushDb(); } // Only this harness's private server.
        base=LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);ContestCoreTest.Clock.now=base;
    }
    String key="stable-registration-key";
    String utc(LocalDateTime t) { return t.toInstant(ZoneOffset.UTC).toString(); }
    ContestCommand command(long version) { ContestCommand c=new ContestCommand();c.setExpectedVersion(version);c.setReason("controlled local functional test");return c; }
    long published(int capacity) {
        ContestDraft d=new ContestDraft();d.setTitle("Async registration");d.setContestType("PRIVATE");d.setCapacity(capacity);
        d.setRegistrationOpenAt(utc(base.minusMinutes(1)));d.setRegistrationCloseAt(utc(base.plusMinutes(1)));d.setStartAt(utc(base.plusMinutes(2)));d.setEndAt(utc(base.plusMinutes(3)));
        ContestDraft.Problem p=new ContestDraft.Problem();p.setProblemCode("A");p.setTitle("sum");p.setContent("private statement");JudgeConfig config=new JudgeConfig();config.setTimeLimit(1000L);config.setMemoryLimit(131072L);p.setLimits(config);
        JudgeCase sample=new JudgeCase();sample.setInput("secret input");sample.setOutput("secret output");p.setCases(Collections.singletonList(sample));d.setProblems(Collections.singletonList(p));
        ContestCoreTest.Clock.now=base.minusMinutes(2);long id=Long.parseLong((String)contests.create(d,99).get("id"));contests.publish(id,command(0),99);ContestCoreTest.Clock.now=base;return id;
    }
    long active(int capacity) { long id=published(capacity);service.prepare(id,command(1),99);service.reconcile(id,command(2),99);return id; }
    RegistrationRequest request(long contest,long user) { long id=jdbc.queryForObject("SELECT id FROM contest_registration_request WHERE contestId=? AND userId=?",Long.class,contest,user);return transactions.request(contest,id,false); }
    RegistrationReservation raw(long contest,long user) { RegistrationSettings s=transactions.settings(contest);return ledger.reserve(s,user,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(contest,s.getPublishedVersion())); }
    RegistrationRequest accept(long contest,long user) { Map<String,Object> result=service.register(contest,key,user);assertEquals("PROCESSING",result.get("status"));assertEquals(false,result.get("qualificationValid"));return request(contest,user); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    int stock(long contest) { return ledger.stats(contest).getInt("stock"); }
    RegistrationMaintenance maintenance() { return new RegistrationMaintenance(jdbc,transactions,service,ledger); }

    @Test void reservationAndOutboxAreDurableBeforeQualificationAndDuplicateConfirmationIsHarmless() {
        long id=active(1);RegistrationRequest q=accept(id,10);assertEquals(0,count("contest_registration"));assertEquals(1,count("reliable_outbox"));assertEquals(0,stock(id));
        assertEquals(q.getReservation().getRequestId().toString(),service.register(id,key,10).get("requestId"));assertTrue(transactions.confirm(q.event()));assertTrue(transactions.confirm(q.event()));
        service.synchronize(transactions.request(id,q.getReservation().getRequestId(),false));assertEquals(1,count("contest_registration"));assertEquals("CONFIRMED",ledger.user(id,10).getState());assertEquals(0,stock(id));
        String json=jdbc.queryForObject("SELECT payload FROM reliable_outbox",String.class);assertFalse(json.contains("secret"));assertFalse(json.contains("requestKey"));
        System.out.println("REGISTRATION_LOCAL_SAMPLE "+JSONUtil.toJsonStr(ContestTransactions.map("scope","real isolated Redis/Lua + H2; manual consumer invocation; no MySQL/RabbitMQ acceptance","status",request(id,10).getStatus(),"remainingRedis",stock(id),"requests",count("contest_registration_request"),"qualifications",count("contest_registration"),"outboxEvents",count("reliable_outbox"))));
    }
    @Test void reservedBeforeDatabaseCrashIsRebuiltWithOriginalIdentity() {
        long id=active(2);RegistrationReservation r=raw(id,10);assertEquals(0,count("contest_registration_request"));maintenance().maintain(id);
        RegistrationRequest q=request(id,10);assertEquals(r.getRequestId(),q.getReservation().getRequestId());assertEquals(r.getEventId(),q.event().getEventId());assertEquals(r.getReservedAtMicros(),q.getReservation().getReservedAtMicros());assertEquals(1,count("reliable_outbox"));
    }
    @Test void outboxWriteFailureRollsBackDatabaseButNeverReleasesAnUnknownReservation() {
        long id=active(1);jdbc.execute("ALTER TABLE reliable_outbox ADD CONSTRAINT no_events CHECK(eventType='NEVER')");assertThrows(BusinessException.class,()->service.register(id,key,10));
        assertEquals(0,count("contest_registration_request"));assertEquals(0,stock(id));RegistrationReservation r=ledger.user(id,10);assertNotNull(r);
        jdbc.execute("ALTER TABLE reliable_outbox DROP CONSTRAINT no_events");maintenance().maintain(id);assertEquals(r.getRequestId(),request(id,10).getReservation().getRequestId());assertEquals(1,count("reliable_outbox"));
    }
    @Test void redisAofRestartPreservesAnUnpersistedReservation() throws Exception {
        long id=active(1);RegistrationReservation r=raw(id,10);harness.restart();redis.getConnectionFactory().getConnection().close();
        assertEquals(r.getEventId(),ledger.user(id,10).getEventId());maintenance().maintain(id);assertEquals(r.getRequestId(),request(id,10).getReservation().getRequestId());assertEquals(0,stock(id));
    }
    @Test void redisCompletionMarkerCanRetryAfterDatabaseMarkerWriteFails() {
        long id=active(1);RegistrationRequest q=accept(id,10);transactions.confirm(q.event());q=transactions.request(id,q.getReservation().getRequestId(),false);
        jdbc.execute("ALTER TABLE contest_registration_request ADD CONSTRAINT no_complete CHECK(reclaimState<>'COMPLETE')");RegistrationRequest confirmed=q;
        assertThrows(RuntimeException.class,()->service.synchronize(confirmed));assertEquals("CONFIRMED",ledger.user(id,10).getState());assertEquals("CONFIRMED",request(id,10).getStatus());assertEquals("RESERVATION_SYNC_UNAVAILABLE",request(id,10).getLastError());assertTrue(request(id,10).getNextActionAt().isAfter(ContestCoreTest.Clock.now));
        jdbc.execute("ALTER TABLE contest_registration_request DROP CONSTRAINT no_complete");service.synchronize(request(id,10));assertEquals("COMPLETE",request(id,10).getReclaimState());assertNull(request(id,10).getLastError());assertEquals(0,stock(id));
    }
    RegistrationReview review(RegistrationRequest q,String decision) { RegistrationReview c=new RegistrationReview();c.setExpectedVersion(q.getVersion());c.setReason("reviewed original reservation evidence");c.setDecision(decision);return c; }
    @Test void terminalStatePrecedesReleaseAndLateConsumerCannotConfirmReleasedRequest() {
        long id=active(1);RegistrationRequest q=accept(id,10);transactions.review(id,q.getReservation().getRequestId(),review(q,"FAIL"),99);
        assertEquals("FAILED",request(id,10).getStatus());assertEquals(0,stock(id));assertTrue(transactions.confirm(q.event()));assertEquals(0,count("contest_registration"));
        service.synchronize(request(id,10));service.synchronize(request(id,10));assertEquals(1,stock(id));assertEquals("RELEASED",ledger.user(id,10).getState());
        assertEquals("FAILED",service.register(id,key,10).get("status"));assertThrows(SubmissionConflictException.class,()->service.register(id,"another-request-key",10));assertEquals(1,stock(id));
    }
    @Test void releaseMarkerFailureDoesNotIncreaseStockTwice() {
        long id=active(1);RegistrationRequest q=accept(id,10);transactions.review(id,q.getReservation().getRequestId(),review(q,"FAIL"),99);
        jdbc.execute("ALTER TABLE contest_registration_request ADD CONSTRAINT no_release CHECK(reclaimState<>'RELEASED')");assertThrows(RuntimeException.class,()->service.synchronize(request(id,10)));assertEquals(1,stock(id));
        jdbc.execute("ALTER TABLE contest_registration_request DROP CONSTRAINT no_release");service.synchronize(request(id,10));assertEquals(1,stock(id));assertEquals("RELEASED",request(id,10).getReclaimState());
    }
    @Test void cancellationRecoversPreviouslyUnknownReservationAsCancelledAndReleasesOnce() {
        long id=active(1);RegistrationReservation r=raw(id,10);contests.cancel(id,command(4),99);maintenance().maintain(id);
        assertEquals(r.getRequestId(),request(id,10).getReservation().getRequestId());assertEquals("CANCELLED",request(id,10).getStatus());assertEquals(false,service.status(id,10).get("qualificationValid"));assertEquals(1,stock(id));assertEquals(0,count("contest_registration"));
    }
    @Test void cancellationAndConfirmationRaceNeverResellsConfirmedQualification() throws Exception {
        long id=active(1);RegistrationRequest q=accept(id,10);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<?> a=pool.submit(()->{try { start.await();transactions.confirm(q.event()); }catch(InterruptedException e) { throw new RuntimeException(e); }});
            Future<?> b=pool.submit(()->{try { start.await();contests.cancel(id,command(4),99); }catch(InterruptedException e) { throw new RuntimeException(e); }});
            start.countDown();a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);maintenance().maintain(id);
            assertEquals(false,service.status(id,10).get("qualificationValid"));assertEquals(1,stock(id)+count("contest_registration"));
        } finally { pool.shutdownNow(); }
    }
    @Test void eightUsersContendingForTwoSlotsProduceOnlyTwoRequestsAndQualifications() throws Exception {
        long id=active(2);ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
        try {
            for(long user=10;user<18;user++) { final long actor=user;results.add(pool.submit(()->{start.await();try { service.register(id,key,actor);return true; }catch(SubmissionConflictException full) { return false; }})); }
            start.countDown();int accepted=0;for(Future<Boolean> f:results) { if(f.get(10,TimeUnit.SECONDS)) { accepted++; } }assertEquals(2,accepted);assertEquals(2,count("contest_registration_request"));
            for(long request:jdbc.queryForList("SELECT id FROM contest_registration_request",Long.class)) { transactions.confirm(transactions.request(id,request,false).event()); }assertEquals(2,count("contest_registration"));assertEquals(0,stock(id));
        } finally { pool.shutdownNow(); }
    }
    @Test void delayedConfirmationUsesOriginalReservationTimeEvenAfterContestEnd() {
        long id=active(1);RegistrationRequest q=accept(id,10);ContestCoreTest.Clock.now=base.plusMinutes(3).plusSeconds(1);contests.finalizeIfDue(id);
        assertTrue(transactions.confirm(q.event()));assertEquals("CONFIRMED",request(id,10).getStatus());assertEquals(1,count("contest_registration"));
    }
    @Test void unknownEarlierPublishAndLaterNackCannotFreeReservation() {
        long id=active(1);RegistrationRequest q=accept(id,10);OutboxStore.Publication a=outbox.claim(q.event().getEventId(),"REGISTRATION_CONFIRM");
        ContestCoreTest.Clock.now=base.plusSeconds(16);OutboxStore.Publication b=outbox.claim(q.event().getEventId(),"REGISTRATION_CONFIRM");outbox.record(b.getEventId(),b.getToken(),"NACK",false,"BROKER_NACK");
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT confirmState FROM publish_attempt WHERE attemptToken=?",String.class,a.getToken()));transactions.recover(id,q.getReservation().getRequestId());assertEquals("PROCESSING",request(id,10).getStatus());assertEquals(0,stock(id));
    }
    @Test void exhaustedConfirmationNeedsExplicitFiniteAdminExtensionAndKeepsOriginalEvent() {
        long id=active(1);RegistrationRequest q=accept(id,10);ContestCoreTest.Clock.now=base.plusMinutes(6);transactions.recover(id,q.getReservation().getRequestId());RegistrationRequest held=request(id,10);
        assertEquals("MANUAL_REVIEW",held.getStatus());assertEquals(0,stock(id));assertTrue(transactions.confirm(q.event()));assertEquals(0,count("contest_registration"));
        RegistrationReview c=review(held,"CONTINUE");assertThrows(SubmissionConflictException.class,()->transactions.review(id,q.getReservation().getRequestId(),c,99));c.setExtendRecoverySeconds(60);transactions.review(id,q.getReservation().getRequestId(),c,99);
        assertEquals(q.event().getEventId(),request(id,10).event().getEventId());transactions.confirm(q.event());assertEquals("CONFIRMED",request(id,10).getStatus());
    }
    @Test void confirmedRequestCannotBeManuallyReleased() {
        long id=active(1);RegistrationRequest q=accept(id,10);transactions.confirm(q.event());RegistrationRequest confirmed=request(id,10);
        assertThrows(SubmissionConflictException.class,()->transactions.review(id,q.getReservation().getRequestId(),review(confirmed,"FAIL"),99));assertEquals(0,stock(id));
    }
    @Test void partialLedgerLossPausesGloballyAndCannotResetStock() {
        long id=active(1);RegistrationRequest q=accept(id,10);redis.opsForHash().delete(RedisRegistrationLedger.keys(id).get(3),q.event().getEventId());
        assertThrows(RedisRegistrationLedger.LedgerException.class,()->maintenance().maintain(id));assertFalse(transactions.settings(id).isReady());assertEquals("REDIS_PAUSED",transactions.settings(id).getMode());
        long version=transactions.settings(id).getRevision();assertThrows(BusinessException.class,()->service.reconcile(id,command(version),99));assertEquals("0",redis.opsForValue().get(RedisRegistrationLedger.keys(id).get(1)));assertEquals("PROCESSING",service.status(id,10).get("status"));
    }
    @Test void completeRedisLossCannotBeMisreportedAsNoReservationOrSilentlyReinitialized() {
        long id=active(1);accept(id,10);try(RedisConnection c=redis.getConnectionFactory().getConnection()) { c.flushDb(); }
        assertThrows(BusinessException.class,()->service.status(id,11));assertEquals("PROCESSING",service.status(id,10).get("status"));
        long version=transactions.settings(id).getRevision();assertThrows(BusinessException.class,()->service.reconcile(id,command(version),99));assertFalse(redis.hasKey(RedisRegistrationLedger.keys(id).get(1)));assertFalse(transactions.settings(id).isReady());
    }
    @Test void cutoverPreservesExistingSynchronousQualificationsAndClosesOldAllocator() {
        long id=published(2);contests.register(id,key,10);service.prepare(id,command(1),99);assertThrows(SubmissionConflictException.class,()->contests.register(id,key,11));service.reconcile(id,command(2),99);
        assertEquals(1,stock(id));assertEquals("CONFIRMED",service.register(id,key,10).get("status"));RegistrationRequest q=accept(id,11);transactions.confirm(q.event());assertEquals(2,count("contest_registration"));assertEquals(0,stock(id));
    }
    @Test void initializationRetryNeverResetsStockAndReconcileSubtractsPendingReservations() {
        long id=active(2);RegistrationReservation r=raw(id,10);ledger.initialize(transactions.settings(id));assertEquals(1,stock(id));
        service.reconcile(id,command(4),99);assertEquals(1,stock(id));assertEquals(r.getRequestId(),request(id,10).getReservation().getRequestId());assertTrue(transactions.settings(id).isReady());
    }
    @Test void lateGateOpenCannotOverrideNewerCancellationFence() {
        long id=active(1);RegistrationSettings old=transactions.settings(id);contests.cancel(id,command(4),99);RegistrationSettings cancelled=transactions.settings(id);ledger.gate(cancelled,false,false);
        assertThrows(RedisRegistrationLedger.LedgerException.class,()->ledger.gate(old,true,false));assertEquals("0",ledger.stats(id).getStr("open"));
    }
    @Test void clockSkewInEitherDirectionPausesBeforeAnyReservation() {
        for(int sign:new int[]{-1,1}) {
            long id=active(1);ContestCoreTest.Clock.now=base.plusSeconds(sign*10L);assertThrows(BusinessException.class,()->service.register(id,key,10));
            assertFalse(transactions.settings(id).isReady());assertEquals(0,ledger.stats(id).getInt("count"));assertEquals("0",ledger.stats(id).getStr("open"));ContestCoreTest.Clock.now=base;
        }
    }
    @Test void requestKeysArePerContestAndTerminalMappingsHaveNoExpiry() {
        long first=active(1),second=active(1);accept(first,10);accept(second,10);assertEquals(2,count("contest_registration_request"));
        for(String k:RedisRegistrationLedger.keys(first)) { assertEquals(-1L,redis.getExpire(k)); }
        assertThrows(SubmissionConflictException.class,()->service.register(first,"different-key",10));
    }
    @Test void staleAccountAndStaleAdminAreCheckedAgainstCurrentDatabase() {
        long id=active(1);RegistrationRequest q=accept(id,10);jdbc.update("UPDATE `user` SET userRole='ban' WHERE id=10");transactions.confirm(q.event());service.synchronize(request(id,10));assertEquals("FAILED",request(id,10).getStatus());assertEquals(1,stock(id));
        jdbc.update("UPDATE `user` SET userRole='user' WHERE id=99");long version=transactions.settings(id).getRevision();assertThrows(BusinessException.class,()->service.reconcile(id,command(version),99));
    }
    @Test void forgedEventCannotConfirmAnotherRequestOrPublicationVersion() {
        long id=active(1);RegistrationRequest q=accept(id,10);RegistrationEvent forged=q.event();forged.setPublishedVersion(99L);assertFalse(transactions.confirm(forged));assertEquals(0,count("contest_registration"));assertEquals("PROCESSING",request(id,10).getStatus());
    }
    @Test void wrongUserMappingCannotReturnAnotherUsersReservationOrPassReconciliation() {
        long id=active(2);RegistrationRequest a=accept(id,10),b=accept(id,11);
        redis.opsForHash().put(RedisRegistrationLedger.keys(id).get(2),"10",b.event().getEventId());redis.opsForHash().put(RedisRegistrationLedger.keys(id).get(2),"11",a.event().getEventId());
        assertThrows(RedisRegistrationLedger.LedgerException.class,()->ledger.user(id,10));assertThrows(BusinessException.class,()->service.reconcile(id,command(4),99));assertFalse(transactions.settings(id).isReady());assertEquals(0,count("contest_registration"));
    }
    @Test void internallyConsistentOldRedisSnapshotStillCannotOmitKnownDatabaseRequests() {
        long id=active(2);accept(id,10);RegistrationRequest q=accept(id,11);List<String> keys=RedisRegistrationLedger.keys(id);
        redis.opsForHash().delete(keys.get(3),q.event().getEventId());redis.opsForHash().delete(keys.get(2),"11");redis.opsForZSet().remove(keys.get(4),q.event().getEventId());redis.opsForHash().increment(keys.get(0),"reservedCount",-1);redis.opsForHash().increment(keys.get(0),"pendingCount",-1);redis.opsForValue().set(keys.get(1),"1");
        assertThrows(RedisRegistrationLedger.LedgerException.class,()->maintenance().maintain(id));assertFalse(transactions.settings(id).isReady());assertEquals(2,count("contest_registration_request"));
    }
    @Test void manualPublishExtensionPreservesAllOldAttemptEvidence() {
        long id=active(1);RegistrationRequest q=accept(id,10);
        for(int i=0;i<20;i++) { ContestCoreTest.Clock.now=base.plusSeconds(i*2L);OutboxStore.Publication p=outbox.claim(q.event().getEventId(),"REGISTRATION_CONFIRM");assertNotNull(p);outbox.record(p.getEventId(),p.getToken(),"NACK",false,"BROKER_NACK"); }
        transactions.recover(id,q.getReservation().getRequestId());RegistrationRequest held=request(id,10);assertEquals("MANUAL_REVIEW",held.getStatus());RegistrationReview resume=review(held,"CONTINUE");
        assertThrows(SubmissionConflictException.class,()->transactions.review(id,q.getReservation().getRequestId(),resume,99));resume.setAdditionalPublishAttempts(1);transactions.review(id,q.getReservation().getRequestId(),resume,99);
        assertNotNull(outbox.claim(q.event().getEventId(),"REGISTRATION_CONFIRM"));assertEquals(21,count("publish_attempt"));assertEquals(0,stock(id));
    }
    @Test void luaRejectsBothBeforeOpenAndAtOrAfterCloseWithoutChangingInventory() {
        long id=active(1);RegistrationSettings s=transactions.settings(id);s.setInitialized(false);s.setContestId(IdWorker.getId());s.setOpen(base.minusMinutes(2));s.setClose(base.minusMinutes(1));ledger.initialize(s);ledger.gate(s,true,false);
        RedisRegistrationLedger.LedgerException closed=assertThrows(RedisRegistrationLedger.LedgerException.class,()->ledger.reserve(s,10,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(s.getContestId(),s.getPublishedVersion())));assertEquals("REGISTRATION_WINDOW_CLOSED",closed.getCode());assertEquals(1,stock(s.getContestId()));
        s.setContestId(IdWorker.getId());s.setOpen(base.plusMinutes(1));s.setClose(base.plusMinutes(2));ledger.initialize(s);ledger.gate(s,true,false);
        assertThrows(RedisRegistrationLedger.LedgerException.class,()->ledger.reserve(s,10,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(s.getContestId(),s.getPublishedVersion())));assertEquals(0,ledger.stats(s.getContestId()).getInt("count"));
    }
    @Test void adminHttpContractUsesFreshRolesAndExposesEvidenceWithoutPrivateProblemData() throws Exception {
        long id=active(2);RegistrationRequest q=accept(id,10);OutboxStore.Publication publication=outbox.claim(q.event().getEventId(),"REGISTRATION_CONFIRM");assertNotNull(publication);
        UserFeignClient users=mock(UserFeignClient.class);User user=new User();user.setId(10L);user.setUserRole("admin");when(users.getLoginUser(any())).thenReturn(user);
        RegistrationAdminController controller=new RegistrationAdminController(service,transactions,new ExecutionRepository(jdbc),users,jdbc);
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ExecutionRpcAdvice(),new SubmissionConflictAdvice()).build();
        String url="/contests/"+id+"/registration-admin";mvc.perform(get(url)).andExpect(status().isForbidden());user.setId(99L);
        String body=mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertTrue(body.contains("UNKNOWN"));assertFalse(body.contains("secret"));assertTrue(body.contains(q.getReservation().getRequestId().toString()));
        mvc.perform(post(url+"/requests/"+q.getReservation().getRequestId()+"/review").contentType("application/json").content(JSONUtil.toJsonStr(review(q,"FAIL")))).andExpect(status().isOk());assertEquals("FAILED",request(id,10).getStatus());assertEquals(1,stock(id));
        jdbc.update("UPDATE `user` SET userRole='user' WHERE id=99");mvc.perform(get(url)).andExpect(status().isForbidden());
    }
    @Test void lateRecoveryCannotGrantANewAutomaticDeadlineToAnOldReservation() {
        long id=active(1);RegistrationReservation r=raw(id,10);ContestCoreTest.Clock.now=base.plusMinutes(6);
        assertEquals("MANUAL_REVIEW",service.status(id,10).get("status"));RegistrationRequest q=request(id,10);assertEquals(r.getRequestId(),q.getReservation().getRequestId());assertTrue(q.getDeadline().isBefore(ContestCoreTest.Clock.now));assertTrue(outbox.due("REGISTRATION_CONFIRM").isEmpty());assertEquals(0,stock(id));assertEquals(0,count("contest_registration"));
    }
    @Autowired com.codeoj.codeojbackendquestionservice.scoreboard.ScoreboardTransactions scores;
    com.codeoj.codeojbackendquestionservice.scoreboard.ScoreboardCommand scoreCommand(long id) {
        Map<String,Object> view=scores.snapshot(id,0,20,null,99,true);
        com.codeoj.codeojbackendquestionservice.scoreboard.ScoreboardCommand c=new com.codeoj.codeojbackendquestionservice.scoreboard.ScoreboardCommand();
        c.setExpectedVersion(((Number)view.get("contestVersion")).longValue());c.setExpectedProjectionVersion(((Number)view.get("projectionVersion")).longValue());c.setExpectedRevision(((Number)view.get("revision")).longValue());c.setReason("verified real Redis finalizing ledger");return c;
    }
    @Test void finalizationFindsUndurableReservationAndRequiresClosedReconciledLedger() {
        long id=active(1);RegistrationReservation reservation=raw(id,10);assertEquals(0,count("contest_registration_request"));
        ContestCoreTest.Clock.now=base.plusMinutes(3);contests.finalizeIfDue(id);
        assertThrows(SubmissionConflictException.class,()->scores.rebuild(id,scoreCommand(id),99,false));
        service.reconcile(id,command(transactions.settings(id).getRevision()),99);
        assertEquals(reservation.getRequestId(),request(id,10).getReservation().getRequestId());assertEquals("0",ledger.stats(id).getStr("open"));
        assertThrows(SubmissionConflictException.class,()->scores.rebuild(id,scoreCommand(id),99,false));
        transactions.confirm(request(id,10).event());service.synchronize(request(id,10));
        service.reconcile(id,command(transactions.settings(id).getRevision()),99);assertEquals("0",ledger.stats(id).getStr("open"));
        scores.rebuild(id,scoreCommand(id),99,false);scores.finish(id,scoreCommand(id),99);
        Map<String,Object> finalBoard=scores.snapshot(id,0,20,null,99,true);assertEquals(true,finalBoard.get("final"));assertEquals(1L,finalBoard.get("total"));assertEquals("FINISHED",finalBoard.get("status"));
    }

    /** Executes the production Lua with exactly one injected runtime error, never a production fault flag. */
    class FaultLedger extends RedisRegistrationLedger {
        final String operation,marker;boolean armed=true;String proposedRequest,proposedEvent;
        FaultLedger(String operation,String marker) { super(redis);this.operation=operation;this.marker=marker; }
        @Override cn.hutool.json.JSONObject call(long contest,String... args) {
            if(armed && operation.equals(args[0])) {
                armed=false;
                if("reserve".equals(operation)) { proposedRequest=args[5];proposedEvent=args[6]; }
                try {
                    String source=org.springframework.util.StreamUtils.copyToString(new org.springframework.core.io.ClassPathResource("lua/contest-registration.lua").getInputStream(),StandardCharsets.UTF_8);
                    assertTrue(source.contains(marker));
                    source=source.replace(marker,marker+"\n error('CONTROLLED_REVIEW_FAULT')");
                    redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(source,String.class),RedisRegistrationLedger.keys(contest),(Object[])args);
                    throw new AssertionError("Fault was not reached");
                } catch(java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }
            return super.call(contest,args);
        }
    }
    RegistrationService facade(RedisRegistrationLedger custom) {
        RegistrationService result=new RegistrationService(contests,transactions,custom,new ExecutionRepository(jdbc),jdbc);
        ReflectionTestUtils.setField(result,"enabled",true);return result;
    }
    Map<String,byte[]> dump(long id) {
        Map<String,byte[]> snapshot=new LinkedHashMap<>();
        try(RedisConnection c=redis.getConnectionFactory().getConnection()) { for(String k:RedisRegistrationLedger.keys(id)) { snapshot.put(k,c.dump(k.getBytes(StandardCharsets.UTF_8))); } }return snapshot;
    }
    void restore(long id,Map<String,byte[]> snapshot) {
        try(RedisConnection c=redis.getConnectionFactory().getConnection()) {
            for(String k:RedisRegistrationLedger.keys(id)) { c.del(k.getBytes(StandardCharsets.UTF_8)); }
            for(Map.Entry<String,byte[]> e:snapshot.entrySet()) { if(e.getValue()!=null) { c.restore(e.getKey().getBytes(StandardCharsets.UTF_8),0,e.getValue()); } }
        }
    }
    @Test void oldSnapshotWithOrphanCannotMaskMissingConfirmedIdentity() {
        long id=active(2);RegistrationReservation early=raw(id,10);Map<String,byte[]> snapshot=dump(id);
        RegistrationRequest b=accept(id,11);transactions.confirm(b.event());service.synchronize(request(id,11));restore(id,snapshot);
        assertEquals(1,ledger.stats(id).getInt("count"));assertEquals(1,count("contest_registration_request"));
        RedisRegistrationLedger.LedgerException error=assertThrows(RedisRegistrationLedger.LedgerException.class,()->maintenance().maintain(id));
        assertEquals("LEDGER_ROLLBACK_DETECTED",error.getCode());assertFalse(transactions.settings(id).isReady());
        assertThrows(BusinessException.class,()->service.register(id,key,12));
        assertEquals(early.getEventId(),ledger.user(id,10).getEventId());assertEquals("CONFIRMED",request(id,11).getStatus());assertEquals(1,count("contest_registration"));
    }
    @Test void staleDatabaseSampleCannotReuseADurablyAssignedOrdinal() {
        long id=active(2);raw(id,10);Map<String,byte[]> snapshot=dump(id);RegistrationSettings stale=transactions.settings(id);
        RegistrationRequest b=accept(id,11);transactions.confirm(b.event());service.synchronize(request(id,11));restore(id,snapshot);
        RegistrationReservation late=ledger.reserve(stale,12,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(id,stale.getPublishedVersion()));
        assertEquals(b.getReservation().getSequence(),late.getSequence());
        assertThrows(BusinessException.class,()->service.persist(late));assertFalse(transactions.settings(id).isReady());
        assertEquals(1,count("contest_registration_request"));assertEquals(1,count("contest_registration"));
    }
    @Test void newContestCannotExceedTheSupportedClosureCapacity() {
        assertThrows(BusinessException.class,()->published(10001));assertEquals(0,count("contest"));
    }
    @Test @Timeout(60) void ledgerLimitCountsReleasedIdentitiesAndStillAllowsOriginalRetries() {
        long id=active(1);RegistrationSettings s=transactions.settings(id);RegistrationReservation original=null;
        // Lua contract fixture: each synthetic reservation is terminally released; not 10000 HTTP/MQ users.
        for(int i=0;i<10000;i++) {
            s.setNow(LocalDateTime.now(ZoneOffset.UTC));
            RegistrationReservation r=ledger.reserve(s,1000L+i,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(id,s.getPublishedVersion()));
            if(i==0) { original=r; }ledger.finish(r,"RELEASED");
        }
        assertEquals(10000,ledger.stats(id).getInt("count"));assertEquals(1,stock(id));s.setNow(LocalDateTime.now(ZoneOffset.UTC));
        RedisRegistrationLedger.LedgerException limit=assertThrows(RedisRegistrationLedger.LedgerException.class,()->ledger.reserve(s,99999,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(id,s.getPublishedVersion())));
        assertEquals("REGISTRATION_LEDGER_LIMIT",limit.getCode());assertEquals(1,stock(id));
        assertEquals(original.getEventId(),ledger.reserve(s,1000,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(id,s.getPublishedVersion())).getEventId());
        ledger.gate(s,false,false);assertEquals(10000,ledger.snapshot(id).size());
    }
    @Test void reservationRecoversEveryPartialWriteAndTheOriginalIdentity() {
        String[] markers={"-- reserve.write.intent","-- reserve.write.stock","-- reserve.write.user","-- reserve.write.ledger","-- reserve.write.pending","-- reserve.write.counters","-- mutation.clear"};
        for(String marker:markers) {
            long id=active(1);FaultLedger fault=new FaultLedger("reserve",marker);
            Map<String,Object> response=facade(fault).register(id,key,10);RegistrationRequest q=request(id,10);
            assertEquals("PROCESSING",response.get("status"));assertEquals(fault.proposedRequest,response.get("requestId"));assertEquals(fault.proposedEvent,q.event().getEventId());
            assertEquals(0,stock(id));assertFalse(redis.opsForHash().hasKey(RedisRegistrationLedger.keys(id).get(0),"mutation"));
            assertEquals(response.get("requestId"),service.register(id,key,10).get("requestId"));
            transactions.confirm(q.event());service.synchronize(request(id,10));assertEquals("CONFIRMED",request(id,10).getStatus());assertEquals(0,stock(id));
        }
        System.out.println("REGISTRATION_RECOVERY_CASE reservation-write-points=7 original-identity-and-single-allocation=PASS");
    }
    @Test void processLostAfterStockWriteCanRecoverThroughMaintenance() {
        long id=active(1);RegistrationSettings s=transactions.settings(id);FaultLedger fault=new FaultLedger("reserve","-- reserve.write.stock");
        assertThrows(RuntimeException.class,()->fault.reserve(s,10,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(id,s.getPublishedVersion())));
        assertEquals(0,count("contest_registration_request"));maintenance().maintain(id);
        assertEquals(fault.proposedRequest,request(id,10).getReservation().getRequestId().toString());assertEquals(0,stock(id));
        assertThrows(SubmissionConflictException.class,()->service.register(id,key,11));
    }
    @Test void releaseRecoversEveryPartialWriteWithoutASecondRefund() {
        String[] markers={"-- finish.write.intent","-- finish.write.stock","-- finish.write.ledger","-- finish.write.pending","-- finish.write.counters","-- mutation.clear"};
        for(String marker:markers) {
            long id=active(1);RegistrationRequest q=accept(id,10);transactions.review(id,q.getReservation().getRequestId(),review(q,"FAIL"),99);
            assertThrows(RuntimeException.class,()->facade(new FaultLedger("finish",marker)).synchronize(request(id,10)));
            ledger.repair(transactions.settings(id));service.synchronize(request(id,10));
            assertEquals("RELEASED",request(id,10).getReclaimState());assertEquals(1,stock(id));
            ledger.repair(transactions.settings(id));service.synchronize(request(id,10));assertEquals(1,stock(id));
        }
        System.out.println("REGISTRATION_RECOVERY_CASE release-write-points=6 no-double-refund=PASS");
    }
    @Test void confirmedMarkerFailureNeverRefundsAQualification() {
        long id=active(1);RegistrationRequest q=accept(id,10);transactions.confirm(q.event());
        assertThrows(RuntimeException.class,()->facade(new FaultLedger("finish","-- finish.write.ledger")).synchronize(request(id,10)));
        ledger.repair(transactions.settings(id));service.synchronize(request(id,10));assertEquals(0,stock(id));assertEquals("COMPLETE",request(id,10).getReclaimState());assertEquals(1,count("contest_registration"));
    }
    @Test void interruptedInitializationResumesWithoutResettingAnAllocatedStock() {
        for(String marker:new String[]{"-- init.write.intent","-- init.write.stock","-- mutation.clear"}) {
            long id=published(1);service.prepare(id,command(1),99);RegistrationSettings s=transactions.settings(id);
            assertThrows(RuntimeException.class,()->new FaultLedger("init",marker).initialize(s));
            service.reconcile(id,command(s.getRevision()),99);accept(id,10);assertEquals(0,stock(id));ledger.initialize(transactions.settings(id));assertEquals(0,stock(id));
        }
    }
    @Test void lostSuccessfulResponseReturnsTheOriginalRequestInsteadOfAllocatingAgain() {
        long id=active(1);
        RedisRegistrationLedger transport=new RedisRegistrationLedger(redis) {
            boolean once=true;
            @Override public RegistrationReservation reserve(RegistrationSettings s,long u,long r,String e,String k,String h) {
                RegistrationReservation accepted=super.reserve(s,u,r,e,k,h);if(once) { once=false;throw new org.springframework.data.redis.RedisConnectionFailureException("CONTROLLED_RESPONSE_LOSS"); }return accepted;
            }
        };
        Map<String,Object> response=facade(transport).register(id,key,10);
        assertEquals(request(id,10).getReservation().getRequestId().toString(),response.get("requestId"));assertEquals(1,count("contest_registration_request"));assertEquals(0,stock(id));
    }
    @Test void exceptionBeforeScriptExecutionDoesNotInventAnAcceptedRequest() {
        long id=active(1);
        RedisRegistrationLedger transport=new RedisRegistrationLedger(redis) {
            @Override public RegistrationReservation reserve(RegistrationSettings s,long u,long r,String e,String k,String h) { throw new org.springframework.data.redis.RedisConnectionFailureException("CONTROLLED_BEFORE_SEND"); }
        };
        assertThrows(BusinessException.class,()->facade(transport).register(id,key,10));assertEquals(1,stock(id));assertEquals(0,count("contest_registration_request"));
        accept(id,10);assertEquals(0,stock(id));assertEquals(1,count("contest_registration_request"));
    }
    @Test void missingScriptCacheIsRebuiltByTheRealRedisExecutor() {
        long id=active(1);try(RedisConnection c=redis.getConnectionFactory().getConnection()) { c.scriptFlush(); }
        accept(id,10);assertEquals(0,stock(id));assertEquals(1,count("contest_registration_request"));
    }
    @Test void wrongKeyTypeFailsBeforeDeductionAndDoesNotFabricateARefund() {
        long id=active(1);String pending=RedisRegistrationLedger.keys(id).get(4);redis.opsForValue().set(pending,"wrong-type");
        assertThrows(BusinessException.class,()->service.register(id,key,10));
        assertEquals("1",redis.opsForValue().get(RedisRegistrationLedger.keys(id).get(1)));assertEquals(0,count("contest_registration_request"));assertFalse(transactions.settings(id).isReady());
    }
    @Test void conflictingPartialWriteEvidenceStaysHeldForReview() {
        long id=active(1);RegistrationSettings s=transactions.settings(id);FaultLedger fault=new FaultLedger("reserve","-- reserve.write.stock");
        assertThrows(RuntimeException.class,()->fault.reserve(s,10,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(id,s.getPublishedVersion())));
        redis.opsForValue().set(RedisRegistrationLedger.keys(id).get(1),"2");
        assertThrows(BusinessException.class,()->service.status(id,10));assertFalse(transactions.settings(id).isReady());
        assertTrue(redis.opsForHash().hasKey(RedisRegistrationLedger.keys(id).get(0),"mutation"));assertEquals("2",redis.opsForValue().get(RedisRegistrationLedger.keys(id).get(1)));assertEquals(0,count("contest_registration_request"));
    }
    void legacy(long id) {
        String records=RedisRegistrationLedger.keys(id).get(3);
        for(Map.Entry<Object,Object> entry:redis.opsForHash().entries(records).entrySet()) {
            cn.hutool.json.JSONObject r=JSONUtil.parseObj(entry.getValue());r.remove("sequence");redis.opsForHash().put(records,entry.getKey(),r.toString());
        }
        redis.opsForHash().delete(RedisRegistrationLedger.keys(id).get(0),"format");jdbc.update("UPDATE contest_registration_request SET reservationSequence=NULL WHERE contestId=?",id);
    }
    @Test void legacyLedgerNeedsClosedIdentityReconciliationBeforeNewAdmission() {
        long id=active(3);RegistrationRequest a=accept(id,10),b=accept(id,11);transactions.confirm(b.event());service.synchronize(request(id,11));legacy(id);
        assertThrows(BusinessException.class,()->service.register(id,key,12));assertFalse(transactions.settings(id).isReady());
        service.reconcile(id,command(transactions.settings(id).getRevision()),99);assertTrue(transactions.settings(id).isReady());assertEquals("2",ledger.stats(id).getStr("format"));
        assertEquals(a.event(),request(id,10).event());assertNotNull(request(id,10).getReservation().getSequence());
        transactions.confirm(a.event());assertEquals("CONFIRMED",request(id,10).getStatus());accept(id,12);assertEquals(0,stock(id));
    }
    @Test void interruptedLegacyUpgradeReusesTheCommittedOrdinalAssignments() {
        long id=active(2);accept(id,10);accept(id,11);legacy(id);
        assertThrows(BusinessException.class,()->facade(new FaultLedger("upgrade","-- upgrade.write.record")).reconcile(id,command(transactions.settings(id).getRevision()),99));
        List<Long> assigned=jdbc.queryForList("SELECT reservationSequence FROM contest_registration_request WHERE contestId=? ORDER BY id",Long.class,id);
        assertTrue(assigned.stream().allMatch(Objects::nonNull));assertFalse(transactions.settings(id).isReady());
        service.reconcile(id,command(transactions.settings(id).getRevision()),99);
        assertEquals(assigned,jdbc.queryForList("SELECT reservationSequence FROM contest_registration_request WHERE contestId=? ORDER BY id",Long.class,id));assertTrue(transactions.settings(id).isReady());assertEquals(0,stock(id));
    }

    @Test void oldSnapshotCannotLeaveAnAlreadyReleasedDatabaseRequestHoldingStockForever() {
        long id=active(1);RegistrationRequest q=accept(id,10);Map<String,byte[]> snapshot=dump(id);
        transactions.review(id,q.getReservation().getRequestId(),review(q,"FAIL"),99);service.synchronize(request(id,10));assertEquals(1,stock(id));
        restore(id,snapshot);assertEquals(0,stock(id));maintenance().maintain(id);
        assertEquals(1,stock(id));assertEquals("RELEASED",ledger.user(id,10).getState());assertEquals("RELEASED",request(id,10).getReclaimState());
        maintenance().maintain(id);assertEquals(1,stock(id));
    }
    @Test void oldSnapshotCompletionMarkerCanBeReplayedFromTheDurableQualification() {
        long id=active(1);RegistrationRequest q=accept(id,10);Map<String,byte[]> snapshot=dump(id);
        transactions.confirm(q.event());service.synchronize(request(id,10));restore(id,snapshot);maintenance().maintain(id);
        assertEquals("CONFIRMED",ledger.user(id,10).getState());assertEquals(0,stock(id));assertEquals(1,count("contest_registration"));
    }

}
