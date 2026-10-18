package com.codeoj.codeojbackendjudgeservice.judge.codesandbox;

import com.codeoj.codeojbackendjudgeservice.judge.codesandbox.impl.RemoteCodeSandbox;
import org.springframework.stereotype.Component;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;

import javax.annotation.Resource;

/**
 * 代码沙箱工厂（根据字符串参数创建指定的代码沙箱实例）
 */
@Component
public class CodeSandboxFactory {

    @Resource
    private RemoteCodeSandbox remoteCodeSandbox;

    /**
     * 创建代码沙箱实例
     *
     * @param type 沙箱类型
     * @return
     */
    public CodeSandbox newInstance(String type) {
        switch (type) {
            case "remote":
                return remoteCodeSandbox;
            default:
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "UNSUPPORTED_SANDBOX_TYPE");
        }
    }
}
