package com.larder.pipeline.inbox;

import com.larder.pipeline.lease.JobLeaseService;
import com.larder.runner.client.RunnerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Inbox 接管成功后的 Runner 派发（S02.DISPATCH）。
 *
 * <p>必须在 inbox 事务**提交后**调用：外部 HTTP 不能放在持有数据库锁的事务里。
 * 派发失败时把 job 放回 QUEUED 并递增 attempt，让 MQ 重投（requeue）后重新接管并重派；
 * 不在这里直接 ack 成功——派发没发生就不能说“处理了”。
 */
@Service
public class InboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InboxDispatcher.class);

    private final InboxMapper mapper;
    private final JobLeaseService leaseService;
    private final RunnerClient runnerClient;
    private final String workerId;

    public InboxDispatcher(InboxMapper mapper,
                           JobLeaseService leaseService,
                           RunnerClient runnerClient,
                           @Value("${larder.inbox.worker-id:worker-local}") String workerId) {
        this.mapper = mapper;
        this.leaseService = leaseService;
        this.runnerClient = runnerClient;
        this.workerId = workerId;
    }

    /**
     * 为已接管的 job 分配稳定 runId 并派发给 Runner。
     *
     * @return runId；job 已不在 RUNNING（被别人处理/已终态）时返回 null，调用方直接 ack
     */
    public String dispatch(String jobId) {
        Map<String, Object> row = mapper.findDispatch(jobId);
        if (row == null) {
            throw new IllegalStateException("job 不存在，无法派发: " + jobId);
        }
        String state = String.valueOf(row.get("state"));
        if (!"RUNNING".equals(state)) {
            return null;
        }
        long token = ((Number) row.get("token")).longValue();
        String inputJson = row.get("inputJson") == null ? "{}" : String.valueOf(row.get("inputJson"));
        String ownerId = String.valueOf(row.get("ownerId"));
        String itemId = row.get("itemId") == null ? null : String.valueOf(row.get("itemId"));
        String runId = leaseService.startRun(jobId, workerId, token);
        try {
            runnerClient.startRun(runId, jobId, ownerId, itemId, inputJson);
            log.info("已派发 Runner：jobId={} runId={}", jobId, runId);
            return runId;
        } catch (RuntimeException unavailable) {
            mapper.releaseForRetry(jobId, token);
            log.warn("Runner 派发失败，job 回 QUEUED 等待重投：jobId={} runId={} reason={}",
                    jobId, runId, unavailable.toString());
            throw unavailable;
        }
    }
}
