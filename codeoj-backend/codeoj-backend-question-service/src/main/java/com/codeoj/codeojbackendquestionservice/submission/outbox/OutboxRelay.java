package com.codeoj.codeojbackendquestionservice.submission.outbox;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@ConditionalOnProperty(name="codeoj.reliable-judge.enabled",havingValue="true")
public class OutboxRelay {
    private final OutboxStore store;
    private final RabbitTemplate rabbit;
    public OutboxRelay(OutboxStore store,@Qualifier("reliableJudgeTemplate") RabbitTemplate rabbit) { this.store=store;this.rabbit=rabbit; }
    @Scheduled(fixedDelayString="${codeoj.reliable-judge.relay-delay-ms:1000}")
    public void tick() {
        for(String eventId:store.due()) {
            OutboxStore.Publication publication=store.claim(eventId);
            if(publication==null) { continue; }
            OutboxDelivery.publish(store,rabbit,publication,"codeoj.judge.v1.exchange","judge");
        }
    }
}
