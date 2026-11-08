package com.larder.ingest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从幂等响应快照里读字段。
 *
 * <p>快照只有 {@code itemId/jobId/dedupe} 这类服务端生成的 ASCII 值，用定向正则读取即可；
 * 不在这里引入通用 JSON 解析器（避免为读三个字段引入新依赖）。
 */
public final class JsonValue {

    private JsonValue() {
    }

    public static String read(String json, String key) {
        if (json == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
