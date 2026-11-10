package com.larder.pipeline.reconcile;

import com.larder.pipeline.lease.JobLeaseMapper;
import com.larder.pipeline.lease.JobLeaseRow;
import com.larder.pipeline.model.OutboxEvent;
import com.larder.pipeline.model.OutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

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

/** 对账：过期租约要么重派（写 outbox），要么在达到上限后 FAILED。 */
class JobReconcilerTest {

    private JobLeaseMapper leaseMapper;
    private OutboxEventMapper outboxMapper;
    private JobReconciler reconciler;

    @BeforeEach
    void setUp() {
        leaseMapper = mock(JobLeaseMapper.class);
        outboxMapper = mock(OutboxEventMapper.class);
        reconciler = new JobReconciler(leaseMapper, org.mockito.Mockito.mock(com.larder.pipeline.lease.JobEventLog.class), outboxMapper, 3);
    }

    @Test
    @DisplayName("首次过期：回 QUEUED 并写一条 job.queued 事件")
    void requeuesExpiredJob() {
        when(leaseMapper.selectExpired(50)).thenReturn(List.of(row(1)));
        when(leaseMapper.reclaim(eq("job_1"), eq(1L), eq("QUEUED"), eq(2), any(), eq(null))).thenReturn(1);

        JobReconciler.Result result = reconciler.runOnce();

        assertThat(result.requeued()).isEqualTo(1);
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxMapper).insert(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("job.queued.ingest");
        assertThat(event.getValue().getPayloadJson()).contains("job_1");
    }

    @Test
    @DisplayName("达到上限：FAILED，不再写事件")
    void failsAfterMaxAttempts() {
        when(leaseMapper.selectExpired(50)).thenReturn(List.of(row(2)));
        when(leaseMapper.reclaim(eq("job_1"), eq(1L), eq("FAILED"), eq(3), any(),
                eq("LEASE_EXPIRED"))).thenReturn(1);

        JobReconciler.Result result = reconciler.runOnce();

        assertThat(result.failed()).isEqualTo(1);
        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("收回时被别人抢先（0 行）：不写事件、不计入重派")
    void skipsWhenTakeoverRaced() {
        when(leaseMapper.selectExpired(50)).thenReturn(List.of(row(1)));
        when(leaseMapper.reclaim(anyString(), anyLong(), anyString(), anyInt(), any(), any())).thenReturn(0);

        JobReconciler.Result result = reconciler.runOnce();

        assertThat(result.requeued()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
    }

    private JobLeaseRow row(int attempt) {
        JobLeaseRow row = new JobLeaseRow();
        row.setJobId("job_1");
        row.setOwnerId("own_1");
        row.setState("RUNNING");
        row.setAttempt(attempt);
        row.setLeaseToken(1L);
        return row;
    }
}
