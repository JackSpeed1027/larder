package com.larder.pipeline.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/** S04：退避必须单调、封顶，jitter 不能突破上限。 */
class OutboxBackoffTest {

    @Test
    @DisplayName("指数增长且封顶 60s")
    void growsAndCaps() {
        assertThat(OutboxBackoff.delaySeconds(1)).isEqualTo(1);
        assertThat(OutboxBackoff.delaySeconds(2)).isEqualTo(2);
        assertThat(OutboxBackoff.delaySeconds(3)).isEqualTo(4);
        assertThat(OutboxBackoff.delaySeconds(10)).isEqualTo(60);
        assertThat(OutboxBackoff.delaySeconds(50)).isEqualTo(60);
        for (int attempt = 2; attempt <= 12; attempt++) {
            assertThat(OutboxBackoff.delaySeconds(attempt))
                    .isGreaterThanOrEqualTo(OutboxBackoff.delaySeconds(attempt - 1));
        }
    }

    @Test
    @DisplayName("jitter 在 [base, base*1.25] 内且不超过上限")
    void jitterWithinBounds() {
        RandomGenerator fixed = new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public long nextLong(long bound) {
                return bound - 1;
            }
        };
        for (int attempt = 1; attempt <= 10; attempt++) {
            long base = OutboxBackoff.delaySeconds(attempt);
            long jittered = OutboxBackoff.delaySecondsWithJitter(attempt, fixed);
            assertThat(jittered).isBetween(base, Math.min(60L, base + base / 4));
        }
    }

    @Test
    @DisplayName("attempt 达到上限即 exhausted")
    void exhaustsAtMax() {
        assertThat(OutboxBackoff.exhausted(1, 5)).isFalse();
        assertThat(OutboxBackoff.exhausted(4, 5)).isFalse();
        assertThat(OutboxBackoff.exhausted(5, 5)).isTrue();
    }
}
