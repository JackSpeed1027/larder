package com.larder.ingest;

import com.larder.content.model.ItemKind;
import com.larder.content.model.ItemMapper;
import com.larder.pipeline.model.JobMapper;
import com.larder.pipeline.model.OutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** S04 背压：活跃任务达上限时拒绝新收录，返回 429（retryable）。 */
class TooManyActiveJobsTest {

    private JobMapper jobMapper;
    private ItemIngestService service;

    @BeforeEach
    void setUp() {
        OwnerService ownerService = mock(OwnerService.class);
        IdempotencyStore store = mock(IdempotencyStore.class);
        ItemMapper itemMapper = mock(ItemMapper.class);
        jobMapper = mock(JobMapper.class);
        OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);

        when(ownerService.ownerIdFor("admin")).thenReturn("own_1");
        when(store.tryBegin(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        service = new ItemIngestService(ownerService, store, itemMapper, jobMapper, outboxMapper);
        ReflectionTestUtils.setField(service, "maxActiveJobs", 3);
    }

    @Test
    @DisplayName("活跃任务数 >= 上限：429 TOO_MANY_ACTIVE_JOBS，不创建 item")
    void rejectsWhenActiveJobsAtLimit() {
        when(jobMapper.countActiveByOwner("own_1")).thenReturn(3L);

        CommandBody body = new CommandBody(ItemKind.TEXT, "t", null, "hello", null);
        assertThatThrownBy(() -> service.ingest("admin", "idem-backpressure-0001", body))
                .isInstanceOf(IngestRejectedException.class)
                .satisfies(ex -> {
                    IngestRejectedException rejected = (IngestRejectedException) ex;
                    org.assertj.core.api.Assertions.assertThat(rejected.code()).isEqualTo("TOO_MANY_ACTIVE_JOBS");
                    org.assertj.core.api.Assertions.assertThat(rejected.httpStatus()).isEqualTo(429);
                    org.assertj.core.api.Assertions.assertThat(rejected.retryable()).isTrue();
                });
    }
}
