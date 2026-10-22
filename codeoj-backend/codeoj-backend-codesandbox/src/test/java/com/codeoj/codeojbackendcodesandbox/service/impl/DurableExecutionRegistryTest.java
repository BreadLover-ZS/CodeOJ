package com.codeoj.codeojbackendcodesandbox.service.impl;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DurableExecutionRegistryTest {
    @TempDir Path root;
    final AtomicBoolean unavailable = new AtomicBoolean();
    final List<String> removed = new CopyOnWriteArrayList<>();
    final IsolationEngine engine = token -> {
        if (unavailable.get()) { throw new IOException("daemon unreachable"); }
        removed.add(token);
    };
    private DurableExecutionRegistry registry() throws Exception { return new DurableExecutionRegistry(root, 1, "fixed-policy", engine); }
    private ExecuteCodeRequest request() {
        return ExecuteCodeRequest.builder().executionToken(UUID.randomUUID().toString())
                .executionDeadlineEpochMs(System.currentTimeMillis() + 30000).language("java").code("source")
                .inputList(Collections.singletonList("private-input")).build();
    }
    private ExecuteCodeResponse ok() { return ExecuteCodeResponse.builder().outcome(SandboxOutcome.EXECUTED).status(1).outputList(Collections.singletonList("3")).build(); }

    @Test void duplicateTokenAcrossManagersExecutesOnlyOnceAndSurvivesReopen() throws Exception {
        DurableExecutionRegistry a = registry(), b = registry(); ExecuteCodeRequest request = request();
        AtomicInteger count = new AtomicInteger(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ExecuteCodeResponse> first = pool.submit(() -> a.execute(request, () -> { count.incrementAndGet(); started.countDown(); release.await(); return ok(); }));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            Future<ExecuteCodeResponse> duplicate = pool.submit(() -> b.execute(request, () -> { count.incrementAndGet(); return ok(); }));
            release.countDown();
            assertEquals(SandboxOutcome.EXECUTED, first.get().getOutcome());
            assertEquals(SandboxOutcome.EXECUTED, duplicate.get().getOutcome());
            assertEquals(SandboxOutcome.EXECUTED, registry().execute(request, () -> { count.incrementAndGet(); return ok(); }).getOutcome());
            assertEquals(1, count.get());
            assertFalse(new String(Files.readAllBytes(root.resolve("execution-" + request.getExecutionToken() + ".json")), StandardCharsets.UTF_8).contains("private-input"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void separateManagersShareOnePhysicalSlot() throws Exception {
        DurableExecutionRegistry a = registry(), b = registry(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ExecuteCodeResponse> first = pool.submit(() -> a.execute(request(), () -> { started.countDown(); release.await(); return ok(); }));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            ExecuteCodeResponse blocked = b.execute(request(), () -> { fail("physical capacity exceeded"); return ok(); });
            assertEquals("EXECUTION_CAPACITY_UNAVAILABLE", blocked.getErrorCode());
            String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            String classes = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            Process observer = new ProcessBuilder(java, "-cp", classes, LockProbe.class.getName(), root.resolve("slot-0.lock").toString()).start();
            assertTrue(observer.waitFor(3, TimeUnit.SECONDS));
            byte[] bytes = new byte[32]; int length = observer.getInputStream().read(bytes);
            assertEquals("BUSY", new String(bytes, 0, length, StandardCharsets.UTF_8),
                    "Closing a competing channel must not release the owner's POSIX lock");
            release.countDown(); assertEquals(SandboxOutcome.EXECUTED, first.get().getOutcome());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void contentConflictAndExpiredIdentityCannotExecute() throws Exception {
        DurableExecutionRegistry registry = registry(); ExecuteCodeRequest request = request();
        assertEquals(SandboxOutcome.EXECUTED, registry.execute(request, this::ok).getOutcome());
        request.setCode("changed");
        assertEquals("EXECUTION_TOKEN_CONTENT_CONFLICT", registry.execute(request, this::ok).getErrorCode());
        ExecuteCodeRequest expired = request(); expired.setExecutionDeadlineEpochMs(System.currentTimeMillis() - 1);
        assertEquals("EXECUTION_DEADLINE_EXPIRED", registry.execute(expired, () -> { fail(); return ok(); }).getErrorCode());
    }
    @Test void cleanupFailureKeepsSlotReservedUntilActualRemovalSucceeds() throws Exception {
        DurableExecutionRegistry registry = registry(); ExecuteCodeRequest first = request(); unavailable.set(true);
        assertEquals("EXECUTION_CLEANUP_PENDING", registry.execute(first, this::ok).getErrorCode());
        assertTrue(Files.exists(root.resolve("slot-0.json")));
        assertEquals("EXECUTION_REGISTRY_UNAVAILABLE", registry.execute(request(), () -> { fail(); return ok(); }).getErrorCode());
        assertTrue(Files.exists(root.resolve("slot-0.json")));
        unavailable.set(false);
        assertEquals(SandboxOutcome.EXECUTED, registry.execute(request(), this::ok).getOutcome());
        assertTrue(removed.contains(first.getExecutionToken()));
        assertFalse(Files.exists(root.resolve("slot-0.json")));
    }
    @Test void recoveryOfAbandonedSlotIsDurableAndDoesNotRerunOldToken() throws Exception {
        registry(); ExecuteCodeRequest request = request();
        DurableExecutionRegistry.Record record = new DurableExecutionRegistry.Record(); record.token = request.getExecutionToken();
        record.hash = cn.hutool.crypto.digest.DigestUtil.sha256Hex(JSONUtil.toJsonStr(request));
        record.slot = 0; record.deadline = request.getExecutionDeadlineEpochMs(); record.state = "RUNNING";
        byte[] data = JSONUtil.toJsonStr(record).getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("slot-0.json"), data);
        Files.write(root.resolve("execution-" + record.token + ".json"), data);
        unavailable.set(true); assertThrows(IOException.class, this::registry);
        assertTrue(Files.exists(root.resolve("slot-0.json")));
        unavailable.set(false); DurableExecutionRegistry restarted = registry();
        assertEquals("EXECUTION_OWNER_LOST", restarted.execute(request, () -> { fail(); return ok(); }).getErrorCode());
        assertFalse(Files.exists(root.resolve("slot-0.json")));
    }
    @Test void crashBetweenSlotJournalAndTokenJournalCannotRerunSameToken() throws Exception {
        DurableExecutionRegistry registry = registry(); ExecuteCodeRequest request = request();
        DurableExecutionRegistry.Record record = new DurableExecutionRegistry.Record(); record.token = request.getExecutionToken();
        record.hash = cn.hutool.crypto.digest.DigestUtil.sha256Hex(JSONUtil.toJsonStr(request));
        record.slot = 0; record.deadline = request.getExecutionDeadlineEpochMs(); record.state = "RUNNING";
        Files.write(root.resolve("slot-0.json"), JSONUtil.toJsonStr(record).getBytes(StandardCharsets.UTF_8));
        assertEquals("EXECUTION_OWNER_LOST", registry.execute(request, () -> { fail(); return ok(); }).getErrorCode());
    }
    @Test void differentCapacityOrEnginePolicyCannotShareTheSameAuthority() throws Exception {
        registry();
        assertThrows(IOException.class, () -> new DurableExecutionRegistry(root, 2, "fixed-policy", engine));
        assertThrows(IOException.class, () -> new DurableExecutionRegistry(root, 1, "other-daemon", engine));
    }
    @Test void corruptJournalFailsClosedInsteadOfFreeingCapacity() throws Exception {
        DurableExecutionRegistry registry = registry(); Files.write(root.resolve("slot-0.json"), new byte[]{1, 2, 3});
        assertEquals("EXECUTION_REGISTRY_UNAVAILABLE", registry.execute(request(), () -> { fail(); return ok(); }).getErrorCode());
        assertTrue(Files.exists(root.resolve("slot-0.json")));
    }
    public static class LockProbe {
        public static void main(String[] args) throws Exception {
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(Paths.get(args[0]), StandardOpenOption.WRITE)) {
                java.nio.channels.FileLock lock = channel.tryLock();
                System.out.print(lock == null ? "BUSY" : "ACQUIRED");
                if (lock != null) { lock.release(); }
            }
        }
    }
}
