package com.larder.pipeline.outbox;

/**
 * 投递结果：由 publisher 返回，relay 据此决定"标 SENT"还是"退避重试"。
 *
 * <p>关键区分：{@link #UNROUTABLE} 表示 broker 收了消息但没有任何队列绑定它。
 * 这种情况**不能**标 SENT——消息实际上进了黑洞；必须退避重试并最终进 DEAD，
 * 否则运维永远不知道有事件丢了。
 */
public enum PublishOutcome {
    /** broker 确认收到（publisher confirm ack）且没有被退回。 */
    ACKED,
    /** nack 或连接/通道错误。 */
    NACKED,
    /** mandatory 消息被 basic.return 退回（无路由）。 */
    UNROUTABLE,
    /** 等 confirm 超时，结果未知。 */
    TIMEOUT
}
