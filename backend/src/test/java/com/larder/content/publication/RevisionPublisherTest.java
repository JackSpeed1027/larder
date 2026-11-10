package com.larder.content.publication;

import com.larder.content.storage.ArtifactService;
import com.larder.content.storage.ArtifactView;
import com.larder.content.storage.OrphanRecorder;
import com.larder.runner.ingest.RunnerResultRequest;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 发布编排（S03）：外部上传在事务外，绑定交给 RevisionBinder。 */
class RevisionPublisherTest {

    private static final String SHA =
            com.larder.content.storage.ArtifactKeys.sha256("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private PublicationMapper mapper;
    private ArtifactService artifactService;
    private RevisionBinder binder;
    private OrphanRecorder orphanRecorder;
    private RevisionPublisher publisher;

    @BeforeEach
    void setUp() {
        mapper = mock(PublicationMapper.class);
        artifactService = mock(ArtifactService.class);
        binder = mock(RevisionBinder.class);
        orphanRecorder = mock(OrphanRecorder.class);
        publisher = new RevisionPublisher(mapper, artifactService, binder, orphanRecorder);
    }

    private RunnerResultRequest request(String jobId, String runId, long epoch, String sha) {
        return new RunnerResultRequest(jobId, runId, epoch, "hello", sha, "extract-v1", "chunk-v1", "{\"chunks\":0}");
    }

    private JobPublishRow job(String state, String runId, long epoch) {
        JobPublishRow row = new JobPublishRow();
        row.setJobId("job_1");
        row.setOwnerId("own_1");
        row.setItemId("itm_1");
        row.setState(state);
        row.setRunId(runId);
        row.setProcessingEpoch(epoch);
        return row;
    }

    @Test
    @DisplayName("正常发布：先上传（事务外）再绑定，返回 CREATED")
    void publishesRevision() {
        when(mapper.findJob("job_1")).thenReturn(job("RUNNING", "run_1", 2));
        when(artifactService.store(eq("own_1"), any(), anyString(), eq("text/plain")))
                .thenReturn(new ArtifactView("art_1", SHA, 5, "text/plain", "own_1/" + SHA, "VERIFIED"));
        when(binder.bind("job_1", "run_1", 2, "art_1", SHA, "extract-v1", "chunk-v1", "{\"chunks\":0}"))
                .thenReturn(new PublicationResult("rev_1", "CREATED"));

        PublicationResult result = publisher.publish(request("job_1", "run_1", 2, SHA));

        assertThat(result).isEqualTo(new PublicationResult("rev_1", "CREATED"));
        verify(artifactService).store(eq("own_1"), any(), eq(SHA), eq("text/plain"));
        verify(binder).bind("job_1", "run_1", 2, "art_1", SHA, "extract-v1", "chunk-v1", "{\"chunks\":0}");
    }

    @Test
    @DisplayName("epoch 过期：不写对象、不进绑定事务")
    void rejectsSupersededEpoch() {
        when(mapper.findJob("job_1")).thenReturn(job("RUNNING", "run_1", 1));

        assertThatThrownBy(() -> publisher.publish(request("job_1", "run_1", 0, SHA)))
                .isInstanceOf(PublicationException.class);
        verifyNoInteractions(artifactService, binder);
    }

    @Test
    @DisplayName("runId 不一致：拒绝旧 worker")
    void rejectsStaleRun() {
        when(mapper.findJob("job_1")).thenReturn(job("RUNNING", "run_2", 0));

        assertThatThrownBy(() -> publisher.publish(request("job_1", "run_1", 0, SHA)))
                .isInstanceOf(PublicationException.class);
        verifyNoInteractions(artifactService, binder);
    }

    @Test
    @DisplayName("已 SUCCEEDED：同 hash 重放，不重复上传")
    void replaysExistingRevision() {
        when(mapper.findJob("job_1")).thenReturn(job("SUCCEEDED", "run_1", 0));
        when(mapper.findRevisionBySource("own_1", "itm_1", SHA)).thenReturn("rev_old");

        PublicationResult result = publisher.publish(request("job_1", "run_1", 0, SHA));

        assertThat(result).isEqualTo(new PublicationResult("rev_old", "REPLAYED"));
        verifyNoInteractions(artifactService, binder);
    }

    @Test
    @DisplayName("sha256 不一致：拒绝，不上传")
    void rejectsHashMismatch() {
        when(mapper.findJob("job_1")).thenReturn(job("RUNNING", "run_1", 0));

        assertThatThrownBy(() -> publisher.publish(request("job_1", "run_1", 0, "b".repeat(64))))
                .isInstanceOf(PublicationException.class);
        verifyNoInteractions(artifactService, binder);
    }

    @Test
    @DisplayName("job 不存在：404 语义")
    void rejectsMissingJob() {
        when(mapper.findJob("job_1")).thenReturn(null);

        assertThatThrownBy(() -> publisher.publish(request("job_1", "run_1", 0, SHA)))
                .isInstanceOf(PublicationException.class);
        verify(artifactService, never()).store(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("绑定失败：对象已上传 → 登记孤立对象并上抛")
    void recordsOrphanWhenBindFails() {
        when(mapper.findJob("job_1")).thenReturn(job("RUNNING", "run_1", 0));
        when(artifactService.store(anyString(), any(), anyString(), anyString()))
                .thenReturn(new ArtifactView("art_1", SHA, 5, "text/plain", "own_1/" + SHA, "VERIFIED"));
        when(binder.bind(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(PublicationException.staleRun());

        assertThatThrownBy(() -> publisher.publish(request("job_1", "run_1", 0, SHA)))
                .isInstanceOf(PublicationException.class);
        verify(orphanRecorder).record("own_1/" + SHA, SHA, 5, "text/plain");
    }
}
