package com.codeoj.codeojbackendjudgeservice.rabbitmq;

import com.rabbitmq.client.Channel;
import com.codeoj.codeojbackendjudgeservice.judge.JudgeService;
import com.codeoj.codeojbackendmodel.model.entity.QuestionSubmit;
import com.codeoj.codeojbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.codeoj.codeojbackendserviceclient.service.QuestionFeignClient;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class MyMessageConsumer {

    @Resource
    private JudgeService judgeService;

    @Resource
    private QuestionFeignClient questionFeignClient;

    // 指定程序监听的消息队列和确认机制
    @SneakyThrows
    @RabbitListener(queues = {"code_queue"}, ackMode = "MANUAL")
    public void receiveMessage(String message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("receiveMessage message = {}", message);
        long questionSubmitId = Long.parseLong(message);
        try {
            judgeService.doJudge(questionSubmitId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("判题失败，questionSubmitId = {}", questionSubmitId, e);
            // 若记录已是终态（如：判题结果已写入但 ack 前进程崩溃导致消息重投），
            // 不能将已完成的判题结果覆盖为失败，直接丢弃消息即可
            QuestionSubmit current = questionFeignClient.getQuestionSubmitById(questionSubmitId);
            boolean isFinal = current != null
                    && (QuestionSubmitStatusEnum.SUCCEED.getValue().equals(current.getStatus())
                    || QuestionSubmitStatusEnum.FAILED.getValue().equals(current.getStatus()));
            if (!isFinal) {
                // 将判题状态回滚为失败，避免提交记录一直停留在“判题中”
                QuestionSubmit update = new QuestionSubmit();
                update.setId(questionSubmitId);
                update.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
                try {
                    questionFeignClient.updateQuestionSubmitById(update);
                } catch (Exception ex) {
                    log.error("回滚判题状态失败，questionSubmitId = {}", questionSubmitId, ex);
                }
            } else {
                log.info("判题已存在终态结果，跳过回滚，questionSubmitId = {}", questionSubmitId);
            }
            channel.basicNack(deliveryTag, false, false);
        }
    }

}