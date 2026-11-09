package com.larder.pipeline.lease;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 租约与 fencing 提交：旧 token / 旧 epoch / 旧 runId 都必须被拒绝。 */
class JobLeaseServiceTest {

    private JobLeaseMapper mapper;
    private JobLeaseService service;

    @BeforeEach
    void setUp() {
        mapper = mock(JobLeaseMapper.class);
        service = new JobLeaseService(mapper, 30);
    }

    @Test
    @DisplayName("续租成功/失败直接映射影响行数")
    void renewMapsRows() {
        when(mapper.renew(eq("job_1"), eq("w1"), eq(3L), any())).thenReturn(1);
        when(mapper.renew(eq("job_1"), eq("w1"), eq(2L), any())).thenReturn(0);

        assertThat(service.renew("job_1", "w1", 3L)).isTrue();
        assertThat(service.renew("job_1", "w1", 2L)).isFalse();
    }

    @Test
    @DisplayName("startRun：已有 runId 直接复用（稳定 runId）")
    void reusesExistingRunId() {
        JobLeaseRow row = row("RUNNING", 1, "run_existing", 1L);
        when(mapper.findJob("job_1")).thenReturn(row);

        assertThat(service.startRun("job_1", "w1", 1L)).isEqualTo("run_existing");
    }

    @Test
    @DisplayName("startRun：没有 runId 时分配新的并返回")
    void assignsNewRunId() {
        when(mapper.findJob("job_1")).thenReturn(row("RUNNING", 1, null, 1L));
        when(mapper.assignRunId(eq("job_1"), eq("w1"), eq(1L), anyString())).thenReturn(1);

        String runId = service.startRun("job_1", "w1", 1L);

        assertThat(runId).startsWith("run_");
        verify(mapper).assignRunId(eq("job_1"), eq("w1"), eq(1L), eq(runId));
    }

    @Test
    @DisplayName("startRun：租约被接管（assign 0 行且仍无 runId）→ StaleLeaseException")
    void rejectsStaleLeaseOnStart() {
        when(mapper.findJob("job_1")).thenReturn(row("RUNNING", 2, null, 1L));
        when(mapper.assignRunId(anyString(), anyString(), anyLong(), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.startRun("job_1", "w1", 1L))
                .isInstanceOf(StaleLeaseException.class);
    }

    @Test
    @DisplayName("成功提交带 token+epoch+runId，0 行即拒绝")
    void commitSuccessRequiresFencing() {
        when(mapper.commitSuccess("job_1", "w1", 3L, 0L, "run_1")).thenReturn(1);
        when(mapper.commitSuccess("job_1", "w1", 2L, 0L, "run_1")).thenReturn(0);

        assertThat(service.commitSuccess("job_1", "w1", 3L, 0L, "run_1")).isTrue();
        assertThat(service.commitSuccess("job_1", "w1", 2L, 0L, "run_1")).isFalse();
    }

    @Test
    @DisplayName("失败提交：错误码 NEEDS_DECISION 映射为同名状态")
    void commitFailureMapsState() {
        when(mapper.commitFailure(eq("job_1"), eq("w1"), eq(3L), eq(0L), eq("run_1"),
                eq("NEEDS_DECISION"), eq("NEEDS_DECISION"), any())).thenReturn(1);

        assertThat(service.commitFailure("job_1", "w1", 3L, 0L, "run_1", "NEEDS_DECISION", "需人工确认"))
                .isTrue();
    }

    @Test
    @DisplayName("progress 映射所有字段，且终态可识别")
    void progressMapsFields() {
        JobLeaseRow row = row("SUCCEEDED", 2, "run_1", 4L);
        row.setLastEventSeq(9L);
        row.setErrorCode(null);
        when(mapper.findJob("job_1")).thenReturn(row);

        JobProgress progress = service.progress("job_1");

        assertThat(progress.state()).isEqualTo("SUCCEEDED");
        assertThat(progress.seq()).isEqualTo(9);
        assertThat(progress.terminal()).isTrue();
    }

    private JobLeaseRow row(String state, int attempt, String runId, long token) {
        JobLeaseRow row = new JobLeaseRow();
        row.setJobId("job_1");
        row.setOwnerId("own_1");
        row.setState(state);
        row.setAttempt(attempt);
        row.setRunId(runId);
        row.setProcessingEpoch(0L);
        row.setLeaseOwner("w1");
        row.setLeaseToken(token);
        row.setLastEventSeq(1L);
        return row;
    }
}
