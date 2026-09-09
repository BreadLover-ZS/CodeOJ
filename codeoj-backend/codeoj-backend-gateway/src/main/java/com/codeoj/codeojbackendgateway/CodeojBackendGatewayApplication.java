package com.codeoj.codeojbackendgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableDiscoveryClient
public class CodeojBackendGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeojBackendGatewayApplication.class, args);
    }

}
