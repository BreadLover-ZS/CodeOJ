package com.codeoj.codeojbackendcodesandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;

/**
 * 代码沙箱服务启动类
 *
 * <p>负责接收判题服务的代码执行请求，在临时目录中编译并运行用户代码，返回执行结果。
 * 沙箱无数据库/Redis 依赖，排除父 pom 引入的相关自动配置。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class,
        SessionAutoConfiguration.class
})
public class CodeojBackendCodesandboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeojBackendCodesandboxApplication.class, args);
    }
}
