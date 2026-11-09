package com.larder.pipeline.outbox;

import com.larder.pipeline.model.OutboxState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Outbox 的短事务存取。
 *
 * <p>为什么"短"：领取只做"锁行 + 改状态 + 递增 relay_token + 写租约"，随即提交；
 * MQ 投递在事务外进行。拿着行锁发网络请求，会把连接池拖死（docs/02）。
 */
@Service
public class OutboxStore {

    private final OutboxRelayMapper mapper;

    public OutboxStore(OutboxRelayMapper mapper) {
        this.mapper = mapper;
    }

    /** 领取一批到期事件；返回已带新 relay_token 的行（事务内提交后即可投递）。 */
    @Transactional
    public List<OutboxRow> claim(String relayOwner, int batchSize, int leaseSeconds) {
        List<OutboxRow> candidates = mapper.selectClaimable(batchSize);
        LocalDateTime leaseUntil = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(leaseSeconds);
        List<OutboxRow> claimed = new ArrayList<>(candidates.size());
        for (OutboxRow row : candidates) {
            long nextToken = (row.getRelayToken() == null ? 0L : row.getRelayToken()) + 1L;
            if (mapper.claim(row.getEventId(), relayOwner, nextToken, leaseUntil) == 1) {
                row.setRelayToken(nextToken);
                row.setRelayOwner(relayOwner);
                row.setRelayLeaseUntil(leaseUntil);
                row.setState(OutboxState.PUBLISHING.name());
                claimed.add(row);
            }
        }
        return claimed;
    }

    /** ack 且无 return：标 SENT；返回 0 表示租约已被接管（迟到确认作废）。 */
    @Transactional
    public int markSent(String eventId, long token) {
        return mapper.markSent(eventId, token);
    }

    /**
     * 失败/退回/超时：attempt+1，未超限则退避回 PENDING，超限进 DEAD。
     *
     * @return 更新行数；0 表示 token/状态不匹配（结果作废）
     */
    @Transactional
    public int markFailed(String eventId, long token, int maxAttempts) {
        OutboxRow current = mapper.selectByEventId(eventId);
        if (current == null || current.getRelayToken() == null || current.getRelayToken() != token
                || !OutboxState.PUBLISHING.name().equals(current.getState())) {
            return 0;
        }
        int attempt = (current.getAttempt() == null ? 0 : current.getAttempt()) + 1;
        boolean dead = OutboxBackoff.exhausted(attempt, maxAttempts);
        LocalDateTime availableAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusSeconds(dead ? 0 : OutboxBackoff.delaySeconds(attempt));
        return mapper.markOutcome(eventId, token, attempt,
                dead ? OutboxState.DEAD.name() : OutboxState.PENDING.name(), availableAt);
    }

    /** 租约过期的 PUBLISHING 收回（进程崩溃恢复）：attempt+1 后按退避回 PENDING 或 DEAD。 */
    @Transactional
    public int reclaimExpired(int maxAttempts) {
        List<OutboxRow> expired = mapper.selectExpired();
        int reclaimed = 0;
        for (OutboxRow row : expired) {
            int attempt = (row.getAttempt() == null ? 0 : row.getAttempt()) + 1;
            boolean dead = OutboxBackoff.exhausted(attempt, maxAttempts);
            LocalDateTime availableAt = LocalDateTime.now(ZoneOffset.UTC)
                    .plusSeconds(dead ? 0 : OutboxBackoff.delaySeconds(attempt));
            reclaimed += mapper.markOutcome(row.getEventId(), row.getRelayToken(), attempt,
                    dead ? OutboxState.DEAD.name() : OutboxState.PENDING.name(), availableAt);
        }
        return reclaimed;
    }
}
