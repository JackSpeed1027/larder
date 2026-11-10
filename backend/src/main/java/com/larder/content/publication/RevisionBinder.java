package com.larder.content.publication;

import com.larder.ingest.Ids;
import com.larder.ingest.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S03：发布绑定事务（短事务，不含任何对象存储 I/O）。
 *
 * <p>不变量：进入本事务前 artifact 已经上传并校验；本事务只做数据库绑定。
 * 发布条件（任一不满足即回滚）：
 * owner + jobId + runId + job 仍 RUNNING + lease_token 未变 + DB 时钟下租约未过期
 * + processing_epoch 一致 + item 未删除 + 目标 revision/输入快照一致。
 */
@Service
public class RevisionBinder {

    private final PublicationMapper mapper;

    public RevisionBinder(PublicationMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public PublicationResult bind(String jobId, String runId, long epoch, String artifactId,
                                  String contentSha256, String extractorVersion, String chunkerVersion,
                                  String manifestJson) {
        JobPublishRow job = mapper.lockJob(jobId);
        if (job == null) {
            throw PublicationException.jobNotFound();
        }
        if (job.getCancelRequestedAt() != null) {
            throw PublicationException.cancelledConflict();
        }
        if (!runId.equals(job.getRunId())) {
            throw PublicationException.staleRun();
        }
        if (!Long.valueOf(epoch).equals(job.getProcessingEpoch())) {
            throw PublicationException.epochSuperseded();
        }
        if (!"RUNNING".equals(job.getState())) {
            throw PublicationException.staleRun();
        }

        String revisionId = Ids.next("rev");
        long revisionNo = mapper.maxRevisionNo(job.getOwnerId(), job.getItemId()) + 1;
        mapper.insertRevision(revisionId, job.getOwnerId(), job.getItemId(), revisionNo, contentSha256,
                extractorVersion, chunkerVersion, Json.sha256(manifestJson), manifestJson,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        mapper.bindBodyArtifact(job.getOwnerId(), revisionId, artifactId);
        if (mapper.pointCurrentRevision(job.getOwnerId(), job.getItemId(), revisionId) != 1) {
            throw PublicationException.corrupt("item 指针更新失败（条目可能已删除或归属变化）");
        }
        if (mapper.commitJob(job.getJobId(), runId, epoch) != 1) {
            JobPublishRow after = mapper.lockJob(jobId);
            if (after != null && after.getCancelRequestedAt() != null) {
                throw PublicationException.cancelledConflict();
            }
            throw PublicationException.staleRun();
        }
        return new PublicationResult(revisionId, "CREATED");
    }
}
