package com.larder.content.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 对象 key 与内容 hash 的派生规则。
 *
 * <p>key 只由 owner + sha256 决定，**不含用户可控路径**（docs/16：禁止用户任意路径）：
 * <pre>artifacts/{ownerId}/{sha256 前两位}/{sha256}</pre>
 * 这样同一个内容天然幂等复用，且无法用 key 遍历他人对象。
 */
public final class ArtifactKeys {

    private ArtifactKeys() {
    }

    public static String forHash(String ownerId, String sha256) {
        return "artifacts/" + ownerId + "/" + sha256.substring(0, 2) + "/" + sha256;
    }

    /** 计算真实内容 sha256（十六进制小写）；绝不使用 multipart ETag 充当内容 hash。 */
    public static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
