package com.larder.pipeline.lease;

import com.larder.ingest.Json;
import com.larder.ingest.OwnerService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 任务进度 SSE：先发 snapshot，再推状态变化；断流重连仍以 snapshot 为准。
 *
 * <p>为什么"先 snapshot"：SSE 可能重复、丢失、乱序；客户端重新连上时最可靠的动作
 * 是拿一次权威快照（job 当前状态 + 序号），而不是假设自己还记得旧状态。
 *
 * <p>为什么轮询数据库而不是内存广播：多实例部署时别的实例改了 job，本实例必须能看见；
 * 内存广播只能当优化，不能当事实源。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobEventsController {

    private static final long SSE_TIMEOUT_MS = 120_000L;
    private static final long POLL_MS = 500L;

    private final JobLeaseService leaseService;
    private final OwnerService ownerService;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public JobEventsController(JobLeaseService leaseService, OwnerService ownerService) {
        this.leaseService = leaseService;
        this.ownerService = ownerService;
    }

    @GetMapping(path = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String jobId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             Authentication authentication) {
        String ownerId = ownerService.ownerIdFor(authentication.getName());
        JobProgress progress = leaseService.progress(jobId);
        if (progress == null || !ownerId.equals(progress.ownerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job 不存在");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        pool.submit(() -> stream(emitter, jobId, progress, lastEventId));
        return emitter;
    }

    private void stream(SseEmitter emitter, String jobId, JobProgress initial, String lastEventId) {
        try {
            long seq = initial.seq();
            send(emitter, "snapshot", seq, snapshotJson(initial, lastEventId));
            JobProgress current = initial;
            long lastSent = System.currentTimeMillis();
            while (!current.terminal()) {
                Thread.sleep(POLL_MS);
                JobProgress next = leaseService.progress(jobId);
                if (next == null) {
                    break;
                }
                if (next.seq() > seq || !next.state().equals(current.state())) {
                    seq = Math.max(seq + 1, next.seq());
                    send(emitter, "state", seq, snapshotJson(next, lastEventId));
                    current = next;
                    lastSent = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastSent > 10_000L) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastSent = System.currentTimeMillis();
                }
            }
            emitter.complete();
        } catch (Exception stop) {
            emitter.completeWithError(stop);
        }
    }

    private void send(SseEmitter emitter, String name, long id, String data) throws Exception {
        emitter.send(SseEmitter.event().id(Long.toString(id)).name(name).data(data, MediaType.APPLICATION_JSON));
    }

    private String snapshotJson(JobProgress p, String lastEventId) {
        return Json.canonical(
                "jobId", p.jobId(),
                "state", p.state(),
                "attempt", Integer.toString(p.attempt()),
                "runId", p.runId() == null ? "" : p.runId(),
                "epoch", Long.toString(p.epoch()),
                "errorCode", p.errorCode() == null ? "" : p.errorCode(),
                "updatedAt", p.updatedAt(),
                "seq", Long.toString(p.seq()),
                "resumedFrom", lastEventId == null ? "" : lastEventId);
    }
}
