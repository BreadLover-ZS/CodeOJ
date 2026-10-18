package com.codeoj.codeojbackendquestionservice.scoreboard;

import com.codeoj.codeojbackendquestionservice.submission.outbox.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="codeoj.scoreboard.enabled",havingValue="true")
public class ScoreboardMessaging {
    @org.springframework.beans.factory.annotation.Value("${codeoj.reliable-judge.enabled:false}") private boolean reliableEnabled;
    @javax.annotation.PostConstruct public void validate() { if(!reliableEnabled) { throw new IllegalStateException("SCOREBOARD_REQUIRES_RELIABLE_JUDGE"); } }
    public static final String EXCHANGE="codeoj.scoreboard.v1.exchange",QUEUE="codeoj.scoreboard.v1.queue";
    @Bean public Declarables scoreboardTopology() {
        DirectExchange exchange=new DirectExchange(EXCHANGE,true,false),dead=new DirectExchange("codeoj.scoreboard.v1.dlx",true,false);
        Queue work=QueueBuilder.durable(QUEUE).deadLetterExchange(dead.getName()).deadLetterRoutingKey("quarantine").build(),quarantine=QueueBuilder.durable("codeoj.scoreboard.v1.quarantine").build();
        return new Declarables(exchange,dead,work,quarantine,BindingBuilder.bind(work).to(exchange).with("result"),BindingBuilder.bind(quarantine).to(dead).with("quarantine"));
    }
    @Bean public RabbitTemplate scoreboardTemplate(ConnectionFactory factory) { RabbitTemplate template=new RabbitTemplate(factory);template.setMandatory(true);template.setReturnsCallback(returned->{});return template; }
    @Bean public SimpleRabbitListenerContainerFactory scoreboardListenerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,ConnectionFactory connection) {
        SimpleRabbitListenerContainerFactory factory=new SimpleRabbitListenerContainerFactory();configurer.configure(factory,connection);factory.setPrefetchCount(10);factory.setConcurrentConsumers(1);factory.setMaxConcurrentConsumers(1);factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);return factory;
    }
    @Bean public Relay scoreboardRelay(ScoreboardTransactions transactions,OutboxStore store,@org.springframework.beans.factory.annotation.Qualifier("scoreboardTemplate") RabbitTemplate rabbit) { return new Relay(transactions,store,rabbit); }
    public static class Relay {
        private final ScoreboardTransactions transactions;private final OutboxStore store;private final RabbitTemplate rabbit;
        public Relay(ScoreboardTransactions transactions,OutboxStore store,RabbitTemplate rabbit) { this.transactions=transactions;this.store=store;this.rabbit=rabbit; }
        @Scheduled(fixedDelay=1000) public void tick() {
            transactions.recoverPublications();
            for(String id:store.due("JUDGE_RESULT")) { OutboxStore.Publication p=store.claim(id,"JUDGE_RESULT");if(p!=null) { OutboxDelivery.publish(store,rabbit,p,EXCHANGE,"result"); } }
        }
    }
}
