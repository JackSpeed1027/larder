package com.larder.pipeline.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时驱动 Outbox 投递。
 *
 * <p>默认关闭（{@code larder.outbox.enabled=false}）：单机开发不必起 RabbitMQ；
 * 需要验证投递时打开，用 compose 环境变量控制，而不是改代码。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "larder.outbox.enabled", havingValue = "true")
public class OutboxSchedulingConfig {

    /** 调度器本身：固定延迟轮询，避免上一轮未完成时叠加。 */
    @Component
    @ConditionalOnProperty(name = "larder.outbox.enabled", havingValue = "true")
    public static class Poller {

        private final OutboxRelay relay;

        public Poller(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${larder.outbox.poll-interval-ms:1000}")
        public void poll() {
            relay.runOnce();
        }
    }
}
