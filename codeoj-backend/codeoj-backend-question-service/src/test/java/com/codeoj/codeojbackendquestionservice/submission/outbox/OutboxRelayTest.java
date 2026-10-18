package com.codeoj.codeojbackendquestionservice.submission.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Confirm/Return callback contracts with a RabbitTemplate double; no broker acceptance claim. */
class OutboxRelayTest {
    private OutboxStore.Publication publication() {
        OutboxStore.Publication p=new OutboxStore.Publication();p.setEventId(UUID.randomUUID().toString());p.setToken(UUID.randomUUID().toString());
        p.setPayload("{\"submissionId\":1,\"dispatchGeneration\":1,\"payloadVersion\":1}");return p;
    }
    @Test void sendsPersistentMessageWithSeparateEventAndPublishAttemptIdentities() {
        OutboxStore store=mock(OutboxStore.class);RabbitTemplate rabbit=mock(RabbitTemplate.class);OutboxStore.Publication p=publication();
        when(store.due()).thenReturn(Collections.singletonList(p.getEventId()));when(store.claim(p.getEventId())).thenReturn(p);
        doAnswer(invocation->{
            Message message=invocation.getArgument(2);CorrelationData correlation=invocation.getArgument(3);
            assertEquals(MessageDeliveryMode.PERSISTENT,message.getMessageProperties().getDeliveryMode());
            assertEquals(p.getEventId(),message.getMessageProperties().getMessageId());assertEquals(p.getToken(),correlation.getId());
            assertEquals(p.getPayload(),new String(message.getBody(),StandardCharsets.UTF_8));
            correlation.getFuture().set(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(eq("codeoj.judge.v1.exchange"),eq("judge"),any(Message.class),any(CorrelationData.class));
        new OutboxRelay(store,rabbit).tick();verify(store).record(p.getEventId(),p.getToken(),"ACK",false,null);
    }
    @Test void ackPlusReturnRecordsUnroutableInsteadOfSuccess() {
        OutboxStore store=mock(OutboxStore.class);RabbitTemplate rabbit=mock(RabbitTemplate.class);OutboxStore.Publication p=publication();
        when(store.due()).thenReturn(Collections.singletonList(p.getEventId()));when(store.claim(p.getEventId())).thenReturn(p);
        doAnswer(invocation->{
            Message message=invocation.getArgument(2);CorrelationData correlation=invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(message,312,"NO_ROUTE","codeoj.judge.v1.exchange","judge"));
            correlation.getFuture().set(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        new OutboxRelay(store,rabbit).tick();verify(store).record(p.getEventId(),p.getToken(),"ACK",true,"UNROUTABLE");
    }
    @Test void synchronousSendExceptionIsUnknownRatherThanDefiniteFailure() {
        OutboxStore store=mock(OutboxStore.class);RabbitTemplate rabbit=mock(RabbitTemplate.class);OutboxStore.Publication p=publication();
        when(store.due()).thenReturn(Collections.singletonList(p.getEventId()));when(store.claim(p.getEventId())).thenReturn(p);
        doThrow(new IllegalStateException("connection uncertain")).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        new OutboxRelay(store,rabbit).tick();verify(store).record(p.getEventId(),p.getToken(),"UNKNOWN",false,"SEND_RESULT_UNKNOWN");
    }
}
