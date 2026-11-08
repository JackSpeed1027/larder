package com.larder.pipeline.model;

/** Outbox 投递状态。 */
public enum OutboxState {
    PENDING,
    PUBLISHING,
    SENT,
    DEAD
}
