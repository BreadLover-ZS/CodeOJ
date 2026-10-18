package com.codeoj.codeojbackendquestionservice.submission;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="codeoj.reliable-judge.enabled",havingValue="true")
public class ReliableJudgeConfiguration {
    @Bean public Declarables reliableJudgeTopology() {
        DirectExchange exchange=new DirectExchange("codeoj.judge.v1.exchange",true,false);
        DirectExchange dead=new DirectExchange("codeoj.judge.v1.dlx",true,false);
        Queue queue=QueueBuilder.durable("codeoj.judge.v1.queue").deadLetterExchange(dead.getName()).deadLetterRoutingKey("quarantine").build();
        Queue quarantine=QueueBuilder.durable("codeoj.judge.v1.quarantine").build();
        return new Declarables(exchange,dead,queue,quarantine,BindingBuilder.bind(queue).to(exchange).with("judge"),
                BindingBuilder.bind(quarantine).to(dead).with("quarantine"));
    }
    @Bean @Primary public RabbitTemplate reliableJudgeTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template=new RabbitTemplate(connectionFactory);template.setMandatory(true);
        template.setReturnsCallback(returned -> { }); // Evidence read from each CorrelationData, never body logging.
        return template;
    }
}
