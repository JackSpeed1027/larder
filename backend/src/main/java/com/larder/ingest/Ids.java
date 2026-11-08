package com.larder.ingest;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 服务端不透明 ID 生成。
 *
 * <p>契约要求 ID 形如 {@code ^[A-Za-z][A-Za-z0-9_-]{1,63}$} 且客户端不得解析前缀取权限；
 * 这里用"字母前缀 + 随机 hex"，前缀只用于人眼排查，不参与任何授权判断。
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /** 生成 itm_/job_/out_ 这类 ID。 */
    public static String next(String prefix) {
        byte[] bytes = new byte[10];
        RANDOM.nextBytes(bytes);
        return prefix + "_" + HexFormat.of().formatHex(bytes);
    }
}
