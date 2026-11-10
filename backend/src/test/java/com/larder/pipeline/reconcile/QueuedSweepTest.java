package com.larder.pipeline.reconcile;

import com.larder.pipeline.lease.JobLeaseMapper;
import com.larder.pipeline.lease.JobLeaseRow;
import com.larder.pipeline.model.OutboxEvent;
import com.larder.pipeline.model.OutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S04：QUEUED 补偿扫描——只在没有未投递事件时补发，并写防抖时间。 */
class QueuedSweepTest {

    private JobLeaseMapper leaseMapper;
    private OutboxEventMapper outboxMapper;
    private JobReconciler reconciler;

    @BeforeEach
    void setUp() {
        leaseMapper = mock(JobLeaseMapper.class);
        outboxMapper = mock(OutboxEventMapper.class);
        reconciler = new JobReconciler(leaseMapper, outboxMapper, 5);
        when(leaseMapper.selectExpired(50)).thenReturn(List.of());
    }

    private JobLeaseRow row() {
        JobLeaseRow row = new JobLeaseRow();
        row.setJobId("job_q1");
        row.setOwnerId("own_1");
        row.setState("QUEUED");
        row.setAttempt(1);
        return row;
    }

    @Test
    @DisplayName("QUEUED 到期且无 PENDING 事件：补发并防抖")
    void compensatesQueuedWithoutPendingEvent() {
        when(leaseMapper.selectQueuedDue(50)).thenReturn(List.of(row()));
        when(leaseMapper.countPendingOutbox("job_q1")).thenReturn(0);

        JobReconciler.Result result = reconciler.runOnce();

        assertThat(result.compensated()).isEqualTo(1);
        verify(outboxMapper).insert(any(OutboxEvent.class));
        verify(leaseMapper).markReconcile(eq("job_q1"), any());
    }

    @Test
    @DisplayName("已有 PENDING 事件：不重复补发，只写防抖")
    void skipsWhenPendingEventExists() {
        when(leaseMapper.selectQueuedDue(50)).thenReturn(List.of(row()));
        when(leaseMapper.countPendingOutbox("job_q1")).thenReturn(1);

        JobReconciler.Result result = reconciler.runOnce();

        assertThat(result.compensated()).isZero();
        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
        verify(leaseMapper).markReconcile(eq("job_q1"), any());
    }
}
