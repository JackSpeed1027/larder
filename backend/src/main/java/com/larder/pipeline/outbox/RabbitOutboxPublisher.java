package com.larder.pipeline.outbox;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 真实 RabbitMQ 投递：mandatory + publisher confirm + returns。
 *
 * <p>判定顺序很重要：
 * <ol>
 *   <li>confirm ack 之前，消息不算成功；</li>
 *   <li>ack 之后检查 {@code CorrelationData.getReturned()}——broker 可能先 basic.return（无路由）再 ack，
 *       这种 ack 不能当成功，否则 unroutable 会被标 SENT（任务卡明确禁止）；</li>
 *   <li>confirm 超时视为"结果未知"，按失败退避重试（允许重复投递）。</li>
 * </ol>
 */
@Component
public class RabbitOutboxPublisher implements OutboxPublisher {

    private final RabbitTemplate template;
    private final long confirmTimeoutMs;

    public RabbitOutboxPublisher(RabbitTemplate template,
                                 @Value("${larder.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.template = template;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Override
    public PublishOutcome publish(OutboxRow row) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setMessageId(row.getEventId());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setHeader("payloadHash", row.getPayloadHash());
        props.setHeader("ownerId", row.getOwnerId());
        Message message = new Message(row.getPayloadJson().getBytes(StandardCharsets.UTF_8), props);

        CorrelationData correlation = new CorrelationData(row.getEventId());
        template.send(RabbitTopology.EXCHANGE, row.getEventType(), message, correlation);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception timeoutOrInterrupt) {
            if (timeoutOrInterrupt instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return PublishOutcome.TIMEOUT;
        }
        if (!confirm.isAck()) {
            return PublishOutcome.NACKED;
        }
        // 无路由的消息会被 basic.return 回来：即使 ack 也不能算成功
        if (correlation.getReturned() != null) {
            return PublishOutcome.UNROUTABLE;
        }
        return PublishOutcome.ACKED;
    }
}
