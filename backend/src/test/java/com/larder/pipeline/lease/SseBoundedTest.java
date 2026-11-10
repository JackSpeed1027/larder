package com.larder.pipeline.lease;

import com.larder.ingest.OwnerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** S05：SSE 连接必须有界；超限返回 503，而不是无限建线程。 */
class SseBoundedTest {

    private JobEventsController controller;

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    private JobEventsController controller(int maxConnections) {
        JobLeaseService lease = mock(JobLeaseService.class);
        OwnerService owner = mock(OwnerService.class);
        when(owner.ownerIdFor("admin")).thenReturn("own_1");
        when(lease.progress("job_1")).thenReturn(new JobProgress(
                "job_1", "own_1", "RUNNING", 1, "run_1", 0, null, "2026-11-10T00:00:00Z", 1L));
        controller = new JobEventsController(lease, owner,
                org.mockito.Mockito.mock(JobEventLog.class), 1, maxConnections);
        return controller;
    }

    private Authentication auth() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("admin");
        return authentication;
    }

    @Test
    @DisplayName("超过连接上限：503，而不是再开一个线程")
    void rejectsWhenConnectionsExhausted() {
        JobEventsController subject = controller(1);
        subject.events("job_1", null, auth());

        assertThatThrownBy(() -> subject.events("job_1", null, auth()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(503));
    }

    @Test
    @DisplayName("未认证 owner 的 job：404，不占用连接")
    void rejectsForeignJob() {
        JobEventsController subject = controller(1);
        Authentication other = mock(Authentication.class);
        when(other.getName()).thenReturn("intruder");

        assertThatThrownBy(() -> subject.events("job_1", null, other))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
    }
}
