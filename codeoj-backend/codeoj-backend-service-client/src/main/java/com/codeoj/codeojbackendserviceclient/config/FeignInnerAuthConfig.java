package com.codeoj.codeojbackendserviceclient.config;

import com.codeoj.codeojbackendserviceclient.constant.InnerAuthConstant;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 内部调用鉴权配置
 *
 * <p>为所有 Feign 请求自动添加内部鉴权头，供服务端 {@code /inner/**} 接口校验，
 * 避免内部接口被网关外部或直连方式任意调用。
 */
@Configuration
public class FeignInnerAuthConfig {

    @Value("${codeoj.inner-auth-secret:codeoj-default-inner-secret}")
    private String innerAuthSecret;

    /**
     * 为 Feign 请求统一附加内部鉴权头
     */
    @Bean
    public RequestInterceptor innerAuthRequestInterceptor() {
        return requestTemplate -> requestTemplate.header(InnerAuthConstant.X_INNER_SECRET, innerAuthSecret);
    }
}