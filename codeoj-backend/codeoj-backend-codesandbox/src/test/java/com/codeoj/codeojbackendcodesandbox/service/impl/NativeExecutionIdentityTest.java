package com.codeoj.codeojbackendcodesandbox.service.impl;

import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class NativeExecutionIdentityTest {
    @TempDir Path directory;
    private ExecuteCodeRequest request(Path marker) {
        String file=marker.toString().replace("\\","\\\\").replace("\"","\\\"");
        String code="public class Main { public static void main(String[] args) throws Exception {"
                +"java.nio.file.Path p=java.nio.file.Paths.get(\""+file+"\");"
                +"int n=java.nio.file.Files.exists(p)?Integer.parseInt(new String(java.nio.file.Files.readAllBytes(p),\"UTF-8\")):0;"
                +"java.nio.file.Files.write(p,Integer.toString(n+1).getBytes(\"UTF-8\"));"
                +"System.out.print(n+1);Thread.sleep(100);}}";
        return ExecuteCodeRequest.builder().language("java").code(code).inputList(Collections.singletonList(""))
                .executionToken(UUID.randomUUID().toString()).executionDeadlineEpochMs(System.currentTimeMillis()+30000).build();
    }
    @Test void concurrentAndRepeatedTokenStartsOnlyOnePhysicalJvm() throws Exception {
        JavaNativeCodeSandbox sandbox=new JavaNativeCodeSandbox(1);Path marker=directory.resolve("runs");ExecuteCodeRequest request=request(marker);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<ExecuteCodeResponse> first=pool.submit(()->sandbox.executeCode(request));
            Future<ExecuteCodeResponse> second=pool.submit(()->sandbox.executeCode(request));
            assertEquals(SandboxOutcome.EXECUTED,first.get(5,TimeUnit.SECONDS).getOutcome());
            assertEquals(SandboxOutcome.EXECUTED,second.get(5,TimeUnit.SECONDS).getOutcome());
            assertEquals(SandboxOutcome.EXECUTED,sandbox.executeCode(request).getOutcome());
            assertEquals("1",new String(Files.readAllBytes(marker),StandardCharsets.UTF_8));
            request.setCode("different");assertEquals("EXECUTION_TOKEN_CONTENT_CONFLICT",sandbox.executeCode(request).getErrorCode());
        } finally { pool.shutdownNow(); }
    }
    @Test void expiredTokenCannotBeStarted() {
        JavaNativeCodeSandbox sandbox=new JavaNativeCodeSandbox(1);Path marker=directory.resolve("runs");ExecuteCodeRequest request=request(marker);
        request.setExecutionDeadlineEpochMs(System.currentTimeMillis()-1);
        assertEquals(SandboxOutcome.PLATFORM_ERROR,sandbox.executeCode(request).getOutcome());assertFalse(Files.exists(marker));
    }
}
