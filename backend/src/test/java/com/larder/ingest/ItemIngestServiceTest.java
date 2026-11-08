package com.larder.ingest;

import com.larder.content.model.Item;
import com.larder.content.model.ItemKind;
import com.larder.content.model.ItemMapper;
import com.larder.pipeline.model.IdempotencyRequest;
import com.larder.pipeline.model.IdempotencyState;
import com.larder.pipeline.model.Job;
import com.larder.pipeline.model.JobMapper;
import com.larder.pipeline.model.JobState;
import com.larder.pipeline.model.OutboxEvent;
import com.larder.pipeline.model.OutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收录服务的状态机测试（不连数据库）。
 *
 * <p>覆盖四条主路径：新建、URL 去重、同 body 重放、同 key 不同 body 冲突。
 * 数据库唯一约束在容器/集成环境验证；这里验证"拿到约束结果后的分支"是否正确。
 */
class ItemIngestServiceTest {

    private OwnerService ownerService;
    private IdempotencyStore idempotencyStore;
    private ItemMapper itemMapper;
    private JobMapper jobMapper;
    private OutboxEventMapper outboxMapper;
    private ItemIngestService service;

    @BeforeEach
    void setUp() {
        ownerService = mock(OwnerService.class);
        idempotencyStore = mock(IdempotencyStore.class);
        itemMapper = mock(ItemMapper.class);
        jobMapper = mock(JobMapper.class);
        outboxMapper = mock(OutboxEventMapper.class);
        service = new ItemIngestService(ownerService, idempotencyStore, itemMapper, jobMapper, outboxMapper);
        when(ownerService.ownerIdFor("admin")).thenReturn("own_1");
        when(itemMapper.insert(any(Item.class))).thenReturn(1);
        when(jobMapper.insert(any(Job.class))).thenReturn(1);
        when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);
    }

    @Test
    @DisplayName("新建 WEB：写 item/job/outbox，返回 202 CREATED")
    void createsWebItemWithJobAndOutbox() {
        when(idempotencyStore.tryBegin(eq("own_1"), anyString(), anyString(), anyString())).thenReturn(true);
        when(itemMapper.selectOne(any())).thenReturn(null);

        IngestOutcome outcome = service.ingest("admin", "idem-key-0001",
                new CommandBody(ItemKind.WEB, null, "https://Example.com/a", null, null));

        assertThat(outcome.httpStatus()).isEqualTo(202);
        assertThat(outcome.dedupe()).isEqualTo("CREATED");
        assertThat(outcome.itemId()).startsWith("itm_");
        assertThat(outcome.jobId()).startsWith("job_");

        ArgumentCaptor<Job> job = ArgumentCaptor.forClass(Job.class);
        verify(jobMapper).insert(job.capture());
        assertThat(job.getValue().getState()).isEqualTo(JobState.QUEUED.name());
        assertThat(job.getValue().getKind()).isEqualTo("INGEST");
        assertThat(job.getValue().getInputJson()).contains("https://Example.com/a");

        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxMapper).insert(outbox.capture());
        assertThat(outbox.getValue().getEventType()).isEqualTo("job.queued.ingest");
        assertThat(outbox.getValue().getState()).isEqualTo("PENDING");

        verify(idempotencyStore).complete(eq("own_1"), eq(ItemIngestService.ROUTE), eq("idem-key-0001"),
                eq(202), anyString());
    }

    @Test
    @DisplayName("同 URL 已存在：返回 200 EXISTING，且不重复插入 item")
    void dedupesByNormalizedUrl() {
        when(idempotencyStore.tryBegin(eq("own_1"), anyString(), anyString(), anyString())).thenReturn(true);
        Item existing = new Item();
        existing.setId("itm_existing");
        existing.setOwnerId("own_1");
        when(itemMapper.selectOne(any())).thenReturn(existing);
        Job existingJob = new Job();
        existingJob.setId("job_existing");
        when(jobMapper.selectOne(any())).thenReturn(existingJob);

        IngestOutcome outcome = service.ingest("admin", "idem-key-0002",
                new CommandBody(ItemKind.WEB, null, "https://example.com/a", null, null));

        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.dedupe()).isEqualTo("EXISTING");
        assertThat(outcome.itemId()).isEqualTo("itm_existing");
        assertThat(outcome.jobId()).isEqualTo("job_existing");
        verify(itemMapper, never()).insert(any(Item.class));
    }

    @Test
    @DisplayName("同 key 同 body 重放：返回首次快照，不新建")
    void replaysSameBody() {
        when(idempotencyStore.tryBegin(eq("own_1"), anyString(), anyString(), anyString())).thenReturn(false);
        IdempotencyRequest row = completedRow("own_1", "idem-key-0003",
                new CommandBody(ItemKind.TEXT, "t", null, "hello", null).bodyHash(),
                "{\"itemId\":\"itm_a\",\"jobId\":\"job_a\",\"dedupe\":\"CREATED\"}", 202);
        when(idempotencyStore.find("own_1", ItemIngestService.ROUTE, "idem-key-0003")).thenReturn(row);

        IngestOutcome outcome = service.ingest("admin", "idem-key-0003",
                new CommandBody(ItemKind.TEXT, "t", null, "hello", null));

        assertThat(outcome.itemId()).isEqualTo("itm_a");
        assertThat(outcome.jobId()).isEqualTo("job_a");
        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.dedupe()).isEqualTo("REPLAYED");
    }

    @Test
    @DisplayName("同 key 不同 body：409 IDEMPOTENCY_CONFLICT，且不产生任何写入")
    void rejectsDifferentBody() {
        when(idempotencyStore.tryBegin(eq("own_1"), anyString(), anyString(), anyString())).thenReturn(false);
        IdempotencyRequest row = completedRow("own_1", "idem-key-0004", "different-hash",
                "{\"itemId\":\"itm_a\"}", 200);
        when(idempotencyStore.find("own_1", ItemIngestService.ROUTE, "idem-key-0004")).thenReturn(row);

        assertThatThrownBy(() -> service.ingest("admin", "idem-key-0004",
                new CommandBody(ItemKind.TEXT, "t", null, "hello", null)))
                .isInstanceOf(IngestRejectedException.class)
                .satisfies(ex -> {
                    IngestRejectedException rejected = (IngestRejectedException) ex;
                    assertThat(rejected.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    assertThat(rejected.httpStatus()).isEqualTo(409);
                });
        verify(itemMapper, never()).insert(any(Item.class));
    }

    @Test
    @DisplayName("WEB 缺 url / TEXT 缺 text 直接 400，不进入幂等流程")
    void validatesInputShape() {
        assertThatThrownBy(() -> service.ingest("admin", "idem-key-0005",
                new CommandBody(ItemKind.WEB, null, null, null, null)))
                .isInstanceOf(IngestRejectedException.class)
                .hasMessageContaining("url");
        assertThatThrownBy(() -> service.ingest("admin", "idem-key-0006",
                new CommandBody(ItemKind.TEXT, null, null, null, null)))
                .isInstanceOf(IngestRejectedException.class)
                .hasMessageContaining("text");
        verify(idempotencyStore, never()).tryBegin(anyString(), anyString(), anyString(), anyString());
    }

    private IdempotencyRequest completedRow(String owner, String key, String bodyHash, String json, int status) {
        IdempotencyRequest row = new IdempotencyRequest();
        row.setOwnerId(owner);
        row.setRouteKey(ItemIngestService.ROUTE);
        row.setRequestKey(key);
        row.setBodyHash(bodyHash);
        row.setState(IdempotencyState.COMPLETED.name());
        row.setResponseStatus(status);
        row.setResponseJson(json);
        return row;
    }
}
