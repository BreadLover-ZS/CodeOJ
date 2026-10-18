package com.codeoj.codeojbackendjudgeservice.judge.codesandbox;

import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CodeSandboxProxy implements CodeSandbox {

    private final CodeSandbox codeSandbox;


    public CodeSandboxProxy(CodeSandbox codeSandbox) {
        this.codeSandbox = codeSandbox;
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        log.info("Sandbox request: language={}, cases={}", executeCodeRequest.getLanguage(),
                executeCodeRequest.getInputList() == null ? 0 : executeCodeRequest.getInputList().size());
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
        log.info("Sandbox response: outcome={}", executeCodeResponse == null ? null : executeCodeResponse.getOutcome());
        return executeCodeResponse;
    }
}
