package com.codeoj.codeojbackendjudgeservice.rabbitmq;

import cn.hutool.json.JSONUtil;
import com.codeoj.codeojbackendjudgeservice.judge.ReliableJudgeWorker;
import com.codeoj.codeojbackendmodel.model.reliability.JudgeDispatch;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name="codeoj.reliable-judge.enabled",havingValue="true")
public class ReliableJudgeConsumer {
    private final ReliableJudgeWorker worker;
    private final AtomicInteger failures=new AtomicInteger();
    public ReliableJudgeConsumer(ReliableJudgeWorker worker) { this.worker=worker; }

    @RabbitListener(queues="codeoj.judge.v1.queue",ackMode="MANUAL",concurrency="${codeoj.reliable-judge.worker-slots:2}")
    public void receive(Message raw,Channel channel) throws Exception {
        long tag=raw.getMessageProperties().getDeliveryTag();
        JudgeDispatch message;
        try {
            if(raw.getBody().length>8192) { throw new IllegalArgumentException("OVERSIZED_DISPATCH"); }
            message=JSONUtil.toBean(new String(raw.getBody(),StandardCharsets.UTF_8),JudgeDispatch.class);
            if(message==null || !message.valid()) { throw new IllegalArgumentException("INVALID_DISPATCH"); }
        } catch(RuntimeException malformed) {
            channel.basicReject(tag,false);return; // Explicitly configured quarantine DLX, no arbitrary DB mutation.
        }
        try {
            worker.process(message);
            channel.basicAck(tag,false);
            failures.set(0);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw interrupted;
        } catch(RuntimeException unavailable) {
            // Cannot prove business state was durably handled. Bound re-entry by backoff,
            // retain unacked delivery during the pause, then ask the broker to redeliver.
            int count=failures.updateAndGet(n->Math.min(5,n+1));
            Thread.sleep(Math.min(30000,1000L<<count));
            channel.basicNack(tag,false,true);
        }
    }
}
