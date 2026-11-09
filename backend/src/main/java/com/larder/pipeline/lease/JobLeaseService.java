package com.larder.pipeline.lease;

import com.larder.ingest.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 租约与 fencing 提交。
 *
 * <p>六项说明：
 * <ol>
 *   <li>做什么：续租、分配稳定 runId、按 token+epoch+runId 提交成功/失败、读进度。</li>
 *   <li>为什么：worker 可能崩溃/卡住，任务必须能被别人接管；旧 worker 迟到不能覆盖新结果。</li>
 *   <li>正常流程：renew 心跳 → assignRunId（一次）→ commitSuccess（条件全中才落终态）。</li>
 *   <li>竞争：token 被接管后 +1，旧 token 的条件更新影响 0 行 → 抛 StaleLeaseException。</li>
 *   <li>为什么用数据库条件更新而不是内存锁：最终仲裁必须是单点且持久（AGENTS §4）。</li>
 *   <li>限制：本任务只做最小持久 run 适配；真正的 Runner 执行在 V05。</li>
 * </ol>
 */
@Service
public class JobLeaseService {

    private final JobLeaseMapper mapper;
    private final int renewSeconds;

    public JobLeaseService(JobLeaseMapper mapper,
                           @Value("${larder.lease.renew-seconds:30}") int renewSeconds) {
        this.mapper = mapper;
        this.renewSeconds = renewSeconds;
    }

    public boolean renew(String jobId, String owner, long token) {
        return mapper.renew(jobId, owner, token,
                LocalDateTime.now(ZoneOffset.UTC).plusSeconds(renewSeconds)) == 1;
    }

    /**
     * 分配稳定 runId：同一次执行复用同一个值；若已存在则直接返回。
     *
     * <p>runId 稳定是"ack 后 HTTP 前崩溃可续"的前提：对账重派时用同一个 runId，
     * Runner 侧按 runId 幂等，不会产生两个 run。
     */
    public String startRun(String jobId, String owner, long token) {
        JobLeaseRow row = mapper.findJob(jobId);
        if (row == null) {
            throw new StaleLeaseException("job 不存在: " + jobId);
        }
        if (row.getRunId() != null) {
            return row.getRunId();
        }
        String runId = Ids.next("run");
        if (mapper.assignRunId(jobId, owner, token, runId) == 1) {
            return runId;
        }
        JobLeaseRow after = mapper.findJob(jobId);
        if (after != null && after.getRunId() != null) {
            return after.getRunId();
        }
        throw new StaleLeaseException("无法分配 runId，租约已失效: " + jobId);
    }

    /** 成功提交；false 表示 token/epoch/runId 任一不匹配（旧 worker 迟到被拒绝）。 */
    public boolean commitSuccess(String jobId, String owner, long token, long epoch, String runId) {
        return mapper.commitSuccess(jobId, owner, token, epoch, runId) == 1;
    }

    /** 失败提交；state 只能是 FAILED（或 NEEDS_DECISION 由更上层决定）。 */
    public boolean commitFailure(String jobId, String owner, long token, long epoch, String runId,
                                 String errorCode, String errorMessage) {
        String state = "NEEDS_DECISION".equals(errorCode) ? "NEEDS_DECISION" : "FAILED";
        return mapper.commitFailure(jobId, owner, token, epoch, runId, state,
                errorCode, truncate(errorMessage)) == 1;
    }

    public JobProgress progress(String jobId) {
        JobLeaseRow row = mapper.findJob(jobId);
        if (row == null) {
            return null;
        }
        return new JobProgress(row.getJobId(), row.getOwnerId(), row.getState(),
                row.getAttempt() == null ? 0 : row.getAttempt(), row.getRunId(),
                row.getProcessingEpoch() == null ? 0 : row.getProcessingEpoch(),
                row.getErrorCode(),
                row.getUpdatedAt() == null ? "" : row.getUpdatedAt().toString(),
                row.getLastEventSeq() == null ? 0 : row.getLastEventSeq());
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 512 ? message : message.substring(0, 512);
    }
}
