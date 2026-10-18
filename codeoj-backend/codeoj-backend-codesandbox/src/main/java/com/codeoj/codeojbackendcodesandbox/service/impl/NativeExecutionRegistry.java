package com.codeoj.codeojbackendcodesandbox.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendmodel.model.codesandbox.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded local token deduplication, retained beyond the token deadline; not durable across restart. */
final class NativeExecutionRegistry {
    private static final class Entry {
        final String hash;
        final long expiresAt;
        final CompletableFuture<ExecuteCodeResponse> result=new CompletableFuture<>();
        Entry(String hash,long deadline) { this.hash=hash;this.expiresAt=deadline+60000; }
    }
    private final Map<String,Entry> entries=new HashMap<>();

    ExecuteCodeResponse execute(ExecuteCodeRequest request,Supplier<ExecuteCodeResponse> work) {
        if(request==null || request.getExecutionToken()==null) {
            if(request!=null && request.getExecutionDeadlineEpochMs()!=null) { return unavailable("INVALID_EXECUTION_IDENTITY"); }
            return work.get(); // Existing trusted local demo remains compatible.
        }
        long now=System.currentTimeMillis();
        Long deadline=request.getExecutionDeadlineEpochMs();
        if(!request.getExecutionToken().matches("[0-9a-f-]{36}") || deadline==null || deadline<=now || deadline>now+60000) {
            return unavailable("EXECUTION_DEADLINE_OR_TOKEN_INVALID");
        }
        String hash=DigestUtil.sha256Hex(JSONUtil.toJsonStr(request));
        Entry entry;boolean owner=false;
        synchronized(this) {
            cleanup();entry=entries.get(request.getExecutionToken());
            if(entry==null) {
                if(entries.size()>=64) { return unavailable("EXECUTION_REGISTRY_CAPACITY"); }
                entry=new Entry(hash,deadline);entries.put(request.getExecutionToken(),entry);owner=true;
            }
        }
        if(!entry.hash.equals(hash)) { return unavailable("EXECUTION_TOKEN_CONTENT_CONFLICT"); }
        if(owner) {
            try { entry.result.complete(work.get()); }
            catch(RuntimeException e) { entry.result.completeExceptionally(e); }
        }
        try { return entry.result.get(Math.max(1,deadline-System.currentTimeMillis()),TimeUnit.MILLISECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();return unavailable("EXECUTION_WAIT_INTERRUPTED"); }
        catch(ExecutionException | TimeoutException e) { return unavailable("EXECUTION_RESULT_UNAVAILABLE"); }
    }

    synchronized void cleanup() {
        long now=System.currentTimeMillis();
        entries.entrySet().removeIf(e->e.getValue().result.isDone() && now>e.getValue().expiresAt);
    }
    private ExecuteCodeResponse unavailable(String code) {
        return ExecuteCodeResponse.builder().outcome(SandboxOutcome.PLATFORM_ERROR).status(3).message(code).errorCode(code)
                .outputList(Collections.emptyList()).build();
    }
}
