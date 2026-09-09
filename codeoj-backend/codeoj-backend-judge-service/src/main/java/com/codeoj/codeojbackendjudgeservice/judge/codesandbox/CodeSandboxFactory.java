package com.codeoj.codeojbackendjudgeservice.judge.codesandbox;

import com.codeoj.codeojbackendjudgeservice.judge.codesandbox.impl.ExampleCodeSandbox;
import com.codeoj.codeojbackendjudgeservice.judge.codesandbox.impl.RemoteCodeSandbox;
import com.codeoj.codeojbackendjudgeservice.judge.codesandbox.impl.ThirdPartyCodeSandbox;
import org.springframework.stereotype.Component;

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
            case "example":
                return new ExampleCodeSandbox();
            case "remote":
                return remoteCodeSandbox;
            case "thirdParty":
                return new ThirdPartyCodeSandbox();
            default:
                return new ExampleCodeSandbox();
        }
    }
}
