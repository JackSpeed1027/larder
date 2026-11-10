package com.larder.pipeline.lease;

import com.larder.ingest.Json;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * S05：写任务事件日志（与业务更新同事务）。
 *
 * <p>不变量：seq 必须等于 job.last_event_seq（由同事务的 job 更新产生），
 * 这样 SSE 的 Last-Event-ID 与数据库状态是同一套序号，而不是进程内计数。
 */
@Service
public class JobEventLog {

    private final JobEventMapper mapper;

    public JobEventLog(JobEventMapper mapper) {
        this.mapper = mapper;
    }

    /** 追加一条事件；seq 缺失（job 不存在）时返回 false，不编造序号。 */
    public boolean append(String ownerId, String jobId, String eventType, String state,
                          String runId, long epoch, String errorCode) {
        Long seq = mapper.lastEventSeq(ownerId, jobId);
        if (seq == null || seq <= 0) {
            return false;
        }
        String payload = Json.canonical(
                "jobId", jobId,
                "state", state,
                "runId", runId == null ? "" : runId,
                "epoch", Long.toString(epoch),
                "errorCode", errorCode == null ? "" : errorCode);
        mapper.insert(ownerId, jobId, seq, eventType, payload, LocalDateTime.now(ZoneOffset.UTC));
        return true;
    }

    public java.util.List<Map<String, Object>> after(String ownerId, String jobId, long afterSeq) {
        return mapper.listAfter(ownerId, jobId, afterSeq, 50);
    }
}
