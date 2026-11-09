package com.larder.pipeline.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 投递状态更新的 fencing 与死信判定（用 Mapper mock，不连数据库）。 */
class OutboxStoreTest {

    private OutboxRelayMapper mapper;
    private OutboxStore store;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mapper = mock(OutboxRelayMapper.class);
        store = new OutboxStore(mapper);
    }

    @Test
    @DisplayName("租约被接管后，旧 token 的 confirm 更新 0 行（fencing）")
    void staleTokenCannotMarkSent() {
        when(mapper.markSent("out_1", 5L)).thenReturn(0);

        assertThat(store.markSent("out_1", 5L)).isZero();
        verify(mapper).markSent("out_1", 5L);
    }

    @Test
    @DisplayName("失败次数达到上限：标 DEAD")
    void marksDeadAfterMaxAttempts() {
        OutboxRow current = row(4, 7L);
        when(mapper.selectByEventId("out_1")).thenReturn(current);
        when(mapper.markOutcome(eq("out_1"), eq(7L), eq(5), eq("DEAD"), any())).thenReturn(1);

        assertThat(store.markFailed("out_1", 7L, 5)).isEqualTo(1);
        verify(mapper).markOutcome(eq("out_1"), eq(7L), eq(5), eq("DEAD"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("未到上限：回 PENDING，available_at 按退避后移")
    void schedulesRetryWithBackoff() {
        OutboxRow current = row(1, 7L);
        when(mapper.selectByEventId("out_1")).thenReturn(current);
        when(mapper.markOutcome(eq("out_1"), eq(7L), eq(2), eq("PENDING"), any())).thenReturn(1);

        store.markFailed("out_1", 7L, 5);

        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).markOutcome(eq("out_1"), eq(7L), eq(2), eq("PENDING"), at.capture());
        // attempt=2 → 退避 2 秒
        assertThat(at.getValue()).isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(1));
    }

    @Test
    @DisplayName("token 不匹配：不更新任何行")
    void mismatchedTokenIsIgnored() {
        when(mapper.selectByEventId("out_1")).thenReturn(row(1, 6L));

        assertThat(store.markFailed("out_1", 5L, 5)).isZero();
        verify(mapper, never()).markOutcome(anyString(), anyLong(), anyInt(), anyString(), any());
    }

    private OutboxRow row(int attempt, long token) {
        OutboxRow row = new OutboxRow();
        row.setEventId("out_1");
        row.setAttempt(attempt);
        row.setRelayToken(token);
        row.setState("PUBLISHING");
        return row;
    }
}
