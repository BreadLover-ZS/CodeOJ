package com.codeoj.codeojbackendquestionservice.contest;

import cn.hutool.json.JSONUtil;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnExpression("${codeoj.reliable-judge.enabled:false} && ${codeoj.registration.enabled:false}")
public class RegistrationConsumer {
    private final RegistrationTransactions transactions;private final AtomicInteger failures=new AtomicInteger();
    public RegistrationConsumer(RegistrationTransactions transactions) { this.transactions=transactions; }
    @RabbitListener(queues=RegistrationMessaging.QUEUE,containerFactory="registrationListenerFactory")
    public void receive(Message raw,Channel channel) throws Exception {
        long tag=raw.getMessageProperties().getDeliveryTag();RegistrationEvent event;
        try {
            if(raw.getBody().length>4096) { throw new IllegalArgumentException("OVERSIZED_REGISTRATION_EVENT"); }
            event=JSONUtil.toBean(new String(raw.getBody(),StandardCharsets.UTF_8),RegistrationEvent.class);
            if(event==null || !event.valid()) { throw new IllegalArgumentException("INVALID_REGISTRATION_EVENT"); }
        } catch(RuntimeException malformed) { channel.basicReject(tag,false);return; }
        try { if(transactions.confirm(event)) { channel.basicAck(tag,false); }else { channel.basicReject(tag,false); }failures.set(0); }
        catch(RuntimeException unavailable) {
            int n=failures.updateAndGet(v->Math.min(v+1,5));
            try { pause(Math.min(30000,1000L<<n)); }catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw interrupted; }
            channel.basicNack(tag,false,true);
        }
    }
    protected void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
}
