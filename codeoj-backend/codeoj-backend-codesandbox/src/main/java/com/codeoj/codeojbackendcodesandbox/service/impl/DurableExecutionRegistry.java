package com.codeoj.codeojbackendcodesandbox.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shared local-disk authority for managers using the same Docker daemon and storage root. */
final class DurableExecutionRegistry {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DurableExecutionRegistry.class);
    // POSIX may release all process locks when ANY channel for that inode closes.
    // FileLock recommends one persistent channel per file, shared by all managers/threads in this JVM.
    private static final ConcurrentMap<Path, FileChannel> CHANNELS = new ConcurrentHashMap<>();
    interface Work { ExecuteCodeResponse execute() throws Exception; }
    @lombok.Data
    public static class Record {
        public String token, hash, state;
        public long deadline;
        public int slot;
        public ExecuteCodeResponse response;
    }
    private final Path root;
    private final int slots;
    private final IsolationEngine engine;

    DurableExecutionRegistry(Path directory, int slots, String policy, IsolationEngine engine) throws IOException, InterruptedException {
        if (slots < 1 || slots > 16) { throw new IllegalArgumentException("INVALID_ISOLATION_SLOTS"); }
        Files.createDirectories(directory);
        this.root = directory.toRealPath(); this.slots = slots; this.engine = engine;
        try (Held ignored = gate()) {
            Path settings = root.resolve("policy");
            String expected = slots + ":" + policy;
            if (Files.exists(settings)) {
                if (!expected.equals(new String(Files.readAllBytes(settings), StandardCharsets.UTF_8))) {
                    throw new IOException("ISOLATION_POLICY_CONFLICT");
                }
            } else { atomic(settings, expected); }
        }
        recover();
    }

    ExecuteCodeResponse execute(ExecuteCodeRequest request, Work work) {
        String token = request == null ? null : request.getExecutionToken();
        Long deadline = request == null ? null : request.getExecutionDeadlineEpochMs();
        long now = System.currentTimeMillis();
        if (token == null || !token.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                || deadline == null || deadline > now + 60000) { return unavailable("INVALID_EXECUTION_IDENTITY"); }
        String hash = DigestUtil.sha256Hex(JSONUtil.toJsonStr(request));
        try {
            while (true) {
                Held slot = null; Record record = null;
                try (Held ignored = gate()) {
                    Record existing = read(recordPath(token));
                    if (existing != null) {
                        if (!hash.equals(existing.hash)) { return unavailable("EXECUTION_TOKEN_CONTENT_CONFLICT"); }
                        if (existing.response != null) { return existing.response; }
                        Held abandoned = lock("slot-" + existing.slot);
                        if (abandoned != null) {
                            try { reap(existing); }
                            finally { abandoned.close(); }
                            return unavailable("EXECUTION_OWNER_LOST");
                        }
                    } else {
                        if (deadline <= System.currentTimeMillis()) { return unavailable("EXECUTION_DEADLINE_EXPIRED"); }
                        collect();
                        if (records() >= 64) { return unavailable("EXECUTION_REGISTRY_CAPACITY"); }
                        for (int i = 0; i < slots; i++) {
                            Held candidate = lock("slot-" + i);
                            if (candidate == null) { continue; }
                            try {
                                Record old = read(slotPath(i));
                                if (old != null) {
                                    reap(old);
                                    if (token.equals(old.token)) {
                                        candidate.close();
                                        return hash.equals(old.hash) ? old.response : unavailable("EXECUTION_TOKEN_CONTENT_CONFLICT");
                                    }
                                }
                                record = new Record(); record.token = token; record.hash = hash;
                                record.deadline = deadline; record.slot = i; record.state = "RUNNING";
                                save(slotPath(i), record); save(recordPath(token), record);
                                slot = candidate; break;
                            } catch (Exception failure) { candidate.close(); throw failure; }
                        }
                        if (slot == null) { return unavailable("EXECUTION_CAPACITY_UNAVAILABLE"); }
                    }
                }
                if (slot != null) { return run(record, slot, work); }
                if (System.currentTimeMillis() >= deadline) { return unavailable("EXECUTION_IN_PROGRESS"); }
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return unavailable("EXECUTION_INTERRUPTED");
        } catch (Exception e) { return unavailable("EXECUTION_REGISTRY_UNAVAILABLE"); }
    }

    private ExecuteCodeResponse run(Record record, Held slot, Work work) throws IOException, InterruptedException {
        ExecuteCodeResponse response;
        boolean cleaned = false;
        try {
            try { response = work.execute(); }
            catch (Exception failure) {
                StackTraceElement frame = Arrays.stream(failure.getStackTrace()).filter(f -> f.getClassName().startsWith("com.codeoj.")).findFirst().orElse(null);
                LOG.warn("Isolated work failed: type={}, frame={}", failure.getClass().getSimpleName(), frame);
                response = unavailable("ISOLATED_EXECUTION_FAILURE");
            }
            try { engine.removeExecution(record.token); cleaned = true; }
            catch (Exception failure) { response = unavailable("EXECUTION_CLEANUP_PENDING"); }
            try (Held ignored = gate()) {
                record.response = response; record.state = cleaned ? "COMPLETED" : "REAP_PENDING";
                save(recordPath(record.token), record);
                if (cleaned) { Files.deleteIfExists(slotPath(record.slot)); }
                else { save(slotPath(record.slot), record); }
            }
            return response;
        } finally { slot.close(); }
    }

    void recover() throws IOException, InterruptedException {
        try (Held ignored = gate()) {
            for (int i = 0; i < slots; i++) {
                Held slot = lock("slot-" + i);
                if (slot == null) { continue; }
                try {
                    Record previous = read(slotPath(i));
                    if (previous != null) { reap(previous); }
                } finally { slot.close(); }
            }
            collect();
        }
    }

    private void reap(Record record) throws IOException, InterruptedException {
        // Do not clear either journal when daemon/resource inspection is unavailable.
        engine.removeExecution(record.token);
        if (record.response == null) { record.response = unavailable("EXECUTION_OWNER_LOST"); }
        record.state = "COMPLETED";
        save(recordPath(record.token), record);
        Files.deleteIfExists(slotPath(record.slot));
    }

    private long records() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "execution-*.json")) {
            long count = 0; for (Path ignored : files) { count++; } return count;
        }
    }
    private void collect() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "execution-*.json")) {
            for (Path path : files) {
                Record record = read(path);
                if (record.response != null && "COMPLETED".equals(record.state)
                        && System.currentTimeMillis() > record.deadline + 60000) { Files.delete(path); }
            }
        }
    }
    private Path recordPath(String token) { return root.resolve("execution-" + token + ".json"); }
    private Path slotPath(int slot) { return root.resolve("slot-" + slot + ".json"); }
    private Record read(Path path) throws IOException {
        if (!Files.exists(path)) { return null; }
        try {
            Record record = JSONUtil.toBean(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), Record.class);
            if (record.token == null || !record.token.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                    || record.hash == null || !record.hash.matches("[0-9a-f]{64}") || record.deadline <= 0
                    || record.slot < 0 || record.slot >= slots || !Arrays.asList("RUNNING", "COMPLETED", "REAP_PENDING").contains(record.state)
                    || (!"RUNNING".equals(record.state) && record.response == null)
                    || (path.getFileName().toString().startsWith("slot-") && !path.equals(slotPath(record.slot)))
                    || (path.getFileName().toString().startsWith("execution-") && !path.equals(recordPath(record.token)))) {
                throw new IOException("INVALID_EXECUTION_JOURNAL");
            }
            return record;
        }
        catch (RuntimeException invalid) { throw new IOException("INVALID_EXECUTION_JOURNAL"); }
    }
    private void save(Path path, Record record) throws IOException { atomic(path, JSONUtil.toJsonStr(record)); }
    private void atomic(Path path, String text) throws IOException {
        Path pending = Files.createTempFile(root, ".pending-", ".json");
        try {
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                ByteBuffer data = StandardCharsets.UTF_8.encode(text);
                while (data.hasRemaining()) { channel.write(data); } channel.force(true);
            }
            Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(pending); }
    }
    private Held gate() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            Held held = lock("registry"); if (held != null) { return held; }
            if (System.nanoTime() > deadline) { throw new IOException("REGISTRY_LOCK_TIMEOUT"); }
            Thread.sleep(10);
        }
    }
    private Held lock(String name) throws IOException {
        Path path = root.resolve(name + ".lock");
        FileChannel channel;
        synchronized (CHANNELS) {
            channel = CHANNELS.get(path);
            if (channel == null) {
                channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                CHANNELS.put(path, channel);
            }
        }
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) { return new Held(channel, lock); }
        } catch (OverlappingFileLockException busy) { /* A different instance in this JVM owns the slot. */ }
        return null;
    }
    private static final class Held implements AutoCloseable {
        final FileChannel channel; final FileLock lock;
        Held(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        @Override public void close() throws IOException { lock.release(); }
    }
    static ExecuteCodeResponse unavailable(String error) {
        return ExecuteCodeResponse.builder().outcome(SandboxOutcome.PLATFORM_ERROR).status(3)
                .message(error).errorCode(error).outputList(Collections.emptyList()).build();
    }
}
