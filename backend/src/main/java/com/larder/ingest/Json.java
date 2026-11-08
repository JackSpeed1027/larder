package com.larder.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 极小的 JSON 写入/哈希工具。
 *
 * <p>只用于写 job.input_json、outbox.payload_json 与幂等响应快照：结构固定、字段少，
 * 所以不引入完整 JSON 库；用户可控字符串一律经过 {@link #escape(String)}，
 * 避免标题里的引号/换行破坏 JSON（有对应单测）。
 */
public final class Json {

    private Json() {
    }

    /** 转义 JSON 字符串内容（不含外层引号）。 */
    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** 规范化 JSON：固定字段顺序，保证同一语义得到同一字节序列（幂等哈希依赖它）。 */
    public static String canonical(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("key/value 必须成对");
        }
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(escape(keyValues[i])).append("\":");
            String value = keyValues[i + 1];
            if (value == null) {
                out.append("null");
            } else {
                out.append('"').append(escape(value)).append('"');
            }
        }
        return out.append('}').toString();
    }

    /** SHA-256 十六进制小写。 */
    public static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
