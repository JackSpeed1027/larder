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

    public static IngestRejectedException idempotencyConflict(String message, boolean retryable) {
        return new IngestRejectedException("IDEMPOTENCY_CONFLICT", message, 409, retryable);
    }
}
