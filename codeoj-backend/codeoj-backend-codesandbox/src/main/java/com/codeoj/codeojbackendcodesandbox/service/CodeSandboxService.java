package com.codeoj.codeojbackendcodesandbox.service;

import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeResponse;

/**
 * 代码沙箱服务接口
 */
public interface CodeSandboxService {

    /**
     * 校验鉴权头
     *
     * @param auth 请求头携带的密钥
     */
    void checkAuth(String auth);

    /**
     * 编译并执行用户代码，返回每个输入用例的输出
     *
     * @param executeCodeRequest 执行请求
     * @return 每个用例的输出、执行状态与判题信息
     */
    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);
}
