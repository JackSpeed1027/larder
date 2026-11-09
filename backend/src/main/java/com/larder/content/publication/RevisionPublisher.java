package com.larder.content.publication;

import com.larder.content.storage.ArtifactService;
import com.larder.content.storage.ArtifactView;
import com.larder.content.storage.ArtifactKeys;
import com.larder.ingest.Ids;
import com.larder.ingest.Json;
import com.larder.runner.ingest.RunnerResultRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 发布：把 Runner 的抓取结果落成不可变 revision，并移动 item 指针。
 *
 * <p>六项说明：
 * <ol>
 *   <li>做什么：校验 run/epoch → 存正文 artifact → 写 content_revision → 绑定 artifact → 移动指针 → job SUCCEEDED。</li>
 *   <li>为什么：正文必须不可变、可追溯；job 的成功只能由"写入成功"推导，不能由 HTTP 请求推导。</li>
 *   <li>正常流程：一次事务提交，任一步失败全部回滚（不会出现指针指向半成品）。</li>
 *   <li>竞争：job 行 FOR UPDATE；最终 commitJob 带 run_id+epoch 条件，旧 run 结果被拒绝。</li>
 *   <li>幂等：同一个 run 重复提交（Runner 重试）返回已有 revision，不重复写。</li>
 *   <li>限制：只处理文本正文；PDF/音视频在 V06。</li>
 * </ol>
 */
@Service
public class RevisionPublisher {

    private final PublicationMapper mapper;
    private final ArtifactService artifactService;

    public RevisionPublisher(PublicationMapper mapper, ArtifactService artifactService) {
        this.mapper = mapper;
        this.artifactService = artifactService;
    }

    @Transactional
    public PublicationResult publish(RunnerResultRequest request) {
        JobPublishRow job = mapper.lockJob(request.jobId());
        if (job == null) {
            throw PublicationException.jobNotFound();
        }
        if (!request.runId().equals(job.getRunId())) {
            throw PublicationException.staleRun();
        }
        if (!request.processingEpoch().equals(job.getProcessingEpoch())) {
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
        if (!"RUNNING".equals(job.getState())) {
            throw PublicationException.staleRun();
        }

        ArtifactView artifact = artifactService.store(job.getOwnerId(), text, actualSha, "text/plain");
        String manifestJson = request.manifestJson() == null || request.manifestJson().isBlank()
                ? Json.canonical("chunks", "0") : request.manifestJson();
        String revisionId = Ids.next("rev");
        long revisionNo = mapper.maxRevisionNo(job.getOwnerId(), job.getItemId()) + 1;
        mapper.insertRevision(revisionId, job.getOwnerId(), job.getItemId(), revisionNo, actualSha,
                request.extractorVersion(), request.chunkerVersion(),
                Json.sha256(manifestJson), manifestJson, LocalDateTime.now(ZoneOffset.UTC));
        mapper.bindBodyArtifact(job.getOwnerId(), revisionId, artifact.id());
        if (mapper.pointCurrentRevision(job.getOwnerId(), job.getItemId(), revisionId) != 1) {
            throw PublicationException.corrupt("item 指针更新失败");
        }
        if (mapper.commitJob(job.getJobId(), request.runId(), request.processingEpoch()) != 1) {
            throw PublicationException.staleRun();
        }
        return new PublicationResult(revisionId, "CREATED");
    }
}
