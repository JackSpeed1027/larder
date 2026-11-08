package com.larder.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.larder.pipeline.model.IdempotencyRequest;
import com.larder.pipeline.model.IdempotencyRequestMapper;
import com.larder.pipeline.model.IdempotencyState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 幂等键的持久化操作。
 *
 * <p>关键点：{@code tryBegin} 必须用 REQUIRES_NEW 独立事务。如果用同一个事务，
 * 插入冲突会把外层事务标记成 rollback-only，后续回读和返回 409/200 都会失败。
 *
 * <p>主键 (owner_id, route_key, request_key) 是并发唯一的最终保证：
 * 先查后插只是优化，不能替代约束。
 */
@Service
public class IdempotencyStore {

    private final IdempotencyRequestMapper mapper;

    public IdempotencyStore(IdempotencyRequestMapper mapper) {
        this.mapper = mapper;
    }

    /** 抢占幂等键；false 表示已被别人占用（可能是同请求重放，也可能是不同请求冲突）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryBegin(String ownerId, String routeKey, String requestKey, String bodyHash) {
        IdempotencyRequest row = new IdempotencyRequest();
        row.setOwnerId(ownerId);
        row.setRouteKey(routeKey);
        row.setRequestKey(requestKey);
        row.setBodyHash(bodyHash);
        row.setState(IdempotencyState.PROCESSING.name());
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            mapper.insert(row);
            return true;
        } catch (DuplicateKeyException taken) {
            return false;
        }
    }

    /** 独立事务读，确保能看到其他线程已提交的 COMPLETED 状态。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public IdempotencyRequest find(String ownerId, String routeKey, String requestKey) {
        return mapper.selectOne(Wrappers.<IdempotencyRequest>lambdaQuery()
                .eq(IdempotencyRequest::getOwnerId, ownerId)
                .eq(IdempotencyRequest::getRouteKey, routeKey)
                .eq(IdempotencyRequest::getRequestKey, requestKey));
    }

    /** 写入完成状态与响应快照（与业务同事务，失败则一起回滚）。 */
    public void complete(String ownerId, String routeKey, String requestKey, int status, String responseJson) {
        IdempotencyRequest update = new IdempotencyRequest();
        update.setState(IdempotencyState.COMPLETED.name());
        update.setResponseStatus(status);
        update.setResponseJson(responseJson);
        mapper.update(update, Wrappers.<IdempotencyRequest>lambdaUpdate()
                .eq(IdempotencyRequest::getOwnerId, ownerId)
                .eq(IdempotencyRequest::getRouteKey, routeKey)
                .eq(IdempotencyRequest::getRequestKey, requestKey));
    }
}
