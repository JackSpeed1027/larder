package com.larder.pipeline.lease;

import com.larder.ingest.Json;
import com.larder.ingest.OwnerService;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.Semaphore;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * 任务进度 SSE：先发 snapshot，再推状态变化；断流重连仍以 snapshot 为准。
 *
 * <p>为什么"先 snapshot"：SSE 可能重复、丢失、乱序；客户端重新连上时最可靠的动作
 * 是拿一次权威快照（job 当前状态 + 序号），而不是假设自己还记得旧状态。
 *
 * <p>为什么轮询数据库而不是内存广播：多实例部署时别的实例改了 job，本实例必须能看见；
 * 内存广播只能当优化，不能当事实源。
 *
 * <p>S05 资源边界：固定大小的执行池 + 信号量限制并发连接数。超限返回 503 + Retry-After，
 * 而不是无界创建线程（旧实现用 cachedThreadPool，每个连接一个线程，会被慢客户端拖垮）。
 * 连接结束/超时/出错都必须归还许可，否则一次泄漏就会永久少一个连接额度。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobEventsController {

    private static final long SSE_TIMEOUT_MS = 120_000L;
    private static final long POLL_MS = 500L;

    private final JobLeaseService leaseService;
    private final OwnerService ownerService;
    private final JobEventLog eventLog;
    private final ExecutorService pool;
    private final Semaphore connections;

    public JobEventsController(JobLeaseService leaseService, OwnerService ownerService, JobEventLog eventLog,
                               @Value("${larder.sse.pool-size:8}") int poolSize,
                               @Value("${larder.sse.max-connections:32}") int maxConnections) {
        this.leaseService = leaseService;
        this.ownerService = ownerService;
        this.eventLog = eventLog;
        this.pool = Executors.newFixedThreadPool(Math.max(1, poolSize));
        this.connections = new Semaphore(Math.max(1, maxConnections));
    }

    @GetMapping(path = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String jobId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             Authentication authentication) {
        String ownerId = ownerService.ownerIdFor(authentication.getName());
        JobProgress progress = leaseService.progress(jobId);
        if (progress == null || ownerId == null || !ownerId.equals(progress.ownerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job 不存在");
        }
        if (!connections.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SSE 连接数已满，请稍后重试");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onCompletion(connections::release);
        emitter.onTimeout(connections::release);
        emitter.onError(error -> connections.release());
        try {
            pool.submit(() -> stream(emitter, jobId, ownerId, progress, lastEventId));
        } catch (RuntimeException rejected) {
            connections.release();
            emitter.completeWithError(rejected);
        }
        return emitter;
    }

    /** 应用关闭时停止所有 SSE 工作线程，避免测试/停机挂住。 */
    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    private void stream(SseEmitter emitter, String jobId, String ownerId, JobProgress initial, String lastEventId) {
        try {
            long seq = initial.seq();
            send(emitter, "snapshot", seq, snapshotJson(initial, lastEventId));
            replayOrReset(emitter, jobId, ownerId, initial, lastEventId);
            JobProgress current = initial;
            long lastSent = System.currentTimeMillis();
            while (!current.terminal()) {
                if (!awaitPoll()) {
                    break;
                }
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

    /**
     * S05：带 Last-Event-ID 时从持久日志回放；日志没有但 job 序号已前进，
     * 说明历史已不可用——发 reset + 权威快照，而不是假装续传。
     */
    private void replayOrReset(SseEmitter emitter, String jobId, String ownerId, JobProgress initial,
                               String lastEventId) throws Exception {
        if (lastEventId == null || lastEventId.isBlank()) {
            return;
        }
        long cursor;
        try {
            cursor = Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException badCursor) {
            return;
        }
        var events = eventLog.after(ownerId, jobId, cursor);
        if (events.isEmpty() && initial.seq() > cursor) {
            emitter.send(SseEmitter.event().id(Long.toString(initial.seq())).name("reset")
                    .data(snapshotJson(initial, lastEventId), MediaType.APPLICATION_JSON));
            return;
        }
        for (var event : events) {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.get("seq")))
                    .name("state")
                    .data(String.valueOf(event.get("payloadJson")), MediaType.APPLICATION_JSON));
        }
    }

    /** 可中断的轮询等待；线程被关闭时退出而不是继续占用连接。 */
    private boolean awaitPoll() {
        try {
            return !Thread.currentThread().isInterrupted() && !Thread.interrupted()
                    && sleepQuietly();
        } catch (RuntimeException stop) {
            return false;
        }
    }

    private boolean sleepQuietly() {
        try {
            TimeUnit.MILLISECONDS.sleep(POLL_MS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
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
