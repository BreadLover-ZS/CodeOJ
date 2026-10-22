package com.codeoj.codeojbackendquestionservice.contest;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendquestionservice.submission.SubmissionConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.RedisConnection;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Review probes assert observed defects, not desired behavior. Real private Redis + H2; manual consumer calls. */
class RegistrationAuditProbeTest extends RegistrationRedisIntegrationTest {
    @Test void rollbackWithOrphanMasksMissingConfirmedIdentity() throws Exception {
        long id=active(2);
        RegistrationReservation early=raw(id,10); // Redis-only reservation at snapshot time.
        List<String> keys=RedisRegistrationLedger.keys(id);
        Map<String,byte[]> snapshot=new LinkedHashMap<>();
        try(RedisConnection c=redis.getConnectionFactory().getConnection()) {
            for(String k:keys) { snapshot.put(k,c.dump(k.getBytes(StandardCharsets.UTF_8))); }
        }
        RegistrationRequest confirmed=accept(id,11);
        assertTrue(transactions.confirm(confirmed.event()));service.synchronize(request(id,11));
        assertEquals(0,stock(id));assertEquals(1,count("contest_registration"));
        // Controlled rollback to the exact older Redis bytes; DB remains current.
        try(RedisConnection c=redis.getConnectionFactory().getConnection()) {
            for(String k:keys) { c.del(k.getBytes(StandardCharsets.UTF_8)); }
            for(Map.Entry<String,byte[]> e:snapshot.entrySet()) {
                if(e.getValue()!=null) { c.restore(e.getKey().getBytes(StandardCharsets.UTF_8),0,e.getValue()); }
            }
        }
        assertNull(ledger.user(id,11));
        assertEquals(1,count("contest_registration_request"));
        assertEquals(1,ledger.stats(id).getInt("count")); // Equal counts hide different identities.
        maintenance().maintain(id);
        assertTrue(transactions.settings(id).isReady());assertNull(transactions.settings(id).getIssue());
        assertNotNull(request(id,10));assertNull(ledger.user(id,11));
        RegistrationRequest late=accept(id,12);assertTrue(transactions.confirm(late.event()));
        assertTrue(transactions.confirm(request(id,10).event()));
        assertEquals("MANUAL_REVIEW",request(id,10).getStatus());
        assertEquals("CONFIRMED",request(id,12).getStatus());
        assertEquals(2,count("contest_registration")); // MySQL still prevents final overcapacity.
        System.out.println("AUDIT_PROBE "+JSONUtil.toJsonStr(ContestTransactions.map("name","rollback-orphan-cardinality","earlyRequest",early.getRequestId().toString(),"earlyStatus",request(id,10).getStatus(),"laterStatus",request(id,12).getStatus(),"capacity",2,"qualifications",count("contest_registration"),"result","DEFECT_REPRODUCED")));
    }
    @Test @Timeout(60) void admittedLedgerCanExceedReconciliationBound() {
        long id=active(10001);
        for(int i=0;i<10001;i++) {
            RegistrationSettings s=transactions.settings(id);s.setNow(LocalDateTime.now(ZoneOffset.UTC));
            ledger.reserve(s,1000L+i,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(id,s.getPublishedVersion()));
        }
        assertEquals(10001,ledger.stats(id).getInt("count"));
        ContestCoreTest.Clock.now=LocalDateTime.now(ZoneOffset.UTC);
        assertThrows(BusinessException.class,()->service.reconcile(id,command(transactions.settings(id).getRevision()),99));
        assertEquals("REDIS_PAUSED",transactions.settings(id).getMode());
        assertEquals("RECONCILIATION_INCOMPLETE",transactions.settings(id).getIssue());
        System.out.println("AUDIT_PROBE "+JSONUtil.toJsonStr(ContestTransactions.map("name","admission-versus-reconcile-bound","capacity",10001,"ledgerEntries",ledger.stats(id).getInt("count"),"mode",transactions.settings(id).getMode(),"result","BOUND_MISMATCH_REPRODUCED","scope","real Lua reservations; no 10001 DB/account or MQ acceptance claim")));
    }
    @Test void slowRequestIsClassifiedAsClockSkewWithoutClockDrift() throws Exception {
        long id=active(1);RegistrationSettings s=transactions.settings(id);
        Thread.sleep(2300);
        RedisRegistrationLedger.LedgerException error=assertThrows(RedisRegistrationLedger.LedgerException.class,()->ledger.reserve(s,10,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(id,s.getPublishedVersion())));
        assertEquals("CLOCK_SKEW",error.getCode());
        assertEquals("0",ledger.stats(id).getStr("open"));
        System.out.println("AUDIT_PROBE "+JSONUtil.toJsonStr(ContestTransactions.map("name","stale-clock-sample","delayMs",2300,"code",error.getCode(),"gate",ledger.stats(id).getStr("open"),"result","CONSERVATIVE_HOLD_OBSERVED","scope","settings clock frozen by H2 fixture; latency and drift indistinguishable in current predicate")));
    }
}
