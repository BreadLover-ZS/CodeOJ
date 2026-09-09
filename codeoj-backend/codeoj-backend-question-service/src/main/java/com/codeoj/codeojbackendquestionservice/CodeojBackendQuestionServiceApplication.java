package com.codeoj.codeojbackendquestionservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.codeoj.codeojbackendquestionservice.mapper")
@EnableScheduling
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
@ComponentScan("com.codeoj")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"com.codeoj.codeojbackendserviceclient.service"})
public class CodeojBackendQuestionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeojBackendQuestionServiceApplication.class, args);
    }

}
