package com.larder.pipeline.inbox;

import com.larder.ingest.JsonValue;
import com.larder.pipeline.outbox.RabbitTopology;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 事件消费者：解析 → 去重/接管事务 → 提交后手动 ack。
 *
 * <p>ack 策略：
 * <ul>
 *   <li>ACCEPTED / ALREADY_RUNNING / ALREADY_DONE：事务已提交，ack；</li>
 *   <li>CONFLICT / UNKNOWN_JOB / 缺字段：坏消息，nack(requeue=false) → 死信；</li>
 *   <li>其它异常（DB 暂时不可用等）：nack(requeue=true)，让消息回到队列稍后重投。</li>
 * </ul>
 */
@Component
public class InboxListener {

    private static final Logger log = LoggerFactory.getLogger(InboxListener.class);

    private final InboxHandler handler;

    public InboxListener(InboxHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = RabbitTopology.INGEST_QUEUE, containerFactory = InboxRabbitConfig.INBOX_CONTAINER_FACTORY)
    public void onEvent(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        String eventId = message.getMessageProperties().getMessageId();
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        String jobId = JsonValue.read(payload, "jobId");
        String payloadHash = headerOrHash(message, payload);

        if (eventId == null || eventId.isBlank() || jobId == null || jobId.isBlank()) {
            log.warn("坏消息进入死信：eventId={} jobId={} payload={}", eventId, jobId, payload);
            channel.basicNack(tag, false, false);
            return;
        }

        InboxOutcome outcome;
        try {
            outcome = handler.handle(eventId, jobId, payloadHash);
        } catch (RuntimeException failure) {
            log.warn("消费失败，消息回队列重试：eventId={} reason={}", eventId, failure.toString());
            channel.basicNack(tag, false, true);
            return;
        }

        switch (outcome) {
            case ACCEPTED, ALREADY_RUNNING, ALREADY_DONE -> channel.basicAck(tag, false);
            default -> {
                log.warn("不可恢复事件进入死信：eventId={} outcome={}", eventId, outcome);
                channel.basicNack(tag, false, false);
            }
        }
    }

    private String headerOrHash(Message message, String payload) {
        Object header = message.getMessageProperties().getHeaders().get("payloadHash");
        if (header != null && !header.toString().isBlank()) {
            return header.toString();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
