package com.larder.pipeline.inbox;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消费容器配置：手动 ack + 有界 prefetch + 拒绝不回队列。
 *
 * <p>为什么手动 ack：只有"inbox 写入 + job 接管"事务提交后，才能 ack；
 * 自动 ack 会在事务提交前就确认消息，崩溃时消息丢了但 job 没接管。
 *
 * <p>为什么有界 prefetch：一个消费者一次只预取少量消息，避免某个慢任务把整批消息
 * 都压在内存里；同时让其他实例有机会分担。
 *
 * <p>为什么 defaultRequeueRejected=false：坏消息重投只会无限循环；
 * 交给死信队列（DLX）后可以人工诊断与重放。
 */
@Configuration
public class InboxRabbitConfig {

    public static final String INBOX_CONTAINER_FACTORY = "inboxContainerFactory";

    @Bean(INBOX_CONTAINER_FACTORY)
    public SimpleRabbitListenerContainerFactory inboxContainerFactory(
            ConnectionFactory connectionFactory,
            @Value("${larder.inbox.prefetch:5}") int prefetch) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(prefetch);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
