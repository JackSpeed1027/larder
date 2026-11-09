package com.larder.pipeline.lease;

/** 租约已失效（被接管/过期）：迟到的 worker 必须放弃，而不是继续写。 */
public class StaleLeaseException extends RuntimeException {

    public StaleLeaseException(String message) {
        super(message);
    }
}
