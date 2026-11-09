package com.larder.content.publication;

import com.larder.content.storage.ArtifactService;
import com.larder.content.storage.ArtifactView;
import com.larder.content.storage.ArtifactKeys;
import com.larder.runner.ingest.RunnerResultRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 发布事务：旧代次/旧 run 被拒绝，重放幂等，写入顺序正确。 */
class RevisionPublisherTest {

    private PublicationMapper mapper;
    private ArtifactService artifactService;
    private RevisionPublisher publisher;
    private String sha;

    private static final String TEXT = "hello revision";

    @BeforeEach
    void setUp() {
        mapper = mock(PublicationMapper.class);
        artifactService = mock(ArtifactService.class);
        publisher = new RevisionPublisher(mapper, artifactService);
        sha = ArtifactKeys.sha256(TEXT.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("正常发布：写 revision、绑定 artifact、移动指针、提交 job")
    void publishesRevision() {
        when(mapper.lockJob("job_1")).thenReturn(job("RUNNING", 0, "run_1"));
        when(mapper.maxRevisionNo("own_1", "itm_1")).thenReturn(0L);
        when(artifactService.store(eq("own_1"), any(), eq(sha), eq("text/plain")))
                .thenReturn(new ArtifactView("art_1", sha, TEXT.length(), "text/plain", "artifacts/own_1/aa/" + sha, "VERIFIED"));
        when(mapper.pointCurrentRevision(eq("own_1"), eq("itm_1"), anyString())).thenReturn(1);
        when(mapper.insertRevision(anyString(), eq("own_1"), eq("itm_1"), eq(1L), eq(sha),
                anyString(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.commitJob(eq("job_1"), eq("run_1"), eq(0L))).thenReturn(1);

        PublicationResult result = publisher.publish(request("run_1", 0L, sha));

        assertThat(result.dedupe()).isEqualTo("CREATED");
        assertThat(result.revisionId()).startsWith("rev_");
        verify(mapper).bindBodyArtifact(eq("own_1"), eq(result.revisionId()), eq("art_1"));
        ArgumentCaptor<Long> no = ArgumentCaptor.forClass(Long.class);
        verify(mapper).insertRevision(anyString(), eq("own_1"), eq("itm_1"), no.capture(), eq(sha),
                anyString(), anyString(), anyString(), anyString(), any());
        assertThat(no.getValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("旧 processing_epoch：409 EPOCH_SUPERSEDED，且不写任何东西")
    void rejectsSupersededEpoch() {
        when(mapper.lockJob("job_1")).thenReturn(job("RUNNING", 1, "run_1"));

        assertThatThrownBy(() -> publisher.publish(request("run_1", 0L, sha)))
                .isInstanceOf(PublicationException.class)
                .satisfies(ex -> assertThat(((PublicationException) ex).code()).isEqualTo("EPOCH_SUPERSEDED"));
        verify(artifactService, never()).store(anyString(), any(), anyString(), anyString());
        verify(mapper, never()).insertRevision(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("run_id 不匹配：409 STALE_RUN")
    void rejectsStaleRun() {
        when(mapper.lockJob("job_1")).thenReturn(job("RUNNING", 0, "run_other"));

        assertThatThrownBy(() -> publisher.publish(request("run_1", 0L, sha)))
                .isInstanceOf(PublicationException.class)
                .satisfies(ex -> assertThat(((PublicationException) ex).code()).isEqualTo("STALE_RUN"));
    }

    @Test
    @DisplayName("job 已成功且 revision 已存在：REPLAYED，不重复写")
    void replaysExistingRevision() {
        when(mapper.lockJob("job_1")).thenReturn(job("SUCCEEDED", 0, "run_1"));
        when(mapper.findRevisionBySource("own_1", "itm_1", sha)).thenReturn("rev_existing");

        PublicationResult result = publisher.publish(request("run_1", 0L, sha));

        assertThat(result.dedupe()).isEqualTo("REPLAYED");
        assertThat(result.revisionId()).isEqualTo("rev_existing");
        verify(artifactService, never()).store(anyString(), any(), anyString(), anyString());
        verify(mapper, never()).insertRevision(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("正文 hash 不符：CORRUPT_ARTIFACT")
    void rejectsHashMismatch() {
        when(mapper.lockJob("job_1")).thenReturn(job("RUNNING", 0, "run_1"));

        assertThatThrownBy(() -> publisher.publish(request("run_1", 0L, "b".repeat(64))))
                .isInstanceOf(PublicationException.class)
                .satisfies(ex -> assertThat(((PublicationException) ex).code()).isEqualTo("CORRUPT_ARTIFACT"));
    }

    @Test
    @DisplayName("job 不存在：404")
    void rejectsMissingJob() {
        when(mapper.lockJob("job_1")).thenReturn(null);
        assertThatThrownBy(() -> publisher.publish(request("run_1", 0L, sha)))
                .isInstanceOf(PublicationException.class)
                .satisfies(ex -> assertThat(((PublicationException) ex).httpStatus()).isEqualTo(404));
    }

    private RunnerResultRequest request(String runId, long epoch, String contentSha) {
        return new RunnerResultRequest("job_1", runId, epoch, TEXT, contentSha,
                "extract-v1", "chunk-v1", "{\"chunks\":1}");
    }

    private JobPublishRow job(String state, long epoch, String runId) {
        JobPublishRow row = new JobPublishRow();
        row.setJobId("job_1");
        row.setOwnerId("own_1");
        row.setItemId("itm_1");
        row.setState(state);
        row.setProcessingEpoch(epoch);
        row.setRunId(runId);
        return row;
    }
}
