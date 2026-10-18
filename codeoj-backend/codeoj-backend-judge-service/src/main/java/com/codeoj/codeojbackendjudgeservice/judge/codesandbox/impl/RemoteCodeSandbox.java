package com.codeoj.codeojbackendjudgeservice.judge.codesandbox.impl;

import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendjudgeservice.judge.codesandbox.CodeSandbox;
import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Bounded HTTP transport. A transport timeout never means user TLE; no automatic retry. */
@Component
public class RemoteCodeSandbox implements CodeSandbox {
    // Up to 20 * 64KiB outputs, including worst-case JSON escaping.
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final ExecutorService CALLS = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "codeoj-sandbox-http");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @Value("${codesandbox.remote.url:http://localhost:8090/executeCode}")
    private String url;
    @Value("${codesandbox.remote.auth-secret:secretKey}")
    private String authSecret;
    @Value("${codesandbox.remote.connect-timeout-ms:2000}")
    private int connectTimeoutMs = 2000;
    @Value("${codesandbox.remote.read-timeout-ms:35000}")
    private int readTimeoutMs = 35000;
    @Value("${codesandbox.remote.total-timeout-ms:38000}")
    private int totalTimeoutMs = 38000;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        Future<ExecuteCodeResponse> call;
        try {
            call = CALLS.submit(() -> {
                try (HttpResponse response = HttpUtil.createPost(url).header("auth", authSecret)
                        .setConnectionTimeout(connectTimeoutMs).setReadTimeout(readTimeoutMs)
                        .body(JSONUtil.toJsonStr(request)).executeAsync()) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw failure("SANDBOX_TRANSPORT_CANCELLED");
                    }
                    if (!response.isOk()) {
                        throw failure("SANDBOX_HTTP_ERROR");
                    }
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    try (InputStream stream = response.bodyStream()) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = stream.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) {
                                throw failure("SANDBOX_TRANSPORT_CANCELLED");
                            }
                            if (body.size() + count > MAX_RESPONSE_BYTES) {
                                throw failure("SANDBOX_RESPONSE_TOO_LARGE");
                            }
                            body.write(buffer, 0, count);
                        }
                    }
                    String json = new String(body.toByteArray(), StandardCharsets.UTF_8);
                    if (!JSONUtil.isTypeJSONObject(json)) {
                        throw failure("SANDBOX_PROTOCOL_ERROR");
                    }
                    ExecuteCodeResponse result = JSONUtil.toBean(json, ExecuteCodeResponse.class);
                    if (result == null || result.getOutcome() == null || result.getStatus() == null) {
                        throw failure("SANDBOX_PROTOCOL_ERROR");
                    }
                    return result;
                }
            });
        } catch (RejectedExecutionException e) {
            throw failure("SANDBOX_TRANSPORT_BUSY");
        }
        try {
            return call.get(totalTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw failure("SANDBOX_TRANSPORT_TIMEOUT");
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw failure("SANDBOX_TRANSPORT_INTERRUPTED");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BusinessException) {
                throw (BusinessException) e.getCause();
            }
            throw failure("SANDBOX_TRANSPORT_FAILURE");
        }
    }

    private BusinessException failure(String code) {
        return new BusinessException(ErrorCode.API_REQUEST_ERROR, code);
    }
}
