package com.codeoj.codeojbackendcodesandbox.service.impl;

import cn.hutool.core.util.StrUtil;
import com.codeoj.codeojbackendcodesandbox.service.CodeSandboxService;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.Stream;

/** Native runner for trusted local samples only. Isolation validation is deferred. */
@Slf4j
@Service
public class JavaNativeCodeSandbox implements CodeSandboxService {
    private static final int MAX_CODE_BYTES = 256 * 1024;
    private static final int MAX_INPUT_BYTES = 1024 * 1024;
    private static final int MAX_CASES = 20;
    private static final Pattern CLASS_NAME = Pattern.compile("public\\s+class\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private final BoundedProcessRunner runner = new BoundedProcessRunner();
    private final Semaphore slots;
    private final NativeExecutionRegistry executions = new NativeExecutionRegistry();

    @Value("${codesandbox.auth-secret:secretKey}")
    private String authSecret;
    @Value("${codesandbox.run-timeout-ms:5000}")
    private long runTimeoutMs = 5000;
    @Value("${codesandbox.compile-timeout-ms:15000}")
    private long compileTimeoutMs = 15000;
    @Value("${codesandbox.attempt-timeout-ms:30000}")
    private long attemptTimeoutMs = 30000;
    @Value("${codesandbox.max-output-bytes:65536}")
    private int maxOutputBytes = 65536;

    public JavaNativeCodeSandbox(@Value("${codesandbox.max-concurrent:2}") int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 16) {
            throw new IllegalArgumentException("max-concurrent must be between 1 and 16");
        }
        slots = new Semaphore(maxConcurrent);
    }

    @Override
    public void checkAuth(String auth) {
        if (StrUtil.isBlank(auth) || !auth.equals(authSecret)) {
            throw new SecurityException("无权限访问代码沙箱");
        }
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        return executions.execute(request, () -> executeOnce(request));
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public void clearExpiredExecutionTokens() { executions.cleanup(); }

    private ExecuteCodeResponse executeOnce(ExecuteCodeRequest request) {
        if (!valid(request)) {
            return response(SandboxOutcome.REJECTED, "INVALID_EXECUTION_REQUEST", null, null);
        }
        if (!slots.tryAcquire()) {
            return response(SandboxOutcome.PLATFORM_ERROR, "EXECUTION_CAPACITY_UNAVAILABLE", null, null);
        }
        Path workDir = null;
        try {
            if (runTimeoutMs <= 0 || compileTimeoutMs <= 0 || attemptTimeoutMs <= 0 || maxOutputBytes <= 0) {
                return response(SandboxOutcome.PLATFORM_ERROR, "INVALID_EXECUTOR_CONFIGURATION", null, null);
            }
            if (request.getRunTimeLimitMs() != null && request.getRunTimeLimitMs() > runTimeoutMs) {
                return response(SandboxOutcome.PLATFORM_ERROR, "CASE_LIMIT_EXCEEDS_EXECUTOR_POLICY", null, null);
            }
            long budget = attemptTimeoutMs;
            if (request.getExecutionDeadlineEpochMs() != null) {
                budget = Math.min(budget, request.getExecutionDeadlineEpochMs() - System.currentTimeMillis());
                if (budget <= 0) { return response(SandboxOutcome.PLATFORM_ERROR, "EXECUTION_DEADLINE_EXPIRED", null, null); }
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
            workDir = Files.createTempDirectory("codeoj-sandbox-");
            Path classes = Files.createDirectory(workDir.resolve("classes"));
            Matcher matcher = CLASS_NAME.matcher(request.getCode());
            String className = matcher.find() ? matcher.group(1) : "Main";
            Path source = workDir.resolve(className + ".java");
            Files.write(source, request.getCode().getBytes(StandardCharsets.UTF_8));
            List<String> compileCommand = Arrays.asList(jdkTool("javac"), "-J-Duser.language=en",
                    "-J-Duser.country=US", "-encoding", "UTF-8", "-proc:none", "-d", classes.toString(), source.toString());
            BoundedProcessRunner.Result compile = runner.run(compileCommand, workDir, "",
                    remaining(compileTimeoutMs, deadline), maxOutputBytes);
            if (compile.end != BoundedProcessRunner.End.EXITED) {
                return response(SandboxOutcome.PLATFORM_ERROR, "COMPILER_BUDGET_EXCEEDED", null, null);
            }
            if (compile.exitCode != 0) {
                boolean diagnostic = compile.stderr.contains(".java:") && compile.stderr.contains("error:");
                return response(diagnostic ? SandboxOutcome.CE : SandboxOutcome.PLATFORM_ERROR,
                        diagnostic ? "COMPILATION_FAILED" : "COMPILER_FAILURE", null, null);
            }
            List<String> output = new ArrayList<>();
            long maxWallTime = 0;
            Long peakMemory = null;
            boolean allMeasured = true;
            long memoryLimit = request.getMemoryLimitKiB() == null ? 131072L : request.getMemoryLimitKiB();
            long caseLimit = request.getRunTimeLimitMs() == null ? runTimeoutMs
                    : request.getRunTimeLimitMs();
            for (int i = 0; i < request.getInputList().size(); i++) {
                long available = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (available < caseLimit) {
                    return response(SandboxOutcome.PLATFORM_ERROR, "ATTEMPT_BUDGET_EXHAUSTED", null, null);
                }
                Path caseDir = Files.createDirectory(workDir.resolve("case-" + i));
                List<String> command = Arrays.asList(jdkTool("java"), "-Dfile.encoding=UTF-8",
                        "-XX:+UseSerialGC", "-cp", classes.toString(), className);
                BoundedProcessRunner.Result run = runner.run(command, caseDir, request.getInputList().get(i),
                        caseLimit, maxOutputBytes, memoryLimit);
                maxWallTime = Math.max(maxWallTime, run.wallTimeMs);
                JudgeInfo metrics = new JudgeInfo();
                metrics.setTime(maxWallTime);
                allMeasured &= run.peakMemoryKiB != null;
                if (run.peakMemoryKiB != null) {
                    peakMemory = peakMemory == null ? run.peakMemoryKiB : Math.max(peakMemory, run.peakMemoryKiB);
                }
                metrics.setMemory(allMeasured ? peakMemory : null);
                metrics.setMemoryMeasurement(allMeasured ? "NATIVE_RSS_SAMPLED" : null);
                if (run.end == BoundedProcessRunner.End.MEMORY_LIMIT) {
                    metrics.setMemory(run.peakMemoryKiB);
                    metrics.setMemoryMeasurement("NATIVE_RSS_SAMPLED");
                    return response(SandboxOutcome.MLE, "OBSERVED_RSS_LIMIT_EXCEEDED", metrics, null);
                }
                if (run.end == BoundedProcessRunner.End.TIMEOUT) {
                    return response(SandboxOutcome.TLE, "CASE_WALL_TIME_EXCEEDED", metrics, null);
                }
                if (run.end == BoundedProcessRunner.End.OUTPUT_LIMIT) {
                    return response(SandboxOutcome.OUTPUT_LIMIT, "CASE_OUTPUT_EXCEEDED", metrics, null);
                }
                if (run.exitCode != 0) {
                    // No reliable memory/termination attribution in native mode.
                    return response(SandboxOutcome.PLATFORM_ERROR,
                            "UNCLASSIFIED_PROCESS_EXIT", metrics, null);
                }
                output.add(run.stdout);
            }
            JudgeInfo metrics = new JudgeInfo();
            metrics.setTime(maxWallTime);
            metrics.setMemory(allMeasured ? peakMemory : null);
            metrics.setMemoryMeasurement(allMeasured ? "NATIVE_RSS_SAMPLED" : null);
            return response(SandboxOutcome.EXECUTED, null, metrics, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return response(SandboxOutcome.PLATFORM_ERROR, "EXECUTOR_INTERRUPTED", null, null);
        } catch (IOException | RuntimeException e) {
            log.warn("Native executor failed: {}", e.getClass().getSimpleName());
            return response(SandboxOutcome.PLATFORM_ERROR, "EXECUTOR_IO_FAILURE", null, null);
        } finally {
            cleanup(workDir);
            slots.release();
        }
    }

    private boolean valid(ExecuteCodeRequest request) {
        if (request == null || !"java".equals(request.getLanguage()) || StrUtil.isBlank(request.getCode())
                || request.getCode().getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES
                || request.getInputList() == null || request.getInputList().isEmpty()
                || request.getInputList().size() > MAX_CASES
                || (request.getRunTimeLimitMs() != null && request.getRunTimeLimitMs() <= 0)) {
            return false;
        }
        if (request.getMemoryLimitKiB() != null
                && (request.getMemoryLimitKiB() <= 0 || request.getMemoryLimitKiB() > 1048576)) {
            return false;
        }
        long bytes = 0;
        for (String input : request.getInputList()) {
            if (input == null) {
                return false;
            }
            bytes += input.getBytes(StandardCharsets.UTF_8).length;
        }
        return bytes <= MAX_INPUT_BYTES;
    }

    private long remaining(long budget, long deadline) throws IOException {
        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (left <= 0) {
            throw new IOException("ATTEMPT_DEADLINE");
        }
        return Math.min(budget, left);
    }

    private String jdkTool(String name) {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        if (!Files.isExecutable(javaHome.resolve("bin").resolve("javac")) && javaHome.getParent() != null) {
            javaHome = javaHome.getParent(); // Java 8 java.home points at jre/.
        }
        return javaHome.resolve("bin").resolve(name).toString();
    }

    private ExecuteCodeResponse response(SandboxOutcome outcome, String error, JudgeInfo metrics, List<String> output) {
        int status = outcome == SandboxOutcome.EXECUTED ? 1
                : outcome == SandboxOutcome.PLATFORM_ERROR || outcome == SandboxOutcome.REJECTED ? 3 : 2;
        return ExecuteCodeResponse.builder().outcome(outcome).errorCode(error).status(status)
                .message(error == null ? "ok" : error).judgeInfo(metrics)
                .outputList(output == null ? Collections.emptyList() : output).build();
    }

    private void cleanup(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) { // No FOLLOW_LINKS.
            Iterator<Path> iterator = paths.sorted(Comparator.reverseOrder()).iterator();
            while (iterator.hasNext()) {
                Files.deleteIfExists(iterator.next());
            }
        } catch (IOException e) {
            log.warn("Native executor cleanup failed: {}", directory.getFileName());
        }
    }
}
