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
 * <p>S01 合同：幂等登记必须与业务写入、Outbox、响应快照在**同一个短事务**内提交。
 * 不用 REQUIRES_NEW 抢占：那会产生“登记成功但业务没做”的 PROCESSING 僵尸行。
 * 并发插入冲突时，由调用方在事务结束后用 {@link #find} 独立读取结果。
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

    /** 在调用方事务内登记幂等键；false 表示键已被占用（同 key 并发或重放）。 */
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
