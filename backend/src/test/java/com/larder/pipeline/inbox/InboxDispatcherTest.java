package com.larder.pipeline.inbox;

import com.larder.pipeline.lease.JobLeaseService;
import com.larder.runner.client.RunnerClient;
import com.larder.runner.client.RunnerUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 派发：RUNNING 才派发；失败必须释放回 QUEUED 并重抛（不能假装已处理）。 */
class InboxDispatcherTest {

    private InboxMapper mapper;
    private JobLeaseService leaseService;
    private RunnerClient runnerClient;
    private InboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        mapper = mock(InboxMapper.class);
        leaseService = mock(JobLeaseService.class);
        runnerClient = mock(RunnerClient.class);
        dispatcher = new InboxDispatcher(mapper, leaseService, runnerClient, "worker-test");
    }

    @Test
    @DisplayName("RUNNING：分配 runId 并调用 Runner")
    void dispatchesRunningJob() {
        when(mapper.findDispatch("job_1")).thenReturn(row("RUNNING", 5L));
        when(leaseService.startRun("job_1", "worker-test", 5L)).thenReturn("run_1");

        assertThat(dispatcher.dispatch("job_1")).isEqualTo("run_1");
        verify(runnerClient).startRun("run_1", "job_1", "own_1", "itm_1", 0L, "{\"url\":\"https://example.com\"}");
    }

    @Test
    @DisplayName("非 RUNNING：不派发，返回 null")
    void skipsNonRunning() {
        when(mapper.findDispatch("job_1")).thenReturn(row("SUCCEEDED", 2L));

        assertThat(dispatcher.dispatch("job_1")).isNull();
        verify(runnerClient, never()).startRun(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("Runner 不可用：job 回 QUEUED 且异常上抛（让 MQ 重投）")
    void releasesOnFailure() {
        when(mapper.findDispatch("job_1")).thenReturn(row("RUNNING", 7L));
        when(leaseService.startRun("job_1", "worker-test", 7L)).thenReturn("run_2");
        org.mockito.Mockito.doThrow(new RunnerUnavailableException("connection refused"))
                .when(runnerClient).startRun("run_2", "job_1", "own_1", "itm_1", 0L, "{\"url\":\"https://example.com\"}");

        assertThatThrownBy(() -> dispatcher.dispatch("job_1"))
                .isInstanceOf(RunnerUnavailableException.class);
        verify(mapper).releaseForRetry("job_1", 7L);
    }

    private Map<String, Object> row(String state, long token) {
        Map<String, Object> row = new HashMap<>();
        row.put("state", state);
        row.put("token", token);
        row.put("inputJson", "{\"url\":\"https://example.com\"}");
        row.put("ownerId", "own_1");
        row.put("processingEpoch", 0L);
        row.put("itemId", "itm_1");
        return row;
    }
}
