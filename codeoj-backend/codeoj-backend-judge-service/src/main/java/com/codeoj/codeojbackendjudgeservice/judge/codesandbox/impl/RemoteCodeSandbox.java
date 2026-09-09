package com.codeoj.codeojbackendjudgeservice.judge.codesandbox.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendjudgeservice.judge.codesandbox.CodeSandbox;
import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.codeoj.codeojbackendmodel.model.codesandbox.ExecuteCodeResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 远程代码沙箱（实际调用接口的沙箱）
 *
 * <p>地址与鉴权密钥通过配置注入，可通过
 * {@code codesandbox.remote.url} / {@code codesandbox.remote.auth-secret} 灵活指定。
 */
@Component
public class RemoteCodeSandbox implements CodeSandbox {

    // 定义鉴权请求头
    private static final String AUTH_REQUEST_HEADER = "auth";

    @Value("${codesandbox.remote.url:http://localhost:8090/executeCode}")
    private String url;

    @Value("${codesandbox.remote.auth-secret:secretKey}")
    private String authSecret;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        System.out.println("远程代码沙箱");
        String json = JSONUtil.toJsonStr(executeCodeRequest);
        String responseStr = HttpUtil.createPost(url)
                .header(AUTH_REQUEST_HEADER, authSecret)
                .body(json)
                .execute()
                .body();
        if (StringUtils.isBlank(responseStr)) {
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR, "executeCode remoteSandbox error, message = " + responseStr);
        }
        return JSONUtil.toBean(responseStr, ExecuteCodeResponse.class);
    }
}
