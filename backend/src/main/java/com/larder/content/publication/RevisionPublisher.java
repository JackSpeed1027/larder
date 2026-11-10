package com.larder.content.publication;

import com.larder.content.storage.ArtifactService;
import com.larder.content.storage.ArtifactView;
import com.larder.content.storage.ArtifactKeys;
import com.larder.content.storage.OrphanRecorder;
import com.larder.runner.ingest.RunnerResultRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 发布编排（S03）：先校验/去重，再**在事务外**上传 artifact，最后调用短事务绑定。
 *
 * <p>顺序不可颠倒：
 * <ol>
 *   <li>预检（无锁读）：job 存在、run/epoch 匹配、未取消；已成功的 run 直接重放；</li>
 *   <li>上传正文到对象存储（外部 I/O，绝不能持有数据库事务/行锁）；</li>
 *   <li>短事务绑定 revision + 指针 + job（{@link RevisionBinder}），事务内复核 fencing；</li>
 *   <li>绑定失败：对象与 artifact 行已存在，登记为孤立对象，保留供对账，不删。</li>
 * </ol>
 */
@Service
public class RevisionPublisher {

    private final PublicationMapper mapper;
    private final ArtifactService artifactService;
    private final RevisionBinder binder;
    private final OrphanRecorder orphanRecorder;

    public RevisionPublisher(PublicationMapper mapper, ArtifactService artifactService,
                             RevisionBinder binder, OrphanRecorder orphanRecorder) {
        this.mapper = mapper;
        this.artifactService = artifactService;
        this.binder = binder;
        this.orphanRecorder = orphanRecorder;
    }

    public PublicationResult publish(RunnerResultRequest request) {
        JobPublishRow job = mapper.findJob(request.jobId());
        if (job == null) {
            throw PublicationException.jobNotFound();
        }
        if (job.getCancelRequestedAt() != null) {
            throw PublicationException.cancelledConflict();
        }
        if (!request.runId().equals(job.getRunId())) {
            throw PublicationException.staleRun();
        }
        if (!java.util.Objects.equals(request.processingEpoch(), job.getProcessingEpoch())) {
            throw PublicationException.epochSuperseded();
        }

        byte[] text = request.text().getBytes(StandardCharsets.UTF_8);
        String actualSha = ArtifactKeys.sha256(text);
        if (!actualSha.equalsIgnoreCase(request.contentSha256())) {
            throw PublicationException.corrupt("正文 sha256 与声明不一致");
        }

        if ("SUCCEEDED".equals(job.getState())) {
            String existing = mapper.findRevisionBySource(job.getOwnerId(), job.getItemId(), actualSha);
            if (existing == null) {
                throw PublicationException.corrupt("job 已完成但找不到对应 revision");
            }
            return new PublicationResult(existing, "REPLAYED");
        }

        // 外部 I/O 在事务外：短暂失败不应拖住行锁
        ArtifactView artifact = artifactService.store(job.getOwnerId(), text, actualSha, "text/plain");

        String manifestJson = request.manifestJson() == null || request.manifestJson().isBlank()
                ? "{\"chunks\":0}" : request.manifestJson();
        try {
            return binder.bind(request.jobId(), request.runId(), request.processingEpoch(), artifact.id(),
                    actualSha, request.extractorVersion(), request.chunkerVersion(), manifestJson);
        } catch (RuntimeException bindFailure) {
            // 对象已写入但未绑定：登记孤立对象供对账；不删除，避免并发复用被破坏
            orphanRecorder.record(artifact.objectKey(), actualSha, text.length, "text/plain");
            throw bindFailure;
        }
    }
}
