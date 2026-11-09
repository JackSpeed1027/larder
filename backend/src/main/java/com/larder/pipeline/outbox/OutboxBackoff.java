package com.larder.pipeline.outbox;

/**
 * 重试退避：指数增长并封顶。
 *
 * <p>放在独立类里是为了可测：退避写错会让死信永远不到、或把故障瞬间放大成风暴。
 * attempt 是"已经失败的次数"（从 1 开始）。
 */
public final class OutboxBackoff {

    /** 单次退避上限，避免长时间不重试。 */
    static final long MAX_DELAY_SECONDS = 60L;

    private OutboxBackoff() {
    }

    public static long delaySeconds(int attempt) {
        if (attempt <= 0) {
            return 1L;
        }
        long delay = 1L << Math.min(attempt - 1, 10);
        return Math.min(delay, MAX_DELAY_SECONDS);
    }

    public static boolean exhausted(int attempt, int maxAttempts) {
        return attempt >= maxAttempts;
    }
}
