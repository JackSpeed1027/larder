package com.larder.pipeline.inbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费去重与接管的分支测试。
 *
 * <p>关键断言是"inbox 不能掩盖未执行 job"：重复投递时如果 job 还在 QUEUED，
 * 必须重新接管（或抛错重投），而不是因为 inbox 有记录就直接 ack。
 */
class InboxHandlerTest {

    private InboxMapper mapper;
    private InboxHandler handler;

    @BeforeEach
    void setUp() {
        mapper = mock(InboxMapper.class);
        handler = new InboxHandler(mapper, "ingest-consumer", "worker-test", 120);
    }

    @Test
    @DisplayName("首次投递：写 inbox 并接管 job → ACCEPTED")
    void acceptsFirstDelivery() {
        when(mapper.insertInbox(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.claimJob(anyString(), anyString(), any(LocalDateTime.class))).thenReturn(1);

        assertThat(handler.handle("out_1", "job_1", "a".repeat(64))).isEqualTo(InboxOutcome.ACCEPTED);
        verify(mapper).claimJob(org.mockito.ArgumentMatchers.eq("job_1"),
                org.mockito.ArgumentMatchers.eq("worker-test"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("重复投递但 job 已被别人 RUNNING：不重复接管，返回 ALREADY_RUNNING")
    void duplicateWhileRunning() {
        when(mapper.insertInbox(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("dup"));
        when(mapper.findInbox("ingest-consumer", "out_1")).thenReturn(existing("job_1", "a".repeat(64)));
        when(mapper.claimJob(anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);
        when(mapper.jobState("job_1")).thenReturn("RUNNING");

        assertThat(handler.handle("out_1", "job_1", "a".repeat(64))).isEqualTo(InboxOutcome.ALREADY_RUNNING);
    }

    @Test
    @DisplayName("重复投递且 job 已是终态：ALREADY_DONE，不重复执行")
    void duplicateAfterDone() {
        when(mapper.insertInbox(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("dup"));
        when(mapper.findInbox("ingest-consumer", "out_1")).thenReturn(existing("job_1", "a".repeat(64)));
        when(mapper.claimJob(anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);
        when(mapper.jobState("job_1")).thenReturn("SUCCEEDED");

        assertThat(handler.handle("out_1", "job_1", "a".repeat(64))).isEqualTo(InboxOutcome.ALREADY_DONE);
    }

    @Test
    @DisplayName("同 event_id 但 payload 变了：CONFLICT（坏消息，进死信）")
    void payloadConflict() {
        when(mapper.insertInbox(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("dup"));
        when(mapper.findInbox("ingest-consumer", "out_1")).thenReturn(existing("job_1", "b".repeat(64)));

        assertThat(handler.handle("out_1", "job_1", "a".repeat(64))).isEqualTo(InboxOutcome.CONFLICT);
    }

    @Test
    @DisplayName("inbox 有记录但 job 仍是 QUEUED：必须重新接管，不能静默 ack")
    void doesNotHideUnclaimedJob() {
        when(mapper.insertInbox(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("dup"));
        when(mapper.findInbox("ingest-consumer", "out_1")).thenReturn(existing("job_1", "a".repeat(64)));
        when(mapper.claimJob(anyString(), anyString(), any(LocalDateTime.class))).thenReturn(1);

        assertThat(handler.handle("out_1", "job_1", "a".repeat(64))).isEqualTo(InboxOutcome.ACCEPTED);
    }

    @Test
    @DisplayName("job 不存在：UNKNOWN_JOB（进死信）")
    void unknownJob() {
        when(mapper.insertInbox(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.claimJob(anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);
        when(mapper.jobState("job_x")).thenReturn(null);

        assertThat(handler.handle("out_x", "job_x", "a".repeat(64))).isEqualTo(InboxOutcome.UNKNOWN_JOB);
    }

    @Test
    @DisplayName("接管失败但 job 仍 QUEUED：抛错让消息重投，而不是吞掉")
    void throwsWhenClaimUnexpectedlyFails() {
        when(mapper.insertInbox(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.claimJob(anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);
        when(mapper.jobState("job_1")).thenReturn("QUEUED");

        assertThatThrownBy(() -> handler.handle("out_1", "job_1", "a".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).jobState(null);
    }

    private InboxRecord existing(String jobId, String hash) {
        InboxRecord record = new InboxRecord();
        record.setConsumer("ingest-consumer");
        record.setEventId("out_1");
        record.setJobId(jobId);
        record.setPayloadHash(hash);
        return record;
    }
}
