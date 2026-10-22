package com.codeoj.codeojbackendcodesandbox.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.*;
import com.codeoj.codeojbackendcodesandbox.service.CodeSandboxService;
import com.codeoj.codeojbackendcodesandbox.service.impl.nativejava.NativeReceipt;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Per-stage Linux containers; no fallback to host execution when isolation is unavailable. */
@Service
@ConditionalOnProperty(name = "codesandbox.mode", havingValue = "isolated", matchIfMissing = true)
public class JavaIsolatedCodeSandbox implements CodeSandboxService {
    private static final String HELPERS = "com.codeoj.codeojbackendcodesandbox.service.impl.nativejava.";
    private final String secret;
    private final Path root;
    private final DockerIsolationEngine engine;
    private final DurableExecutionRegistry registry;
    private final long runMs, compileMs, attemptMs;
    private final int outputBytes, pids;
    private final String cpus;

    public JavaIsolatedCodeSandbox(
            @Value("${codesandbox.auth-secret:secretKey}") String secret,
            @Value("${codesandbox.isolated.docker-binary:/usr/bin/docker}") String docker,
            @Value("${codesandbox.isolated.docker-endpoint:unix:///var/run/docker.sock}") String endpoint,
            @Value("${codesandbox.isolated.image:}") String image,
            @Value("${codesandbox.isolated.state-directory:./sandbox-state}") String directory,
            @Value("${codesandbox.max-concurrent:2}") int slots,
            @Value("${codesandbox.run-timeout-ms:5000}") long runMs,
            @Value("${codesandbox.compile-timeout-ms:15000}") long compileMs,
            @Value("${codesandbox.attempt-timeout-ms:30000}") long attemptMs,
            @Value("${codesandbox.max-output-bytes:65536}") int outputBytes,
            @Value("${codesandbox.isolated.pids:64}") int pids,
            @Value("${codesandbox.isolated.cpus:1.0}") String cpus) throws IOException, InterruptedException {
        if (runMs <= 0 || compileMs <= 0 || attemptMs <= 0 || outputBytes < 1 || outputBytes > 1048576
                || pids < 32 || pids > 256 || !cpus.matches("(?:0\\.[1-9]|1(?:\\.0)?)")) {
            throw new IllegalArgumentException("INVALID_ISOLATION_POLICY");
        }
        this.secret = secret; this.runMs = runMs; this.compileMs = compileMs; this.attemptMs = attemptMs;
        this.outputBytes = outputBytes; this.pids = pids; this.cpus = cpus;
        Files.createDirectories(Paths.get(directory)); this.root = Paths.get(directory).toRealPath();
        String pool = DigestUtil.sha256Hex(root.toString()).substring(0, 16);
        this.engine = new DockerIsolationEngine(docker, endpoint, image, pool, root);
        engine.verify();
        String policy = DigestUtil.sha256Hex(endpoint + ":" + image + ":" + runMs + ":" + compileMs + ":"
                + attemptMs + ":" + outputBytes + ":" + pids + ":" + cpus);
        this.registry = new DurableExecutionRegistry(root, slots, policy, engine);
    }
    @Override public void checkAuth(String auth) {
        if (auth == null || secret == null || secret.isEmpty() || !java.security.MessageDigest.isEqual(
                auth.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("无权限访问代码沙箱");
        }
    }
    @Override public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        if (!valid(request)) { return response(SandboxOutcome.REJECTED, "INVALID_EXECUTION_REQUEST", null, null); }
        return registry.execute(request, () -> executeOnce(request));
    }
    @Scheduled(fixedDelay = 3000) public void recoverExecutions() {
        try { registry.recover(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException e) { /* Leave journals and physical capacity reserved; next sweep retries. */ }
    }
    private ExecuteCodeResponse executeOnce(ExecuteCodeRequest request) throws Exception {
        String token = request.getExecutionToken();
        long deadline = Math.min(request.getExecutionDeadlineEpochMs(), System.currentTimeMillis() + attemptMs);
        Path work = Files.createDirectories(root.resolve("work").resolve(token));
        Path source = work.resolve("Submission.java");
        Files.write(source, request.getCode().getBytes(StandardCharsets.UTF_8));
        source.toFile().setReadable(true, false);
        String artifact = engine.volume(token, "artifacts");
        String sourceVolume = engine.volume(token, "source");
        String compileReceipt = engine.volume(token, "compile-receipt");
        long compileDeadline = Math.min(deadline, System.currentTimeMillis() + compileMs);
        String nonce = UUID.randomUUID().toString();
        String compiler = engine.create(token, "compile", artifact, false, compileReceipt, sourceVolume, 393216, pids, cpus,
                deadline, compileMs, "/opt/java/openjdk/bin/java", "-Xmx256m", "-cp", "/opt/helpers",
                HELPERS + "NativeCompiler", "/source/Submission.java", "/work/classes", "/work/compile.receipt", nonce);
        engine.upload(compiler, source);
        BoundedProcessRunner.Result compilation = engine.start(compiler, "", remaining(compileDeadline), outputBytes);
        JSONObject compiled = trustedResult(compiler, compilation, work.resolve("compile.json"));
        if (!"EXITED".equals(compiled.getStr("end")) || compiled.getInt("exitCode", -1) != 0) {
            return DurableExecutionRegistry.unavailable("COMPILER_BUDGET_OR_PLATFORM_FAILURE");
        }
        Path receipt = work.resolve("compile.receipt"); engine.copy(compiler, "/work/compile.receipt", receipt);
        String[] entry = NativeReceipt.read(receipt, nonce);
        if ("CE".equals(entry[0])) { return response(SandboxOutcome.CE, "COMPILATION_FAILED", null, null); }
        if (!"OK".equals(entry[0]) || !entry[1].matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
            return DurableExecutionRegistry.unavailable("COMPILER_ENTRY_UNAVAILABLE");
        }
        long memory = request.getMemoryLimitKiB() == null ? 131072 : request.getMemoryLimitKiB();
        long caseLimit = request.getRunTimeLimitMs() == null ? runMs : request.getRunTimeLimitMs();
        List<String> outputs = new ArrayList<>(); long peak = 0, wall = 0;
        for (int i = 0; i < request.getInputList().size(); i++) {
            if (deadline - System.currentTimeMillis() < caseLimit + 250) {
                return DurableExecutionRegistry.unavailable("ATTEMPT_BUDGET_EXHAUSTED");
            }
            String stage = "case-" + i;
            String runReceipt = engine.volume(token, stage + "-receipt");
            long caseDeadline = Math.min(deadline, System.currentTimeMillis() + caseLimit);
            String container = engine.create(token, stage, artifact, true, runReceipt, null, memory, pids, cpus,
                    deadline, caseLimit, "/opt/java/openjdk/bin/java", "-Dfile.encoding=UTF-8", "-XX:+UseSerialGC",
                    "-Xmx" + Math.max(8, memory / 1024) + "m", "-cp", "/opt/helpers",
                    HELPERS + "IsolatedLauncher", "/work/classes", entry[1]);
            BoundedProcessRunner.Result run = engine.start(container, request.getInputList().get(i), remaining(caseDeadline), outputBytes);
            JSONObject result = trustedResult(container, run, work.resolve(stage + ".json"));
            Long measured = result.getLong("memoryKiB"); Long time = result.getLong("timeMs");
            if (measured != null) { peak = Math.max(peak, measured); }
            if (time != null) { wall = Math.max(wall, time); }
            JudgeInfo metrics = metrics(wall, measured == null ? null : peak);
            switch (result.getStr("end", "UNKNOWN")) {
                case "MEMORY_LIMIT": return response(SandboxOutcome.MLE, "CGROUP_MEMORY_LIMIT", metrics, null);
                case "TIMEOUT": return response(SandboxOutcome.TLE, "CASE_WALL_TIME_EXCEEDED", metrics, null);
                case "OUTPUT_LIMIT": return response(SandboxOutcome.OUTPUT_LIMIT, "CASE_OUTPUT_EXCEEDED", metrics, null);
                case "EXITED": break;
                default: return DurableExecutionRegistry.unavailable("ISOLATION_RESULT_UNAVAILABLE");
            }
            if (result.getInt("exitCode", -1) == 43) { return DurableExecutionRegistry.unavailable("JVM_OR_ENTRY_POINT_FAILURE"); }
            if (result.getInt("exitCode", -1) != 0) { return response(SandboxOutcome.RE, "USER_PROCESS_EXIT", metrics, null); }
            if (measured == null || measured < 1 || time == null || time < 1) {
                return DurableExecutionRegistry.unavailable("CGROUP_METRICS_UNAVAILABLE");
            }
            outputs.add(run.stdout);
        }
        return response(SandboxOutcome.EXECUTED, null, metrics(wall, peak), outputs);
    }
    private JSONObject trustedResult(String container, BoundedProcessRunner.Result run, Path file)
            throws IOException, InterruptedException {
        if (run.end == BoundedProcessRunner.End.OUTPUT_LIMIT) { return JSONUtil.createObj().set("end", "OUTPUT_LIMIT"); }
        JSONObject state = engine.inspect(container);
        if (Boolean.TRUE.equals(state.getBool("OOMKilled"))) { return JSONUtil.createObj().set("end", "MEMORY_LIMIT"); }
        if (run.end != BoundedProcessRunner.End.EXITED || run.exitCode != 0 || Boolean.TRUE.equals(state.getBool("Running"))) {
            return JSONUtil.createObj().set("end", "UNKNOWN");
        }
        engine.copy(container, "/receipt/result.json", file);
        if (Files.size(file) > 4096) { throw new IOException("INVALID_SUPERVISOR_RECEIPT"); }
        return JSONUtil.parseObj(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }
    private long remaining(long deadline) throws IOException {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) { throw new IOException("ISOLATION_STAGE_DEADLINE"); }
        return left + 2000; // Root supervisor enforces the earlier wall deadline even if HTTP/manager disappears.
    }
    private boolean valid(ExecuteCodeRequest request) {
        if (request == null || !"java".equals(request.getLanguage()) || request.getCode() == null
                || request.getCode().trim().isEmpty() || request.getCode().getBytes(StandardCharsets.UTF_8).length > 262144
                || request.getInputList() == null || request.getInputList().isEmpty() || request.getInputList().size() > 20
                || (request.getRunTimeLimitMs() != null && (request.getRunTimeLimitMs() <= 0 || request.getRunTimeLimitMs() > runMs))
                || (request.getMemoryLimitKiB() != null && (request.getMemoryLimitKiB() < 16384 || request.getMemoryLimitKiB() > 1048576))) { return false; }
        long bytes = 0;
        for (String input : request.getInputList()) { if (input == null) { return false; } bytes += input.getBytes(StandardCharsets.UTF_8).length; }
        return bytes <= 1048576;
    }
    private JudgeInfo metrics(long wall, Long memory) {
        JudgeInfo metrics = new JudgeInfo(); metrics.setTime(wall); metrics.setMemory(memory);
        metrics.setMemoryMeasurement(memory == null ? null : "CGROUP_V2_PEAK_KIB"); return metrics;
    }
    private ExecuteCodeResponse response(SandboxOutcome outcome, String error, JudgeInfo info, List<String> output) {
        return ExecuteCodeResponse.builder().outcome(outcome).errorCode(error).message(error == null ? "ok" : error)
                .status(outcome == SandboxOutcome.EXECUTED ? 1 : outcome == SandboxOutcome.REJECTED || outcome == SandboxOutcome.PLATFORM_ERROR ? 3 : 2)
                .judgeInfo(info).outputList(output == null ? Collections.emptyList() : output).build();
    }
}
