package com.larder.pipeline.control;

import com.larder.pipeline.lease.JobEventLog;
import com.larder.ingest.Ids;
import com.larder.ingest.Json;
import com.larder.ingest.OwnerService;
import com.larder.pipeline.model.OutboxEvent;
import com.larder.pipeline.model.OutboxEventMapper;
import com.larder.pipeline.model.OutboxState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 任务中心：取消、重试、列表。
 *
 * <p>关键边界（A07）：取消与完成竞争时，**取消先行则产物不能发布**。
 * 这里只负责写 `cancel_requested_at`；发布事务的最终条件更新会再检查它，
 * 两边用同一行记录做仲裁，不靠时序假设。
 */
@Service
public class JobControlService {

    private final JobControlMapper mapper;
    private final JobEventLog eventLog;
    private final OutboxEventMapper outboxMapper;
    private final OwnerService ownerService;

    public JobControlService(JobControlMapper mapper, OutboxEventMapper outboxMapper,
                             OwnerService ownerService, JobEventLog eventLog) {
        this.mapper = mapper;
        this.outboxMapper = outboxMapper;
        this.ownerService = ownerService;
        this.eventLog = eventLog;
    }

    public static class ControlException extends RuntimeException {
        private final String code;
        private final int status;

        public ControlException(String code, String message, int status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }

    private String owner(String username) {
        return ownerService.ownerIdFor(username);
    }

    public Map<String, Object> list(String username, String state, String itemId, int limit, int offset) {
        String ownerId = owner(username);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<Map<String, Object>> jobs = mapper.listJobs(ownerId, blank(state), blank(itemId), safeLimit,
                Math.max(0, offset));
        return Map.of("jobs", jobs, "total", mapper.countJobs(ownerId, blank(state), blank(itemId)),
                "limit", safeLimit, "offset", Math.max(0, offset));
    }

    public Map<String, Object> detail(String username, String jobId) {
        Map<String, Object> job = mapper.findJob(jobId);
        if (job == null || !owner(username).equals(String.valueOf(job.get("ownerId")))) {
            throw new ControlException("NOT_FOUND", "job 不存在", 404);
        }
        return job;
    }

    @Transactional
    public Map<String, Object> cancel(String username, String jobId) {
        String ownerId = owner(username);
        Map<String, Object> job = mapper.findJob(jobId);
        if (job == null || !ownerId.equals(String.valueOf(job.get("ownerId")))) {
            throw new ControlException("NOT_FOUND", "job 不存在", 404);
        }
        String state = String.valueOf(job.get("state"));
        if (List.of("SUCCEEDED", "FAILED", "CANCELLED", "SUPERSEDED").contains(state)) {
            throw new ControlException("CANCEL_NOT_ALLOWED", "任务已结束，不能取消", 409);
        }
        int cancelled = mapper.requestCancel(jobId, ownerId);
        if (cancelled == 0) {
            throw new ControlException("CANCEL_NOT_ALLOWED", "状态已变化，请刷新", 409);
        }
        return mapper.findJob(jobId);
    }

    @Transactional
    public Map<String, Object> retry(String username, String jobId) {
        String ownerId = owner(username);
        Map<String, Object> job = mapper.findJob(jobId);
        if (job == null || !ownerId.equals(String.valueOf(job.get("ownerId")))) {
            throw new ControlException("NOT_FOUND", "job 不存在", 404);
        }
        String state = String.valueOf(job.get("state"));
        if (!List.of("FAILED", "CANCELLED").contains(state)) {
            throw new ControlException("RETRY_NOT_ALLOWED", "只有失败/已取消的任务可以重试", 409);
        }
        int requeued = mapper.requeue(jobId, ownerId);
        if (requeued == 0) {
            throw new ControlException("RETRY_NOT_ALLOWED", "状态已变化，请刷新", 409);
        }
        emitRequeue(ownerId, jobId);
        return mapper.findJob(jobId);
    }

    private void emitRequeue(String ownerId, String jobId) {
        String payload = Json.canonical("jobId", jobId, "kind", "INGEST");
        OutboxEvent event = new OutboxEvent();
        event.setEventId(Ids.next("out"));
        event.setOwnerId(ownerId);
        event.setJobId(jobId);
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

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
