package com.larder.pipeline.lease;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S05：事件 seq 必须取自 job.last_event_seq；没有序号时不编造。 */
class JobEventLogTest {

    private JobEventMapper mapper;
    private JobEventLog log;

    @BeforeEach
    void setUp() {
        mapper = mock(JobEventMapper.class);
        log = new JobEventLog(mapper);
    }

    @Test
    @DisplayName("正常追加：seq 来自 job.last_event_seq")
    void appendsWithDatabaseSeq() {
        when(mapper.lastEventSeq("own_1", "job_1")).thenReturn(7L);

        assertThat(log.append("own_1", "job_1", "SUCCEEDED", "SUCCEEDED", "run_1", 0, null)).isTrue();

        verify(mapper).insert(anyString(), anyString(), anyLong(), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("没有事件序号：不写事件也不伪造 seq")
    void skipsWhenSeqMissing() {
        when(mapper.lastEventSeq("own_1", "job_1")).thenReturn(null);

        assertThat(log.append("own_1", "job_1", "RUNNING", "RUNNING", null, 0, null)).isFalse();

        verify(mapper, never()).insert(anyString(), anyString(), anyLong(), anyString(), anyString(), any());
    }
}
