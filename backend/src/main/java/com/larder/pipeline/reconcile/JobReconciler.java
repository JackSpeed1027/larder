package com.larder.pipeline.reconcile;

import com.larder.ingest.Ids;
import com.larder.ingest.Json;
import com.larder.pipeline.lease.JobEventLog;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


/**
 * 租约对账：把"声称在跑但租约已过期"的 job 收回，并重新派发事件。
 *
 * <p>为什么需要：worker 进程崩溃后 job 会永远停在 RUNNING；没有对账，用户看到的是
 * 永久"处理中"。对账用 attempt 上限把反复失败收敛到 FAILED，而不是无限重试。
 *
 * <p>为什么重新写 outbox 事件而不是直接调用 Runner：派发必须走同一条可靠通道
 * （V03-T01 的 relay），否则又是一次双写。
 *
 * <p>S04：整轮对账是一个短数据库事务——回收（改 job 状态）与补发 Outbox 必须原子提交。
 * 如果先提交 QUEUED 再单独插事件，中间崩溃会留下“QUEUED 但永远没有通知”的僵尸任务。
 * 本方法只做数据库操作，不调用 Runner/对象存储，因此事务足够短。
 */
@Service
public class JobReconciler {

    private static final Logger log = LoggerFactory.getLogger(JobReconciler.class);

    private final JobLeaseMapper leaseMapper;
    private final JobEventLog eventLog;
    private final OutboxEventMapper outboxMapper;
    private final int maxAttempts;

    public JobReconciler(JobLeaseMapper leaseMapper,
                         JobEventLog eventLog,
                         OutboxEventMapper outboxMapper,
                         @Value("${larder.reconcile.max-attempts:5}") int maxAttempts) {
        this.leaseMapper = leaseMapper;
        this.eventLog = eventLog;
        this.outboxMapper = outboxMapper;
        this.maxAttempts = maxAttempts;
    }

    /** 跑一轮对账；回收与 Outbox 补发在同一事务提交（S04）。 */
    @Transactional
    public Result runOnce() {
        List<JobLeaseRow> expired = leaseMapper.selectExpired(50);
        int requeued = 0;
        int failed = 0;
        int skipped = 0;
        for (JobLeaseRow row : expired) {
            int attempt = (row.getAttempt() == null ? 1 : row.getAttempt()) + 1;
            boolean dead = OutboxBackoff.exhausted(attempt, maxAttempts);
            LocalDateTime nextAttemptAt = LocalDateTime.now(ZoneOffset.UTC)
                    .plusSeconds(dead ? 0 : OutboxBackoff.delaySecondsWithJitter(attempt, ThreadLocalRandom.current()));
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
            eventLog.append(row.getOwnerId(), row.getJobId(), dead ? "FAILED" : "QUEUED",
                    dead ? "FAILED" : "QUEUED", row.getRunId(),
                    row.getProcessingEpoch() == null ? 0L : row.getProcessingEpoch(),
                    dead ? "LEASE_EXPIRED" : null);
            if (dead) {
                failed++;
                log.warn("job 租约反复过期，标记 FAILED: jobId={} attempt={}", row.getJobId(), attempt);
            } else {
                requeued++;
                emitRequeue(row);
            }
        }
        int compensated = sweepQueuedDue();
        return new Result(expired.size(), requeued, failed, skipped, compensated);
    }

    /**
     * QUEUED 补偿扫描（S04）：通知可能长期丢失，QUEUED 不能只依赖一次 outbox 投递。
     * 只在没有 PENDING 事件时补发，并写 next_reconcile_at 防抖 30 秒。
     */
    private int sweepQueuedDue() {
        int compensated = 0;
        for (JobLeaseRow row : leaseMapper.selectQueuedDue(50)) {
            if (leaseMapper.countPendingOutbox(row.getJobId()) > 0) {
                leaseMapper.markReconcile(row.getJobId(), LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30));
                continue;
            }
            emitRequeue(row);
            leaseMapper.markReconcile(row.getJobId(), LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30));
            compensated++;
            log.info("补发 QUEUED 任务事件：jobId={}", row.getJobId());
        }
        return compensated;
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

    /** 一轮对账计数（compensated = QUEUED 补偿补发数）。 */
    public record Result(int scanned, int requeued, int failed, int skipped, int compensated) {
    }
}
