package com.codeoj.codeojbackendjudgeservice.rabbitmq;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="codeoj.reliable-judge.enabled",havingValue="true")
public class ReliableJudgeTopology {
    @Bean public Declarables reliableJudgeDeclarations() {
        DirectExchange exchange=new DirectExchange("codeoj.judge.v1.exchange",true,false);
        DirectExchange dead=new DirectExchange("codeoj.judge.v1.dlx",true,false);
        Queue queue=QueueBuilder.durable("codeoj.judge.v1.queue").deadLetterExchange(dead.getName()).deadLetterRoutingKey("quarantine").build();
        Queue quarantine=QueueBuilder.durable("codeoj.judge.v1.quarantine").build();
        return new Declarables(exchange,dead,queue,quarantine,BindingBuilder.bind(queue).to(exchange).with("judge"),
                BindingBuilder.bind(quarantine).to(dead).with("quarantine"));
    }
}
