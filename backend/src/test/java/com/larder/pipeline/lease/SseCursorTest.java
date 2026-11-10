package com.larder.pipeline.lease;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S05：snapshot 与 events 竞态下的续传决策。 */
class SseCursorTest {

    @Test
    @DisplayName("无 cursor：全新连接，只发 snapshot")
    void freshWithoutCursor() {
        assertThat(SseCursor.decide(null, 5, 0)).isEqualTo(SseCursor.Decision.FRESH);
    }

    @Test
    @DisplayName("有后续事件：回放，不重置")
    void replaysWhenEventsExist() {
        assertThat(SseCursor.decide(3L, 5, 2)).isEqualTo(SseCursor.Decision.REPLAY);
    }

    @Test
    @DisplayName("cursor 落后但日志为空：历史不可用，必须 reset")
    void resetsWhenHistoryMissing() {
        assertThat(SseCursor.decide(1L, 5, 0)).isEqualTo(SseCursor.Decision.RESET);
    }

    @Test
    @DisplayName("cursor 不落后（相等/超前）：按全新连接，避免重复回放")
    void freshWhenCursorCurrentOrAhead() {
        assertThat(SseCursor.decide(5L, 5, 0)).isEqualTo(SseCursor.Decision.FRESH);
        assertThat(SseCursor.decide(9L, 5, 0)).isEqualTo(SseCursor.Decision.FRESH);
    }
}
