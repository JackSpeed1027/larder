package com.larder.content.publication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 绑定事务（S03）：事务内复核 fencing，任一条件不满足即整体失败。 */
class RevisionBinderTest {

    private PublicationMapper mapper;
    private RevisionBinder binder;

    @BeforeEach
    void setUp() {
        mapper = mock(PublicationMapper.class);
        binder = new RevisionBinder(mapper);
    }

    private JobPublishRow job(String state, String runId, long epoch, LocalDateTime cancelledAt) {
        JobPublishRow row = new JobPublishRow();
        row.setJobId("job_1");
        row.setOwnerId("own_1");
        row.setItemId("itm_1");
        row.setState(state);
        row.setRunId(runId);
        row.setProcessingEpoch(epoch);
        row.setCancelRequestedAt(cancelledAt);
        return row;
    }

    @Test
    @DisplayName("全部条件满足：写 revision、绑产物、移指针、提交 job")
    void bindsRevision() {
        when(mapper.lockJob("job_1")).thenReturn(job("RUNNING", "run_1", 2, null));
        when(mapper.maxRevisionNo("own_1", "itm_1")).thenReturn(3L);
        when(mapper.pointCurrentRevision(eq("own_1"), eq("itm_1"), anyString())).thenReturn(1);
        when(mapper.commitJob("job_1", "run_1", 2)).thenReturn(1);

        PublicationResult result = binder.bind("job_1", "run_1", 2, "art_1", "a".repeat(64),
                "extract-v1", "chunk-v1", "{\"chunks\":0}");

        assertThat(result.dedupe()).isEqualTo("CREATED");
        verify(mapper).insertRevision(anyString(), eq("own_1"), eq("itm_1"), eq(4L), eq("a".repeat(64)),
                eq("extract-v1"), eq("chunk-v1"), anyString(), anyString(), any(LocalDateTime.class));
        verify(mapper).bindBodyArtifact("own_1", result.revisionId(), "art_1");
        verify(mapper).pointCurrentRevision("own_1", "itm_1", result.revisionId());
        verify(mapper).commitJob("job_1", "run_1", 2);
    }

    @Test
    @DisplayName("已请求取消：拒绝发布")
    void rejectsCancelled() {
        when(mapper.lockJob("job_1")).thenReturn(job("RUNNING", "run_1", 2, LocalDateTime.now()));

        assertThatThrownBy(() -> binder.bind("job_1", "run_1", 2, "art_1", "a".repeat(64),
                "extract-v1", "chunk-v1", "{}"))
                .isInstanceOf(PublicationException.class);
    }

    @Test
    @DisplayName("epoch 不一致：拒绝旧代次")
    void rejectsSupersededEpoch() {
        when(mapper.lockJob("job_1")).thenReturn(job("RUNNING", "run_1", 3, null));

        assertThatThrownBy(() -> binder.bind("job_1", "run_1", 2, "art_1", "a".repeat(64),
                "extract-v1", "chunk-v1", "{}"))
                .isInstanceOf(PublicationException.class);
    }

    @Test
    @DisplayName("item 指针更新 0 行（已删除/归属变化）：整体失败")
    void failsWhenPointerNotUpdated() {
        when(mapper.lockJob("job_1")).thenReturn(job("RUNNING", "run_1", 2, null));
        when(mapper.maxRevisionNo("own_1", "itm_1")).thenReturn(0L);
        when(mapper.pointCurrentRevision(anyString(), anyString(), anyString())).thenReturn(0);

        assertThatThrownBy(() -> binder.bind("job_1", "run_1", 2, "art_1", "a".repeat(64),
                "extract-v1", "chunk-v1", "{}"))
                .isInstanceOf(PublicationException.class);
    }

    @Test
    @DisplayName("提交 job 0 行且期间被取消：报 cancelledConflict 而不是通用冲突")
    void reportsCancelWhenCommitLoses() {
        when(mapper.lockJob("job_1")).thenReturn(job("RUNNING", "run_1", 2, null))
                .thenReturn(job("RUNNING", "run_1", 2, LocalDateTime.now()));
        when(mapper.maxRevisionNo("own_1", "itm_1")).thenReturn(0L);
        when(mapper.pointCurrentRevision(anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.commitJob("job_1", "run_1", 2)).thenReturn(0);

        assertThatThrownBy(() -> binder.bind("job_1", "run_1", 2, "art_1", "a".repeat(64),
                "extract-v1", "chunk-v1", "{}"))
                .isInstanceOf(PublicationException.class)
                .hasMessageContaining("取消");
    }
}
