package com.larder.pipeline.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 退避与死信阈值：算错会让死信永远不到，或在故障时放大重试风暴。 */
class OutboxBackoffTest {

    @Test
    @DisplayName("指数退避：1,2,4,8… 并封顶 60 秒")
    void growsExponentiallyAndCaps() {
        assertThat(OutboxBackoff.delaySeconds(1)).isEqualTo(1);
        assertThat(OutboxBackoff.delaySeconds(2)).isEqualTo(2);
        assertThat(OutboxBackoff.delaySeconds(3)).isEqualTo(4);
        assertThat(OutboxBackoff.delaySeconds(4)).isEqualTo(8);
        assertThat(OutboxBackoff.delaySeconds(20)).isEqualTo(60);
    }

    @Test
    @DisplayName("达到最大次数才算耗尽")
    void exhaustion() {
        assertThat(OutboxBackoff.exhausted(4, 5)).isFalse();
        assertThat(OutboxBackoff.exhausted(5, 5)).isTrue();
    }
}
