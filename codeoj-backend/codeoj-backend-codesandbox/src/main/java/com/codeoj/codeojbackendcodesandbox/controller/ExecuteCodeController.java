package com.codeoj.codeojbackendcodesandbox.controller;

import com.codeoj.codeojbackendcodesandbox.service.CodeSandboxService;
import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 代码执行接口
 *
 * <p>仅允许判题服务通过鉴权头调用，防止被恶意用户直接执行任意代码。
 */
@RestController
public class ExecuteCodeController {

    private final CodeSandboxService codeSandboxService;

    public ExecuteCodeController(CodeSandboxService codeSandboxService) {
        this.codeSandboxService = codeSandboxService;
    }

    /**
     * 执行代码
     *
     * @param executeCodeRequest 执行请求（代码、语言、输入用例）
     * @param auth              鉴权密钥（判题服务配置的 codesandbox.remote.auth-secret）
     */
    @PostMapping("/executeCode")
    public ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest,
                                           @RequestHeader(value = "auth", required = false) String auth) {
        codeSandboxService.checkAuth(auth);
        return codeSandboxService.executeCode(executeCodeRequest);
    }
}
