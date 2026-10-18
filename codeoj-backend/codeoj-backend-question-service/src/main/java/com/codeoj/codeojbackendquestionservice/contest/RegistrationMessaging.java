package com.codeoj.codeojbackendquestionservice.contest;

import com.codeoj.codeojbackendquestionservice.submission.outbox.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@ConditionalOnExpression("${codeoj.reliable-judge.enabled:false} && ${codeoj.registration.enabled:false}")
public class RegistrationMessaging {
    public static final String EXCHANGE="codeoj.registration.v1.exchange",QUEUE="codeoj.registration.v1.queue";
    @Bean public Declarables registrationTopology() {
        DirectExchange exchange=new DirectExchange(EXCHANGE,true,false),dead=new DirectExchange("codeoj.registration.v1.dlx",true,false);
        Queue work=QueueBuilder.durable(QUEUE).deadLetterExchange(dead.getName()).deadLetterRoutingKey("quarantine").build(),quarantine=QueueBuilder.durable("codeoj.registration.v1.quarantine").build();
        return new Declarables(exchange,dead,work,quarantine,BindingBuilder.bind(work).to(exchange).with("confirm"),BindingBuilder.bind(quarantine).to(dead).with("quarantine"));
    }
    @Bean public RabbitTemplate registrationTemplate(ConnectionFactory factory) { RabbitTemplate template=new RabbitTemplate(factory);template.setMandatory(true);template.setReturnsCallback(returned->{});return template; }
    @Bean public SimpleRabbitListenerContainerFactory registrationListenerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,ConnectionFactory connection) {
        SimpleRabbitListenerContainerFactory factory=new SimpleRabbitListenerContainerFactory();configurer.configure(factory,connection);factory.setPrefetchCount(10);factory.setConcurrentConsumers(1);factory.setMaxConcurrentConsumers(1);factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);return factory;
    }
    @Bean public Relay registrationRelay(OutboxStore store,@org.springframework.beans.factory.annotation.Qualifier("registrationTemplate") RabbitTemplate rabbit) { return new Relay(store,rabbit); }
    public static class Relay {
        private final OutboxStore store;private final RabbitTemplate rabbit;
        public Relay(OutboxStore store,RabbitTemplate rabbit) { this.store=store;this.rabbit=rabbit; }
        @Scheduled(fixedDelay=1000) public void tick() {
            for(String id:store.due("REGISTRATION_CONFIRM")) { OutboxStore.Publication p=store.claim(id,"REGISTRATION_CONFIRM");if(p!=null) { OutboxDelivery.publish(store,rabbit,p,EXCHANGE,"confirm"); } }
        }
    }
}
