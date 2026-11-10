package com.larder.ingest;

/**
 * 收录请求被拒绝（4xx），携带稳定错误码，由控制器转成 ApiErrorResponse。
 *
 * <p>错误码必须来自 contracts/error-codes.json；这里只允许用已注册的码。
 */
public class IngestRejectedException extends RuntimeException {

    private final String code;
    private final int httpStatus;
    private final boolean retryable;

    public IngestRejectedException(String code, String message, int httpStatus, boolean retryable) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    public static IngestRejectedException validation(String message) {
        return new IngestRejectedException("VALIDATION_FAILED", message, 400, false);
    }

    /** 同 key 但请求体不同：不得自动换 key 重发同一用户动作（v4 S01）。 */
    public static IngestRejectedException idempotencyPayloadMismatch(String message) {
        return new IngestRejectedException("IDEMPOTENCY_PAYLOAD_MISMATCH", message, 409, false);
    }

    /** 同 key 的处理仍在进行：用原 key 原 body 查询或重试（v4 S01）。 */
    public static IngestRejectedException idempotencyInProgress(String message) {
        return new IngestRejectedException("IDEMPOTENCY_IN_PROGRESS", message, 409, true);
    }
}
