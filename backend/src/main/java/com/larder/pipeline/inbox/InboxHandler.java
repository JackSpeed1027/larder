package com.larder.pipeline.inbox;

import com.larder.pipeline.lease.JobEventLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 消费去重与持久接管（一个事务内完成）。
 *
 * <p>六项说明：
 * <ol>
 *   <li>做什么：收到事件 → 写 inbox（唯一）→ 尝试接管 job（QUEUED→RUNNING + 租约）→ 提交。</li>
 *   <li>为什么：至少一次投递会重复；去重靠 (consumer,event_id) 主键，接管靠条件更新。</li>
 *   <li>正常流程：首次投递 → ACCEPTED；重复投递 → 已 RUNNING/终态 → ALREADY_*，调用方 ack。</li>
 *   <li>失败/竞争：唯一键冲突回读；job 被别人先接管则 0 行；payload 不一致判 CONFLICT 进死信。</li>
 *   <li>关键边界：inbox 行与 job 接管**同事务**。若拆开，崩溃后可能出现
 *       "inbox 有记录但 job 从未被接管"，重复消息会被去重掩盖，job 永远没人执行。</li>
 *   <li>限制：inbox 不代表任务完成（契约原话）；执行可靠性由 job 租约与 V03-T03 的对账负责。</li>
 * </ol>
 */
@Service
public class InboxHandler {

    private final InboxMapper mapper;
    private final JobEventLog eventLog;
    private final String consumer;
    private final String workerId;
    private final int leaseSeconds;

    public InboxHandler(InboxMapper mapper,
                        JobEventLog eventLog,
                        @Value("${larder.inbox.consumer:ingest-consumer}") String consumer,
                        @Value("${larder.inbox.worker-id:worker-local}") String workerId,
                        @Value("${larder.inbox.lease-seconds:120}") int leaseSeconds) {
        this.mapper = mapper;
        this.eventLog = eventLog;
        this.consumer = consumer;
        this.workerId = workerId;
        this.leaseSeconds = leaseSeconds;
    }

    @Transactional
    public InboxOutcome handle(String eventId, String jobId, String payloadHash) {
        try {
            mapper.insertInbox(consumer, eventId, jobId, payloadHash);
        } catch (DuplicateKeyException duplicate) {
            InboxRecord existing = mapper.findInbox(consumer, eventId);
            if (existing == null) {
                throw new IllegalStateException("inbox 唯一键冲突但读不到记录: " + eventId);
            }
            if (!existing.getJobId().equals(jobId) || !existing.getPayloadHash().equals(payloadHash)) {
                return InboxOutcome.CONFLICT;
            }
            // 同一条消息的再次投递：继续尝试接管，避免"inbox 有记录但 job 仍 QUEUED"被掩盖
        }

        LocalDateTime leaseUntil = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(leaseSeconds);
        int claimed = mapper.claimJob(jobId, workerId, leaseUntil);
        if (claimed == 1) {
            String ownerId = mapper.ownerOf(jobId);
            if (ownerId != null) {
                eventLog.append(ownerId, jobId, "RUNNING", "RUNNING", null, 0L, null);
            }
            return InboxOutcome.ACCEPTED;
        }

        String state = mapper.jobState(jobId);
        if (state == null) {
            return InboxOutcome.UNKNOWN_JOB;
        }
        return switch (state) {
            case "RUNNING" -> InboxOutcome.ALREADY_RUNNING;
            case "QUEUED", "RETRY_WAIT" ->
                    throw new IllegalStateException("job 处于 " + state + " 但接管失败，需要重试: " + jobId);
            default -> InboxOutcome.ALREADY_DONE;
        };
    }

    public String consumer() {
        return consumer;
    }
}
