package com.larder.pipeline.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Outbox 投递器：领取（短事务）→ 事务外投递 → 按结果标记。
 *
 * <p>六项说明（复杂机制）：
 * <ol>
 *   <li>它做什么：把 outbox 里 PENDING 的事件可靠地送到 MQ。</li>
 *   <li>为什么需要：业务事务无法直接发 MQ（双写不一致），先落 outbox 再由它投递。</li>
 *   <li>正常流程：claim 一批 → 逐条 publish → ACKED 标 SENT，其余退避或 DEAD。</li>
 *   <li>失败/竞争：publish 抛异常按 NACKED；租约过期由 reclaimExpired 收回；
 *       迟到 confirm 因 relay_token 不匹配而更新 0 行。</li>
 *   <li>为什么短事务：行锁只覆盖"领取"，网络 I/O 在事务外，避免拖垮连接池。</li>
 *   <li>限制：至少一次投递，消费端必须幂等（inbox 在 V03-T02）。</li>
 * </ol>
 */
@Service
public class OutboxRelay {

    private final OutboxStore store;
    private final OutboxPublisher publisher;
    private final String relayId;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maxAttempts;

    public OutboxRelay(OutboxStore store,
                       OutboxPublisher publisher,
                       @Value("${larder.outbox.relay-id:relay-local}") String relayId,
                       @Value("${larder.outbox.batch-size:20}") int batchSize,
                       @Value("${larder.outbox.lease-seconds:30}") int leaseSeconds,
                       @Value("${larder.outbox.max-attempts:5}") int maxAttempts) {
        this.store = store;
        this.publisher = publisher;
        this.relayId = relayId;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
    }

    /** 跑一轮：先收回过期租约，再领取并投递。返回本轮结果计数，便于调度器记录。 */
    public Tick runOnce() {
        int reclaimed = store.reclaimExpired(maxAttempts);
        var claimed = store.claim(relayId, batchSize, leaseSeconds);
        int sent = 0;
        int retried = 0;
        for (OutboxRow row : claimed) {
            PublishOutcome outcome = safePublish(row);
            if (outcome == PublishOutcome.ACKED) {
                sent += store.markSent(row.getEventId(), row.getRelayToken());
            } else {
                // UNROUTABLE / NACKED / TIMEOUT 都不允许标 SENT
                retried += store.markFailed(row.getEventId(), row.getRelayToken(), maxAttempts);
            }
        }
        return new Tick(reclaimed, claimed.size(), sent, retried);
    }

    private PublishOutcome safePublish(OutboxRow row) {
        try {
            return publisher.publish(row);
        } catch (RuntimeException brokerFailure) {
            return PublishOutcome.NACKED;
        }
    }

    /** 一轮的计数。 */
    public record Tick(int reclaimed, int claimed, int sent, int retried) {
    }
}
