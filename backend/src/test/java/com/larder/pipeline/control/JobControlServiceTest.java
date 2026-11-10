package com.larder.pipeline.control;

import com.larder.ingest.OwnerService;
import com.larder.pipeline.model.OutboxEvent;
import com.larder.pipeline.model.OutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 取消/重试的边界：只有进行中可取消，只有失败/取消可重试，重试会重新派发。 */
class JobControlServiceTest {

    private JobControlMapper mapper;
    private OutboxEventMapper outboxMapper;
    private JobControlService service;

    @BeforeEach
    void setUp() {
        mapper = mock(JobControlMapper.class);
        outboxMapper = mock(OutboxEventMapper.class);
        OwnerService ownerService = mock(OwnerService.class);
        when(ownerService.ownerIdFor("admin")).thenReturn("own_1");
        service = new JobControlService(mapper, outboxMapper, ownerService, org.mockito.Mockito.mock(com.larder.pipeline.lease.JobEventLog.class));
    }

    @Test
    @DisplayName("RUNNING 任务取消：写 cancel_requested_at，状态不变")
    void cancelsRunning() {
        when(mapper.findJob("job_1")).thenReturn(job("RUNNING"));
        when(mapper.requestCancel("job_1", "own_1")).thenReturn(1);
        when(mapper.findJob("job_1")).thenReturn(Map.of("id", "job_1", "ownerId", "own_1", "state", "RUNNING", "cancelRequestedAt", "t"));

        Map<String, Object> after = service.cancel("admin", "job_1");

        assertThat(after.get("state")).isEqualTo("RUNNING");
        verify(mapper).requestCancel("job_1", "own_1");
    }

    @Test
    @DisplayName("已结束任务取消：409")
    void rejectsCancelOnTerminal() {
        when(mapper.findJob("job_1")).thenReturn(job("SUCCEEDED"));
        assertThatThrownBy(() -> service.cancel("admin", "job_1"))
                .isInstanceOf(JobControlService.ControlException.class)
                .satisfies(ex -> assertThat(((JobControlService.ControlException) ex).status()).isEqualTo(409));
        verify(mapper, never()).requestCancel(anyString(), anyString());
    }

    @Test
    @DisplayName("RUNNING 任务不能重试：409，不写 outbox")
    void rejectsRetryOnRunning() {
        when(mapper.findJob("job_1")).thenReturn(job("RUNNING"));
        assertThatThrownBy(() -> service.retry("admin", "job_1"))
                .isInstanceOf(JobControlService.ControlException.class);
        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("FAILED 任务重试：置回 QUEUED 并重新派发事件")
    void retriesFailed() {
        when(mapper.findJob("job_1")).thenReturn(job("FAILED"),
                Map.of("id", "job_1", "ownerId", "own_1", "state", "QUEUED"));
        when(mapper.requeue("job_1", "own_1")).thenReturn(1);

        service.retry("admin", "job_1");

        verify(outboxMapper).insert(any(OutboxEvent.class));
        verify(mapper).requeue("job_1", "own_1");
    }

    @Test
    @DisplayName("别人的 job：404")
    void hidesForeignJob() {
        when(mapper.findJob("job_1")).thenReturn(Map.of("id", "job_1", "ownerId", "own_other"));
        assertThatThrownBy(() -> service.cancel("admin", "job_1"))
                .isInstanceOf(JobControlService.ControlException.class)
                .satisfies(ex -> assertThat(((JobControlService.ControlException) ex).status()).isEqualTo(404));
    }

    @Test
    @DisplayName("列表 limit 收敛到 100")
    void clampsLimit() {
        when(mapper.listJobs(eq("own_1"), any(), any(), eq(100), eq(0))).thenReturn(List.of());
        when(mapper.countJobs(eq("own_1"), any(), any())).thenReturn(0L);
        assertThat(service.list("admin", null, null, 5000, 0).get("limit")).isEqualTo(100);
    }

    private Map<String, Object> job(String state) {
        return Map.of("id", "job_1", "ownerId", "own_1", "state", state);
    }
}
