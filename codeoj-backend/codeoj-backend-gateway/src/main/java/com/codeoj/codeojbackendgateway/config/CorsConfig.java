package com.codeoj.codeojbackendgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

// 处理跨域
@Configuration
public class CorsConfig {

    /**
     * 允许跨域的来源（逗号分隔，通过环境变量 CORS_ALLOWED_ORIGINS 覆盖为线上真实域名）
     */
    @Value("${codeoj.cors-allowed-origins:http://localhost:8000}")
    private String allowedOrigins;

    @Bean
    public CorsWebFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        // 安全：禁止通配来源搭配携带凭证，仅放行配置的白名单来源
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());
        config.setAllowedOriginPatterns(origins);
        config.addAllowedHeader("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(new PathPatternParser());
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}