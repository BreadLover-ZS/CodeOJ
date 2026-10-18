package com.codeoj.codeojbackendcodesandbox.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Local trusted-sample runner, not a malicious-code isolation boundary. */
final class BoundedProcessRunner {
    enum End { EXITED, TIMEOUT, OUTPUT_LIMIT, MEMORY_LIMIT }

    static final class Result {
        final End end;
        final int exitCode;
        final String stdout;
        final String stderr;
        final long wallTimeMs;
        Long peakMemoryKiB;

        Result(End end, int exitCode, String stdout, String stderr, long wallTimeMs) {
            this.end = end;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.wallTimeMs = wallTimeMs;
        }
    }

    Result run(List<String> command, Path directory, String input, long timeoutMs, int outputBytes)
            throws IOException, InterruptedException {
        return run(command, directory, input, timeoutMs, outputBytes, null);
    }

    Result run(List<String> command, Path directory, String input, long timeoutMs, int outputBytes, Long memoryLimitKiB)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        // Do not inherit credentials, JAVA_TOOL_OPTIONS or CLASSPATH.
        builder.environment().clear();
        builder.environment().put("LANG", "en_US.UTF-8");
        Process process = builder.start();
        NativeRssSampler sampler = memoryLimitKiB == null ? null : new NativeRssSampler(process);
        Long peakMemoryKiB = null;
        long nextSample = started;
        ExecutorService io = Executors.newFixedThreadPool(3, task -> {
            Thread thread = new Thread(task, "codeoj-process-io");
            thread.setDaemon(true);
            return thread;
        });
        AtomicLong used = new AtomicLong();
        AtomicBoolean exceeded = new AtomicBoolean();
        Future<String> stdout = io.submit(() -> read(process.getInputStream(), used, exceeded, outputBytes));
        Future<String> stderr = io.submit(() -> read(process.getErrorStream(), used, exceeded, outputBytes));
        Future<?> stdin = io.submit(() -> {
            try (OutputStream stream = process.getOutputStream()) {
                stream.write(input.getBytes(StandardCharsets.UTF_8));
                stream.flush();
            } catch (IOException ignored) {
                // A program may exit without consuming all stdin. The process deadline
                // still bounds a writer blocked because a running program does not read.
            }
        });
        try {
            while (process.isAlive() && !exceeded.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    Result result = result(End.TIMEOUT, -1, "", "", started);
                    result.peakMemoryKiB = peakMemoryKiB;
                    return result;
                }
                if (sampler != null && System.nanoTime() >= nextSample) {
                    Long rss = sampler.sample();
                    if (rss != null) {
                        peakMemoryKiB = peakMemoryKiB == null ? rss : Math.max(peakMemoryKiB, rss);
                        if (rss > memoryLimitKiB) {
                            Result result = result(End.MEMORY_LIMIT, -1, "", "", started);
                            result.peakMemoryKiB = peakMemoryKiB;
                            return result;
                        }
                    }
                    nextSample = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20);
                }
                process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS);
            }
            if (exceeded.get()) {
                Result result = result(End.OUTPUT_LIMIT, -1, "", "", started);
                result.peakMemoryKiB = peakMemoryKiB;
                return result;
            }
            String out = await(stdout, deadline);
            String err = await(stderr, deadline);
            if (exceeded.get()) {
                Result result = result(End.OUTPUT_LIMIT, -1, "", "", started);
                result.peakMemoryKiB = peakMemoryKiB;
                return result;
            }
            Result result = result(End.EXITED, process.exitValue(), out, err, started);
            result.peakMemoryKiB = peakMemoryKiB;
            return result;
        } catch (TimeoutException e) {
            // Process exited but pipes did not settle: not evidence of user TLE.
            throw new IOException("PROCESS_IO_DEADLINE", e);
        } catch (ExecutionException e) {
            throw new IOException("PROCESS_IO_FAILURE", e.getCause());
        } finally {
            // Kill before closing pipes: a blocked writer may hold the stream monitor.
            boolean interrupted = Thread.interrupted();
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(1000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            stdin.cancel(true);
            stdout.cancel(true);
            stderr.cancel(true);
            close(process.getOutputStream());
            close(process.getInputStream());
            close(process.getErrorStream());
            io.shutdownNow();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String read(InputStream stream, AtomicLong used, AtomicBoolean exceeded, int limit) throws IOException {
        try (InputStream source = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (used.addAndGet(count) > limit) {
                    exceeded.set(true);
                    break;
                }
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String await(Future<String> future, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    private Result result(End end, int exit, String out, String err, long started) {
        return new Result(end, exit, out, err, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private void close(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Already closed by the I/O worker or process reaper.
        }
    }
}
