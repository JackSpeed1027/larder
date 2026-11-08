package com.larder.content.storage;

/**
 * 产物存储的领域异常，携带稳定错误码（必须来自 contracts/error-codes.json）。
 */
public class ArtifactStorageException extends RuntimeException {

    private final String code;

    public ArtifactStorageException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ArtifactStorageException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ArtifactStorageException hashMismatch(String expected, String actual) {
        return new ArtifactStorageException("CORRUPT_ARTIFACT",
                "内容 sha256 与声明不一致：declared=" + expected + " actual=" + actual);
    }

    public static ArtifactStorageException tooLarge(long bytes, long max) {
        return new ArtifactStorageException("PAYLOAD_TOO_LARGE", "产物 " + bytes + " 字节超过上限 " + max);
    }

    public static ArtifactStorageException unsupportedMime(String mime) {
        return new ArtifactStorageException("VALIDATION_FAILED", "不支持的 mime: " + mime);
    }
}
