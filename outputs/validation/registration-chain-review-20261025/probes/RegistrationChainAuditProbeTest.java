package com.codeoj.codeojbackendquestionservice.contest;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendquestionservice.submission.SubmissionConflictException;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Audit observations, not assertions that the two observed defects have been repaired. */
class RegistrationChainAuditProbeTest extends RegistrationRedisIntegrationTest {
    @Test void olderReconcileFailurePausesANewerSuccessfulReconciliation() throws Exception {
        long id=active(2);
        RegistrationRequest confirmed=accept(id,10);transactions.confirm(confirmed.event());service.synchronize(request(id,10));
        CountDownLatch opening=new CountDownLatch(1),resume=new CountDownLatch(1);
        AtomicBoolean once=new AtomicBoolean(true);
        AtomicReference<String> lateGateError=new AtomicReference<>();
        RedisRegistrationLedger delayed=new RedisRegistrationLedger(redis) {
            @Override public void gate(RegistrationSettings s,boolean open,boolean clearClockHold) {
                if(open && clearClockHold && once.compareAndSet(true,false)) {
                    opening.countDown();
                    try { if(!resume.await(10,TimeUnit.SECONDS)) { throw new IllegalStateException("AUDIT_GATE_TIMEOUT"); } }
                    catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
                }
                try { super.gate(s,open,clearClockHold); }
                catch(LedgerException error) { lateGateError.set(error.getCode());throw error; }
            }
        };
        RegistrationService olderService=facade(delayed);
        long originalRevision=transactions.settings(id).getRevision();
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<String> older=worker.submit(()->{
                try { olderService.reconcile(id,command(originalRevision),99);return "SUCCESS"; }
                catch(BusinessException failure) { return failure.getMessage(); }
            });
            assertTrue(opening.await(10,TimeUnit.SECONDS));
            RegistrationSettings middle=transactions.settings(id);
            assertTrue(middle.isReady());
            RegistrationSettings newer=service.reconcile(id,command(middle.getRevision()),99);
            assertTrue(newer.isReady());assertEquals("REDIS_READY",newer.getMode());
            assertEquals("1",ledger.stats(id).getStr("open"));
            assertEquals(true,service.status(id,10).get("qualificationValid"));
            resume.countDown();
            assertEquals("REGISTRATION_STATE_UNAVAILABLE",older.get(10,TimeUnit.SECONDS));
            RegistrationSettings after=transactions.settings(id);
            assertFalse(after.isReady());assertEquals("REDIS_PAUSED",after.getMode());
            assertEquals("RECONCILIATION_INCOMPLETE",after.getIssue());
            assertEquals("STALE_GATE_REVISION",lateGateError.get());
            assertTrue(after.getRevision()>newer.getRevision());
            assertThrows(SubmissionConflictException.class,()->service.register(id,key,11));
            assertEquals("CONFIRMED",service.status(id,10).get("status"));
            assertEquals(false,service.status(id,10).get("qualificationValid"));
            System.out.println("CHAIN_AUDIT_R1 "+JSONUtil.toJsonStr(ContestTransactions.map(
                    "newerCompletedRevision",newer.getRevision(),"newerCompletedReady",newer.isReady(),
                    "afterOlderFailureRevision",after.getRevision(),"afterOlderFailureReady",after.isReady(),
                    "afterOlderFailureMode",after.getMode(),"issue",after.getIssue(),
                    "lateGateError",lateGateError.get(),"redisGateBeforeNextTick",ledger.stats(id).getStr("open"),
                    "newRegistrationRejected",true,"confirmedUserQualificationValid",service.status(id,10).get("qualificationValid"))));
        } finally { resume.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)); }
    }

    @Test void pendingReservationIsStillAdvertisedAsRemainingCapacity() {
        long id=active(1);RegistrationRequest q=accept(id,10);
        Map<String,Object> publicDetail=contests.detail(id,11,false);
        assertEquals(1,publicDetail.get("remainingCapacity"));assertEquals(0,stock(id));
        assertEquals("PROCESSING",q.getStatus());assertEquals(0,count("contest_registration"));
        SubmissionConflictException full=assertThrows(SubmissionConflictException.class,()->service.register(id,key,11));
        assertEquals("CONTEST_FULL",full.getMessage());
        System.out.println("CHAIN_AUDIT_R2 "+JSONUtil.toJsonStr(ContestTransactions.map(
                "displayedRemainingCapacity",publicDetail.get("remainingCapacity"),"redisAvailable",stock(id),
                "pendingStatus",q.getStatus(),"confirmedQualifications",count("contest_registration"),
                "nextUserResult",full.getMessage())));
    }

    @Test void cancellationAfterPartialReservationRecoversIdentityAndRefundsOnlyOnce() {
        long id=active(1);RegistrationSettings s=transactions.settings(id);
        FaultLedger interrupted=new FaultLedger("reserve","-- reserve.write.stock");
        assertThrows(RuntimeException.class,()->interrupted.reserve(s,10,IdWorker.getId(),UUID.randomUUID().toString(),key,RegistrationTransactions.hash(id,s.getPublishedVersion())));
        assertEquals(0,count("contest_registration_request"));
        contests.cancel(id,command(s.getRevision()),99);
        maintenance().maintain(id);
        RegistrationRequest q=request(id,10);
        assertEquals(interrupted.proposedRequest,q.getReservation().getRequestId().toString());
        assertEquals("CANCELLED",q.getStatus());assertEquals("RELEASED",q.getReclaimState());
        assertEquals(1,stock(id));assertTrue(transactions.confirm(q.event()));
        maintenance().maintain(id);assertEquals(1,stock(id));assertEquals(0,count("contest_registration"));
        System.out.println("CHAIN_AUDIT_PROTECTION partialReserveThenCancel=PASS identityRetained=true refundCount=1 qualifications=0");
    }
}
