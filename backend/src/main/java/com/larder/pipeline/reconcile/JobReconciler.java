package com.larder.pipeline.reconcile;

import com.larder.ingest.Ids;
import com.larder.ingest.Json;
import com.larder.pipeline.lease.JobLeaseMapper;
import com.larder.pipeline.lease.JobLeaseRow;
import com.larder.pipeline.model.OutboxEvent;
import com.larder.pipeline.model.OutboxEventMapper;
import com.larder.pipeline.model.OutboxState;
import com.larder.pipeline.outbox.OutboxBackoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 租约对账：把"声称在跑但租约已过期"的 job 收回，并重新派发事件。
 *
 * <p>为什么需要：worker 进程崩溃后 job 会永远停在 RUNNING；没有对账，用户看到的是
 * 永久"处理中"。对账用 attempt 上限把反复失败收敛到 FAILED，而不是无限重试。
 *
 * <p>为什么重新写 outbox 事件而不是直接调用 Runner：派发必须走同一条可靠通道
 * （V03-T01 的 relay），否则又是一次双写。
 */
@Service
public class JobReconciler {

    private static final Logger log = LoggerFactory.getLogger(JobReconciler.class);

    private final JobLeaseMapper leaseMapper;
    private final OutboxEventMapper outboxMapper;
    private final int maxAttempts;

    public JobReconciler(JobLeaseMapper leaseMapper,
                         OutboxEventMapper outboxMapper,
                         @Value("${larder.reconcile.max-attempts:5}") int maxAttempts) {
        this.leaseMapper = leaseMapper;
        this.outboxMapper = outboxMapper;
        this.maxAttempts = maxAttempts;
    }

    /** 跑一轮对账。 */
    public Result runOnce() {
        List<JobLeaseRow> expired = leaseMapper.selectExpired(50);
        int requeued = 0;
        int failed = 0;
        int skipped = 0;
        for (JobLeaseRow row : expired) {
            int attempt = (row.getAttempt() == null ? 1 : row.getAttempt()) + 1;
            boolean dead = OutboxBackoff.exhausted(attempt, maxAttempts);
            LocalDateTime nextAttemptAt = LocalDateTime.now(ZoneOffset.UTC)
                    .plusSeconds(dead ? 0 : OutboxBackoff.delaySeconds(attempt));
            int updated = leaseMapper.reclaim(row.getJobId(),
                    row.getLeaseToken() == null ? 0 : row.getLeaseToken(),
                    dead ? "FAILED" : "QUEUED",
                    attempt,
                    nextAttemptAt,
                    dead ? "LEASE_EXPIRED" : null);
            if (updated == 0) {
                skipped++;
                continue;
            }
            if (dead) {
                failed++;
                log.warn("job 租约反复过期，标记 FAILED: jobId={} attempt={}", row.getJobId(), attempt);
            } else {
                requeued++;
                emitRequeue(row);
            }
        }
        return new Result(expired.size(), requeued, failed, skipped);
    }

    private void emitRequeue(JobLeaseRow row) {
        String payload = Json.canonical("jobId", row.getJobId(), "kind", "INGEST");
        OutboxEvent event = new OutboxEvent();
        event.setEventId(Ids.next("out"));
        event.setOwnerId(row.getOwnerId());
        event.setJobId(row.getJobId());
        event.setEventType("job.queued.ingest");
        event.setWakeGeneration(1L);
        event.setPayloadJson(payload);
        event.setPayloadHash(Json.sha256(payload));
        event.setState(OutboxState.PENDING.name());
        event.setAvailableAt(LocalDateTime.now(ZoneOffset.UTC));
        event.setAttempt(0);
        event.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        outboxMapper.insert(event);
    }

    /** 一轮对账计数。 */
    public record Result(int scanned, int requeued, int failed, int skipped) {
    }
}
