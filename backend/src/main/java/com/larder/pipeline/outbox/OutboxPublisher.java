package com.larder.pipeline.outbox;

/**
 * 把一条 outbox 事件投递到 MQ 的端口。
 *
 * <p>实现必须在**事务外**调用：数据库事务只负责"领取"，网络 I/O 不能占着行锁。
 * 返回结果而不是抛异常，是为了让 relay 统一按 {@link PublishOutcome} 处理退避。
 */
public interface OutboxPublisher {

    PublishOutcome publish(OutboxRow event);
}
