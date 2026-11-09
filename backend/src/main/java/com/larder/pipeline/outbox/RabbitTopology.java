package com.larder.pipeline.outbox;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑与模板配置。
 *
 * <p>为什么要显式声明拓扑：队列不存在时消息会被丢弃或退回；声明是幂等操作，
 * 让"事件能路由"成为可验证的部署事实，而不是靠手工建队列。
 *
 * <p>为什么 mandatory + returns + confirms 都要开：只开 confirm 会漏掉
 * "broker 收了但没路由到任何队列"（unroutable）的情况——那是静默丢消息。
 */
@Configuration
public class RabbitTopology {

    public static final String EXCHANGE = "larder.events";
    public static final String INGEST_QUEUE = "larder.events.ingest";
    public static final String INGEST_ROUTING_KEY = "job.queued.#";

    @Bean
    public CachingConnectionFactory rabbitConnectionFactory(
            @Value("${spring.rabbitmq.host:127.0.0.1}") String host,
            @Value("${spring.rabbitmq.port:5672}") int port,
            @Value("${spring.rabbitmq.username:larder}") String username,
            @Value("${spring.rabbitmq.password:}") String password) {
        CachingConnectionFactory factory = new CachingConnectionFactory(host, port);
        factory.setUsername(username);
        factory.setPassword(password);
        // CORRELATED：每条消息的 confirm 与 CorrelationData 关联，能判断"是不是我这条"
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(CachingConnectionFactory factory) {
        RabbitTemplate template = new RabbitTemplate(factory);
        template.setMandatory(true);
        return template;
    }

    @Bean
    public TopicExchange larderEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue ingestQueue() {
        return new Queue(INGEST_QUEUE, true);
    }

    @Bean
    public Binding ingestBinding(Queue ingestQueue, TopicExchange larderEventsExchange) {
        return BindingBuilder.bind(ingestQueue).to(larderEventsExchange).with(INGEST_ROUTING_KEY);
    }
}
