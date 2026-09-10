package com.codeoj.codeojbackendjudgeservice.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * 用于创建判题程序用到的交换机和队列（只在程序启动前手动执行一次）
 *
 * <p>连接信息支持环境变量覆盖：RABBITMQ_HOST / RABBITMQ_USERNAME / RABBITMQ_PASSWORD。
 */
@Slf4j
public class InitRabbitMq {

    public static void doInit() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(System.getenv().getOrDefault("RABBITMQ_HOST", "localhost"));
        String username = System.getenv().getOrDefault("RABBITMQ_USERNAME", "guest");
        String password = System.getenv().getOrDefault("RABBITMQ_PASSWORD", "guest");
        factory.setUsername(username);
        factory.setPassword(password);
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            String EXCHANGE_NAME = "code_exchange";
            channel.exchangeDeclare(EXCHANGE_NAME, "direct");
            String queueName = "code_queue";
            channel.queueDeclare(queueName, true, false, false, null);
            channel.queueBind(queueName, EXCHANGE_NAME, "my_routingKey");
            log.info("消息队列启动成功");
        } catch (Exception e) {
            log.error("消息队列启动失败", e);
        }
    }

    public static void main(String[] args) {
        doInit();
    }
}
