package com.larder.pipeline.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投递器状态机：只有 ACKED 才能标 SENT。
 *
 * <p>四个用例分别对应任务卡验收：confirm 丢失（超时）、unroutable、broker 异常、
 * 以及正常 ack。任何一条走错都会让"消息丢了但状态显示已发"。
 */
class OutboxRelayTest {

    private OutboxStore store;
    private OutboxPublisher publisher;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        store = mock(OutboxStore.class);
        publisher = mock(OutboxPublisher.class);
        relay = new OutboxRelay(store, publisher, "relay-test", 10, 30, 5);
        when(store.reclaimExpired(5)).thenReturn(0);
        when(store.claim("relay-test", 10, 30)).thenReturn(List.of(row("out_1", 7L)));
    }

    @Test
    @DisplayName("confirm ack：标 SENT")
    void marksSentOnAck() {
        when(publisher.publish(org.mockito.ArgumentMatchers.any())).thenReturn(PublishOutcome.ACKED);
        when(store.markSent("out_1", 7L)).thenReturn(1);

        OutboxRelay.Tick tick = relay.runOnce();

        verify(store).markSent("out_1", 7L);
        verify(store, never()).markFailed(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
        org.assertj.core.api.Assertions.assertThat(tick.sent()).isEqualTo(1);
    }

    @Test
    @DisplayName("unroutable（basic.return）：不能标 SENT，必须走退避")
    void neverMarksSentWhenUnroutable() {
        when(publisher.publish(org.mockito.ArgumentMatchers.any())).thenReturn(PublishOutcome.UNROUTABLE);
        when(store.markFailed("out_1", 7L, 5)).thenReturn(1);

        OutboxRelay.Tick tick = relay.runOnce();

        verify(store, never()).markSent(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(store).markFailed("out_1", 7L, 5);
        org.assertj.core.api.Assertions.assertThat(tick.retried()).isEqualTo(1);
    }

    @Test
    @DisplayName("confirm 超时：视为结果未知，不标 SENT，允许重投")
    void timeoutLeadsToRetry() {
        when(publisher.publish(org.mockito.ArgumentMatchers.any())).thenReturn(PublishOutcome.TIMEOUT);
        when(store.markFailed(eq("out_1"), eq(7L), eq(5))).thenReturn(1);

        relay.runOnce();

        verify(store, never()).markSent(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(store).markFailed("out_1", 7L, 5);
    }

    @Test
    @DisplayName("broker 抛异常：按 NACKED 处理，不标 SENT")
    void exceptionIsTreatedAsNack() {
        when(publisher.publish(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("connection refused"));
        when(store.markFailed("out_1", 7L, 5)).thenReturn(1);

        relay.runOnce();

        verify(store, never()).markSent(anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(store).markFailed("out_1", 7L, 5);
    }

    @Test
    @DisplayName("每轮先收回过期租约，再领取")
    void reclaimsBeforeClaim() {
        when(publisher.publish(org.mockito.ArgumentMatchers.any())).thenReturn(PublishOutcome.ACKED);
        when(store.markSent("out_1", 7L)).thenReturn(1);

        relay.runOnce();

        verify(store).reclaimExpired(5);
        verify(store).claim("relay-test", 10, 30);
    }

    private OutboxRow row(String eventId, long token) {
        OutboxRow row = new OutboxRow();
        row.setEventId(eventId);
        row.setOwnerId("own_1");
        row.setJobId("job_1");
        row.setEventType("job.queued.ingest");
        row.setPayloadJson("{\"jobId\":\"job_1\"}");
        row.setPayloadHash("a".repeat(64));
        row.setState("PUBLISHING");
        row.setAttempt(0);
        row.setRelayToken(token);
        return row;
    }
}
