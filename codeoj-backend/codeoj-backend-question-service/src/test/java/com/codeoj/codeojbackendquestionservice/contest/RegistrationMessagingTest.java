package com.codeoj.codeojbackendquestionservice.contest;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendquestionservice.submission.outbox.*;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationMessagingTest {
    RegistrationEvent event() { RegistrationEvent e=new RegistrationEvent();e.setContestId(1L);e.setRequestId(2L);e.setPublishedVersion(1L);e.setEpoch(1L);e.setPayloadVersion(1);e.setEventId(UUID.randomUUID().toString());return e; }
    Message message(RegistrationEvent e) { MessageProperties p=new MessageProperties();p.setDeliveryTag(123);return new Message(JSONUtil.toJsonStr(e).getBytes(StandardCharsets.UTF_8),p); }
    @Test void unsupportedPayloadIsQuarantinedWithoutTouchingAuthority() throws Exception {
        RegistrationTransactions t=mock(RegistrationTransactions.class);Channel c=mock(Channel.class);RegistrationEvent e=event();e.setPayloadVersion(2);
        new RegistrationConsumer(t).receive(message(e),c);verify(c).basicReject(123,false);verifyNoInteractions(t);verify(c,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void onlyDurablyHandledRequestsAreAcknowledged() throws Exception {
        RegistrationTransactions t=mock(RegistrationTransactions.class);Channel c=mock(Channel.class);RegistrationEvent e=event();when(t.confirm(e)).thenReturn(true);
        new RegistrationConsumer(t).receive(message(e),c);verify(t).confirm(e);verify(c).basicAck(123,false);
    }
    @Test void mismatchedIdentityIsQuarantined() throws Exception {
        RegistrationTransactions t=mock(RegistrationTransactions.class);Channel c=mock(Channel.class);new RegistrationConsumer(t).receive(message(event()),c);verify(c).basicReject(123,false);verify(c,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void authorityFailureBacksOffBeforeRequeueInsteadOfAck() throws Exception {
        RegistrationTransactions t=mock(RegistrationTransactions.class);Channel c=mock(Channel.class);when(t.confirm(any())).thenThrow(new IllegalStateException("DB_UNAVAILABLE"));long[] delay={0};
        RegistrationConsumer consumer=new RegistrationConsumer(t) { @Override protected void pause(long millis) { delay[0]=millis; } };
        consumer.receive(message(event()),c);assertEquals(2000,delay[0]);verify(c).basicNack(123,false,true);verify(c,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void interruptionLeavesDeliveryUnacknowledgedAndPreservesInterrupt() throws Exception {
        RegistrationTransactions t=mock(RegistrationTransactions.class);Channel c=mock(Channel.class);when(t.confirm(any())).thenThrow(new IllegalStateException("DB_UNAVAILABLE"));
        RegistrationConsumer consumer=new RegistrationConsumer(t) { @Override protected void pause(long millis) throws InterruptedException { throw new InterruptedException(); } };
        try { assertThrows(InterruptedException.class,()->consumer.receive(message(event()),c));assertTrue(Thread.currentThread().isInterrupted());verifyNoInteractions(c); }finally { Thread.interrupted(); }
    }
    @Test void relayUsesSeparatePersistentMandatoryTransportAndCapturesReturn() {
        OutboxStore store=mock(OutboxStore.class);RabbitTemplate rabbit=mock(RabbitTemplate.class);OutboxStore.Publication p=new OutboxStore.Publication();p.setEventId("event");p.setToken("attempt");p.setPayload("{}");
        when(store.due("REGISTRATION_CONFIRM")).thenReturn(Collections.singletonList("event"));when(store.claim("event","REGISTRATION_CONFIRM")).thenReturn(p);
        doAnswer(invocation->{Message message=invocation.getArgument(2);assertEquals(MessageDeliveryMode.PERSISTENT,message.getMessageProperties().getDeliveryMode());CorrelationData correlation=invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(message,312,"NO_ROUTE",RegistrationMessaging.EXCHANGE,"confirm"));correlation.getFuture().set(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(eq(RegistrationMessaging.EXCHANGE),eq("confirm"),any(Message.class),any(CorrelationData.class));
        new RegistrationMessaging.Relay(store,rabbit).tick();verify(store).record("event","attempt","ACK",true,"UNROUTABLE");
    }
    @Test void sendExceptionIsUnknownAndNeverAReasonToReleaseCapacity() {
        OutboxStore store=mock(OutboxStore.class);RabbitTemplate rabbit=mock(RabbitTemplate.class);OutboxStore.Publication p=new OutboxStore.Publication();p.setEventId("event");p.setToken("attempt");p.setPayload("{}");
        doThrow(new IllegalStateException("CONNECTION_LOST")).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        OutboxDelivery.publish(store,rabbit,p,RegistrationMessaging.EXCHANGE,"confirm");verify(store).record("event","attempt","UNKNOWN",false,"SEND_RESULT_UNKNOWN");verifyNoMoreInteractions(store);
    }
}
