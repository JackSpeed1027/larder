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

    /**
     * 带 jitter 的退避：在 [base, base*1.25] 内随机，避免大量任务同一秒重试形成风暴。
     * 仍然封顶 MAX_DELAY_SECONDS；调用方注入随机源以便测试可复现。
     */
    public static long delaySecondsWithJitter(int attempt, java.util.random.RandomGenerator random) {
        long base = delaySeconds(attempt);
        long extra = base <= 3 ? 0 : random.nextLong(0, base / 4 + 1);
        return Math.min(base + extra, MAX_DELAY_SECONDS);
    }

    public static boolean exhausted(int attempt, int maxAttempts) {
        return attempt >= maxAttempts;
    }
}
