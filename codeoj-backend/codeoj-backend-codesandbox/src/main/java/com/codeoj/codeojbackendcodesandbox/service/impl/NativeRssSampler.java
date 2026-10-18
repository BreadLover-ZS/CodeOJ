package com.codeoj.codeojbackendcodesandbox.service.impl;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Sample the direct JVM's RSS in KiB. Missing permission/unsupported OS means unknown. */
final class NativeRssSampler {
    private final Long pid;
    private boolean unavailable;

    NativeRssSampler(Process process) {
        Long found;
        try {
            // Java 9+, without raising the Java 8 source/bytecode baseline.
            found = ((Number) Process.class.getMethod("pid").invoke(process)).longValue();
        } catch (ReflectiveOperationException e) {
            try {
                Field field = process.getClass().getDeclaredField("pid");
                field.setAccessible(true); // OpenJDK/Corretto 8 UNIXProcess.
                found = ((Number) field.get(process)).longValue();
            } catch (ReflectiveOperationException | RuntimeException unsupported) {
                found = null;
            }
        }
        pid = found;
    }

    Long sample() throws InterruptedException {
        if (pid == null || unavailable) {
            return null;
        }
        Process query = null;
        try {
            query = new ProcessBuilder("/bin/ps", "-o", "rss=", "-p", Long.toString(pid)).start();
            if (!query.waitFor(100, TimeUnit.MILLISECONDS)) {
                unavailable = true;
                return null;
            }
            if (query.exitValue() != 0) {
                return null; // Direct JVM may already have exited.
            }
            byte[] bytes = new byte[128];
            int count = query.getInputStream().read(bytes);
            if (count < 0) {
                return null;
            }
            long rss = Long.parseLong(new String(bytes, 0, count, StandardCharsets.US_ASCII).trim());
            return rss > 0 ? rss : null;
        } catch (IOException | NumberFormatException e) {
            unavailable = true;
            return null;
        } finally {
            if (query != null) {
                query.destroyForcibly();
                try {
                    query.getInputStream().close();
                    query.getErrorStream().close();
                    query.getOutputStream().close();
                } catch (IOException ignored) { }
            }
        }
    }
}
