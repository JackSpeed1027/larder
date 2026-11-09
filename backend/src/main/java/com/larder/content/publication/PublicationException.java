package com.larder.content.publication;

/** 发布被拒绝：code 是稳定错误码，status 是 HTTP 状态。 */
public class PublicationException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public PublicationException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public static PublicationException epochSuperseded() {
        return new PublicationException("EPOCH_SUPERSEDED", "旧处理代次的结果不能发布", 409);
    }

    public static PublicationException staleRun() {
        return new PublicationException("STALE_RUN", "run 与 job 当前租约不匹配", 409);
    }

    public static PublicationException jobNotFound() {
        return new PublicationException("NOT_FOUND", "job 不存在", 404);
    }

    public static PublicationException corrupt(String message) {
        return new PublicationException("CORRUPT_ARTIFACT", message, 409);
    }
}
