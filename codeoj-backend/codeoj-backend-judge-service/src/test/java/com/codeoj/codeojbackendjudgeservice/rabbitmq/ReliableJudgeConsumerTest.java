package com.codeoj.codeojbackendjudgeservice.rabbitmq;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendjudgeservice.judge.ReliableJudgeWorker;
import com.codeoj.codeojbackendmodel.model.reliability.JudgeDispatch;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReliableJudgeConsumerTest {
    private Message raw(String body) { MessageProperties properties=new MessageProperties();properties.setDeliveryTag(7);return new Message(body.getBytes(StandardCharsets.UTF_8),properties); }
    private JudgeDispatch event() { JudgeDispatch e=new JudgeDispatch();e.setEventId(UUID.randomUUID().toString());e.setSubmissionId(1L);e.setDispatchGeneration(1);e.setPayloadVersion(1);e.setTraceId(e.getEventId());return e; }
    @Test void malformedMessageIsQuarantinedWithoutTouchingBusinessState() throws Exception {
        ReliableJudgeWorker worker=mock(ReliableJudgeWorker.class);Channel channel=mock(Channel.class);
        new ReliableJudgeConsumer(worker).receive(raw("not json"),channel);
        verify(channel).basicReject(7,false);verifyNoInteractions(worker);verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void durablyHandledMessageIsAcknowledged() throws Exception {
        ReliableJudgeWorker worker=mock(ReliableJudgeWorker.class);Channel channel=mock(Channel.class);
        new ReliableJudgeConsumer(worker).receive(raw(JSONUtil.toJsonStr(event())),channel);
        verify(worker).process(any());verify(channel).basicAck(7,false);verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }
    @Test void authorityFailureIsNotAcknowledgedAndReentryIsBackedOff() throws Exception {
        ReliableJudgeWorker worker=mock(ReliableJudgeWorker.class);Channel channel=mock(Channel.class);
        doThrow(new IllegalStateException("authority unavailable")).when(worker).process(any());
        new ReliableJudgeConsumer(worker).receive(raw(JSONUtil.toJsonStr(event())),channel);
        verify(channel,never()).basicAck(anyLong(),anyBoolean());verify(channel).basicNack(7,false,true);
    }
}
