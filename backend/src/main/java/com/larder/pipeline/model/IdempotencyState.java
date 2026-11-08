package com.larder.pipeline.model;

/** 幂等记录状态。 */
public enum IdempotencyState {
    PROCESSING,
    COMPLETED
}
