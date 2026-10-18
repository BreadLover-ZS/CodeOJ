package com.codeoj.codeojbackendjudgeservice.judge.codesandbox.impl;

import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Loopback protocol tests, not a real judge-service/sandbox end-to-end acceptance. */
@Timeout(5)
class RemoteCodeSandboxTest {
    private HttpServer server;
    private RemoteCodeSandbox remote;
    private AtomicInteger requests;

    private void endpoint(int status, String body, long delayMs) throws Exception {
        requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/executeCode", exchange -> {
            requests.incrementAndGet();
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        remote = new RemoteCodeSandbox();
        ReflectionTestUtils.setField(remote, "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/executeCode");
        ReflectionTestUtils.setField(remote, "authSecret", "test-only");
        ReflectionTestUtils.setField(remote, "readTimeoutMs", 1000);
        ReflectionTestUtils.setField(remote, "totalTimeoutMs", 1500);
    }

    private ExecuteCodeRequest request() {
        return ExecuteCodeRequest.builder().language("java").code("test").inputList(Collections.singletonList("")).build();
    }

    @AfterEach void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
    @Test void acceptsStructuredResponse() throws Exception {
        endpoint(200, "{\"outcome\":\"CE\",\"status\":2}", 0);
        assertEquals(SandboxOutcome.CE, remote.executeCode(request()).getOutcome());
        assertEquals(1, requests.get());
    }
    @Test void httpFailureIsPlatformError() throws Exception {
        endpoint(503, "unavailable", 0);
        assertThrows(BusinessException.class, () -> remote.executeCode(request()));
        assertEquals(1, requests.get());
    }
    @Test void oldOrEmptyResponseIsNotSuccess() throws Exception {
        endpoint(200, "{\"status\":1}", 0);
        assertThrows(BusinessException.class, () -> remote.executeCode(request()));
    }
    @Test void deadlineDoesNotRetryOrBecomeUserTle() throws Exception {
        endpoint(200, "{\"outcome\":\"EXECUTED\",\"status\":1}", 500);
        ReflectionTestUtils.setField(remote, "totalTimeoutMs", 100);
        BusinessException error = assertThrows(BusinessException.class, () -> remote.executeCode(request()));
        assertEquals("SANDBOX_TRANSPORT_TIMEOUT", error.getMessage());
        assertEquals(1, requests.get());
    }
}
