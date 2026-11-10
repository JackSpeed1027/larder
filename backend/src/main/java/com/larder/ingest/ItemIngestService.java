package com.larder.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.larder.content.model.Item;
import com.larder.content.model.ItemKind;
import com.larder.content.model.ItemMapper;
import com.larder.pipeline.model.IdempotencyRequest;
import com.larder.pipeline.model.IdempotencyState;
import com.larder.pipeline.model.Job;
import com.larder.pipeline.model.JobKind;
import com.larder.pipeline.model.JobMapper;
import com.larder.pipeline.model.JobState;
import com.larder.pipeline.model.OutboxEvent;
import com.larder.pipeline.model.OutboxEventMapper;
import com.larder.pipeline.model.OutboxState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 收录入口的业务实现（V02-T01）。
 *
 * <p>一次 TX 内完成：item + job(QUEUED) + outbox(job.queued) + 幂等记录完成态。
 * 四者同事务，避免"有 item 没 job"或"有 job 没通知"的半成品。
 *
 * <p>幂等语义（docs/04）：
 * <ul>
 *   <li>同 Idempotency-Key + 同 body → 返回首次结果（200）；</li>
 *   <li>同 key + 不同 body → 409 IDEMPOTENCY_CONFLICT；</li>
 *   <li>同 owner + 同规范化 URL（不同 key）→ 返回已存在 item（200）。</li>
 * </ul>
 * 并发唯一由 idempotency_request 主键与 uk_item_url 两个数据库约束保证。
 */
@Service
public class ItemIngestService {

    static final String ROUTE = "POST /api/items";
    private static final int NEW_ITEM_STATUS = 202;
    private static final int EXISTING_ITEM_STATUS = 200;

    private final OwnerService ownerService;
    private final IdempotencyStore idempotencyStore;
    private final ItemMapper itemMapper;
    private final JobMapper jobMapper;
    private final OutboxEventMapper outboxEventMapper;

    public ItemIngestService(OwnerService ownerService,
                             IdempotencyStore idempotencyStore,
                             ItemMapper itemMapper,
                             JobMapper jobMapper,
                             OutboxEventMapper outboxEventMapper) {
        this.ownerService = ownerService;
        this.idempotencyStore = idempotencyStore;
        this.itemMapper = itemMapper;
        this.jobMapper = jobMapper;
        this.outboxEventMapper = outboxEventMapper;
    }

    // READ_COMMITTED：并发冲突（uk_item_url）后要能立刻读到赢家已提交的行；
    // 默认 REPEATABLE_READ 的快照会让冲突后的回读看不到对方，误抛 500。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IngestOutcome ingest(String username, String idempotencyKey, CommandBody body) {
        validate(body);
        String ownerId = ownerService.ownerIdFor(username);
        String bodyHash = body.bodyHash();

        if (!idempotencyStore.tryBegin(ownerId, ROUTE, idempotencyKey, bodyHash)) {
            return resolveExisting(ownerId, idempotencyKey, bodyHash);
        }
        try {
            return createOrDedupe(ownerId, idempotencyKey, body, bodyHash);
        } catch (DuplicateKeyException raced) {
            // 两个不同 key 同时收录同一个 URL：唯一键 uk_item_url 只会放行一个
            Item existing = findByUrlHash(ownerId, body);
            if (existing == null) {
                throw raced;
            }
            return completeExisting(ownerId, idempotencyKey, existing, bodyHash);
        }
    }

    private IngestOutcome createOrDedupe(String ownerId, String key, CommandBody body, String bodyHash) {
        Item existing = findByUrlHash(ownerId, body);
        if (existing != null) {
            return completeExisting(ownerId, key, existing, bodyHash);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Item item = new Item();
        item.setId(Ids.next("itm"));
        item.setOwnerId(ownerId);
        item.setKind(body.kind().name());
        item.setTitle(resolveTitle(body));
        item.setProcessingEpoch(0L);
        item.setMetadataVersion(0L);
        item.setRowVersion(0L);
        item.setReadStatus("UNREAD");
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        if (body.kind() == ItemKind.WEB) {
            String normalized = UrlNormalizer.normalize(body.url());
            item.setOriginalUrl(body.url());
            item.setNormalizedUrl(normalized);
            item.setUrlHash(Json.sha256(normalized));
        }
        itemMapper.insert(item);

        String inputJson = Json.canonical(
                "itemId", item.getId(),
                "kind", body.kind().name(),
                "url", body.url(),
                "text", body.text(),
                "uploadId", body.uploadId());
        Job job = newJob(ownerId, item.getId(), inputJson, now);
        jobMapper.insert(job);

        OutboxEvent outbox = newOutbox(ownerId, job.getId(), item.getId(), now);
        outboxEventMapper.insert(outbox);

        String responseJson = Json.canonical(
                "itemId", item.getId(), "jobId", job.getId(), "dedupe", "CREATED");
        idempotencyStore.complete(ownerId, ROUTE, key, NEW_ITEM_STATUS, responseJson);
        return new IngestOutcome(item.getId(), job.getId(), "CREATED", NEW_ITEM_STATUS);
    }

    private IngestOutcome completeExisting(String ownerId, String key, Item existing, String bodyHash) {
        Job latestJob = latestJob(ownerId, existing.getId());
        String jobId = latestJob == null ? null : latestJob.getId();
        String responseJson = Json.canonical(
                "itemId", existing.getId(), "jobId", jobId, "dedupe", "EXISTING");
        idempotencyStore.complete(ownerId, ROUTE, key, EXISTING_ITEM_STATUS, responseJson);
        return new IngestOutcome(existing.getId(), jobId, "EXISTING", EXISTING_ITEM_STATUS);
    }

    /**
     * 已有幂等记录的处理（S01）：
     * COMPLETED + 同 body → 重放首次结果；同 key 不同 body → PAYLOAD_MISMATCH；
     * PROCESSING（并发赢家尚未提交/或历史僵尸行）→ IN_PROGRESS，要求用原 key 原 body 重试。
     * 只读一次，不在事务内 sleep 轮询。
     */
    private IngestOutcome resolveExisting(String ownerId, String key, String bodyHash) {
        IdempotencyRequest row = idempotencyStore.find(ownerId, ROUTE, key);
        if (row == null) {
            throw IngestRejectedException.idempotencyInProgress("幂等记录尚不可见，请用同一 Idempotency-Key 重试");
        }
        if (!row.getBodyHash().equals(bodyHash)) {
            throw IngestRejectedException.idempotencyPayloadMismatch("同一 Idempotency-Key 不能用于不同请求体");
        }
        if (!IdempotencyState.COMPLETED.name().equals(row.getState())) {
            throw IngestRejectedException.idempotencyInProgress("相同请求正在处理中，请稍后用同一 Idempotency-Key 重试");
        }
        String json = row.getResponseJson() == null ? "{}" : row.getResponseJson();
        // 重复请求按 docs/04 的接口语义返回 200（不是首次的 202）：结果相同、状态表示“已存在”。
        return new IngestOutcome(
                JsonValue.read(json, "itemId"),
                JsonValue.read(json, "jobId"),
                "REPLAYED",
                EXISTING_ITEM_STATUS);
    }

    private void validate(CommandBody body) {
        if (body.kind() == null) {
            throw IngestRejectedException.validation("kind 不能为空");
        }
        switch (body.kind()) {
            case WEB -> {
                if (body.url() == null || body.url().isBlank()) {
                    throw IngestRejectedException.validation("kind=WEB 必须提供 url");
                }
                if (body.text() != null || body.uploadId() != null) {
                    throw IngestRejectedException.validation("WEB 输入不允许同时携带 text/uploadId");
                }
            }
            case TEXT -> {
                if (body.text() == null || body.text().isBlank()) {
                    throw IngestRejectedException.validation("kind=TEXT 必须提供 text");
                }
                if (body.url() != null || body.uploadId() != null) {
                    throw IngestRejectedException.validation("TEXT 输入不允许同时携带 url/uploadId");
                }
            }
            default -> throw IngestRejectedException.validation(
                    "V02-T01 只支持 WEB/TEXT，其余类型在对应版本实现");
        }
    }

    private Item findByUrlHash(String ownerId, CommandBody body) {
        if (body.kind() != ItemKind.WEB) {
            return null;
        }
        String urlHash = UrlNormalizer.hash(body.url());
        return itemMapper.selectOne(Wrappers.<Item>lambdaQuery()
                .eq(Item::getOwnerId, ownerId)
                .eq(Item::getUrlHash, urlHash));
    }

    private Job latestJob(String ownerId, String itemId) {
        return jobMapper.selectOne(Wrappers.<Job>lambdaQuery()
                .eq(Job::getOwnerId, ownerId)
                .eq(Job::getItemId, itemId)
                .orderByDesc(Job::getCreatedAt)
                .last("limit 1"));
    }

    private Job newJob(String ownerId, String itemId, String inputJson, LocalDateTime now) {
        Job job = new Job();
        job.setId(Ids.next("job"));
        job.setOwnerId(ownerId);
        job.setItemId(itemId);
        job.setKind(JobKind.INGEST.name());
        job.setState(JobState.QUEUED.name());
        job.setProcessingEpoch(0L);
        job.setAttempt(1);
        job.setPayloadHash(Json.sha256(inputJson));
        job.setInputJson(inputJson);
        job.setLastEventSeq(0L);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    private OutboxEvent newOutbox(String ownerId, String jobId, String itemId, LocalDateTime now) {
        String payload = Json.canonical("jobId", jobId, "itemId", itemId, "kind", JobKind.INGEST.name());
        OutboxEvent event = new OutboxEvent();
        event.setEventId(Ids.next("out"));
        event.setOwnerId(ownerId);
        event.setJobId(jobId);
        event.setEventType("job.queued.ingest");
        event.setWakeGeneration(1L);
        event.setPayloadJson(payload);
        event.setPayloadHash(Json.sha256(payload));
        event.setState(OutboxState.PENDING.name());
        event.setAvailableAt(now);
        event.setAttempt(0);
        event.setCreatedAt(now);
        return event;
    }

    private String resolveTitle(CommandBody body) {
        if (body.title() != null && !body.title().isBlank()) {
            return body.title().trim();
        }
        if (body.kind() == ItemKind.WEB) {
            return body.url().trim();
        }
        String text = body.text().trim();
        return text.length() <= 80 ? text : text.substring(0, 80);
    }
}
