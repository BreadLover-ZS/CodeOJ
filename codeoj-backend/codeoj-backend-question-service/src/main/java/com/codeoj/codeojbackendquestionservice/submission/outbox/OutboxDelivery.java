package com.codeoj.codeojbackendquestionservice.submission.outbox;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.nio.charset.StandardCharsets;

/** Shared transport only. Business completion never follows from publisher confirmation. */
public final class OutboxDelivery {
    private OutboxDelivery() { }
    public static void publish(OutboxStore store,RabbitTemplate rabbit,OutboxStore.Publication p,String exchange,String route) {
        MessageProperties properties=new MessageProperties();properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(p.getEventId());properties.setCorrelationId(p.getToken());CorrelationData correlation=new CorrelationData(p.getToken());
        correlation.getFuture().addCallback(confirm->{
            boolean returned=correlation.getReturned()!=null;
            store.record(p.getEventId(),p.getToken(),confirm.isAck()?"ACK":"NACK",returned,returned?"UNROUTABLE":confirm.isAck()?null:"BROKER_NACK");
        },error->store.record(p.getEventId(),p.getToken(),"UNKNOWN",false,"CONFIRM_RESULT_UNKNOWN"));
        try { rabbit.send(exchange,route,new Message(p.getPayload().getBytes(StandardCharsets.UTF_8),properties),correlation); }
        catch(RuntimeException unknown) { store.record(p.getEventId(),p.getToken(),"UNKNOWN",false,"SEND_RESULT_UNKNOWN"); }
    }
}
