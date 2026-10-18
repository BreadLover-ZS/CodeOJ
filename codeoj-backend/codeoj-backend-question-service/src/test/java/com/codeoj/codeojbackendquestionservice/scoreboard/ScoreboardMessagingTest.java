package com.codeoj.codeojbackendquestionservice.scoreboard;

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

class ScoreboardMessagingTest {
    String eventId=UUID.randomUUID().toString();
    ScoreResultEvent event() { ScoreResultEvent e=new ScoreResultEvent();e.setSubmissionId(2L);e.setResultVersion(1);return e; }
    Message message(ScoreResultEvent e) { MessageProperties p=new MessageProperties();p.setDeliveryTag(123);p.setMessageId(eventId);return new Message(JSONUtil.toJsonStr(e).getBytes(StandardCharsets.UTF_8),p); }
    @Test void unsupportedPayloadIsQuarantinedWithoutTouchingAuthority() throws Exception {
        ScoreboardTransactions t=mock(ScoreboardTransactions.class);Channel c=mock(Channel.class);ScoreResultEvent e=event();e.setResultVersion(0);
        new ScoreboardConsumer(t).receive(message(e),c);verify(c).basicReject(123,false);verifyNoInteractions(t);verify(c,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void onlyDurablyHandledRequestsAreAcknowledged() throws Exception {
        ScoreboardTransactions t=mock(ScoreboardTransactions.class);Channel c=mock(Channel.class);ScoreResultEvent e=event();when(t.project(eventId,e)).thenReturn(true);
        new ScoreboardConsumer(t).receive(message(e),c);verify(t).project(eventId,e);verify(c).basicAck(123,false);
    }
    @Test void mismatchedIdentityIsQuarantined() throws Exception {
        ScoreboardTransactions t=mock(ScoreboardTransactions.class);Channel c=mock(Channel.class);new ScoreboardConsumer(t).receive(message(event()),c);verify(c).basicReject(123,false);verify(c,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void authorityFailureBacksOffBeforeRequeueInsteadOfAck() throws Exception {
        ScoreboardTransactions t=mock(ScoreboardTransactions.class);Channel c=mock(Channel.class);when(t.project(anyString(),any())).thenThrow(new IllegalStateException("DB_UNAVAILABLE"));long[] delay={0};
        ScoreboardConsumer consumer=new ScoreboardConsumer(t) { @Override protected void pause(long millis) { delay[0]=millis; } };
        consumer.receive(message(event()),c);assertEquals(2000,delay[0]);verify(c).basicNack(123,false,true);verify(c,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void interruptionLeavesDeliveryUnacknowledgedAndPreservesInterrupt() throws Exception {
        ScoreboardTransactions t=mock(ScoreboardTransactions.class);Channel c=mock(Channel.class);when(t.project(anyString(),any())).thenThrow(new IllegalStateException("DB_UNAVAILABLE"));
        ScoreboardConsumer consumer=new ScoreboardConsumer(t) { @Override protected void pause(long millis) throws InterruptedException { throw new InterruptedException(); } };
        try { assertThrows(InterruptedException.class,()->consumer.receive(message(event()),c));assertTrue(Thread.currentThread().isInterrupted());verifyNoInteractions(c); }finally { Thread.interrupted(); }
    }
    @Test void relayUsesSeparatePersistentMandatoryTransportAndCapturesReturn() {
        OutboxStore store=mock(OutboxStore.class);RabbitTemplate rabbit=mock(RabbitTemplate.class);OutboxStore.Publication p=new OutboxStore.Publication();p.setEventId("event");p.setToken("attempt");p.setPayload("{}");
        when(store.due("JUDGE_RESULT")).thenReturn(Collections.singletonList("event"));when(store.claim("event","JUDGE_RESULT")).thenReturn(p);
        doAnswer(invocation->{Message message=invocation.getArgument(2);assertEquals(MessageDeliveryMode.PERSISTENT,message.getMessageProperties().getDeliveryMode());CorrelationData correlation=invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(message,312,"NO_ROUTE",ScoreboardMessaging.EXCHANGE,"result"));correlation.getFuture().set(new CorrelationData.Confirm(true,null));return null;
        }).when(rabbit).send(eq(ScoreboardMessaging.EXCHANGE),eq("result"),any(Message.class),any(CorrelationData.class));
        new ScoreboardMessaging.Relay(mock(ScoreboardTransactions.class),store,rabbit).tick();verify(store).record("event","attempt","ACK",true,"UNROUTABLE");
    }
    @Test void sendExceptionIsUnknownAndNeverAReasonToReleaseCapacity() {
        OutboxStore store=mock(OutboxStore.class);RabbitTemplate rabbit=mock(RabbitTemplate.class);OutboxStore.Publication p=new OutboxStore.Publication();p.setEventId("event");p.setToken("attempt");p.setPayload("{}");
        doThrow(new IllegalStateException("CONNECTION_LOST")).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        OutboxDelivery.publish(store,rabbit,p,ScoreboardMessaging.EXCHANGE,"result");verify(store).record("event","attempt","UNKNOWN",false,"SEND_RESULT_UNKNOWN");verifyNoMoreInteractions(store);
    }
    @Test void corruptAuthoritativeFactIsQuarantinedInsteadOfHotRequeued() throws Exception {
        ScoreboardTransactions t=mock(ScoreboardTransactions.class);Channel c=mock(Channel.class);
        when(t.project(anyString(),any())).thenThrow(new com.codeoj.codeojbackendquestionservice.submission.SubmissionConflictException("INVALID_RESULT_VERDICT"));
        new ScoreboardConsumer(t).receive(message(event()),c);verify(c).basicReject(123,false);verify(c,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());verify(c,never()).basicAck(anyLong(),anyBoolean());
    }

}
