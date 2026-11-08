package com.larder.content.api;

import com.larder.identity.SecurityConfig;
import com.larder.ingest.IngestOutcome;
import com.larder.ingest.IngestRejectedException;
import com.larder.ingest.ItemIngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 收录接口的 HTTP 语义测试。
 *
 * <p>断言的是状态码与错误码（202/200/400/401/409），不是"返回了 200 就行"：
 * 去掉 Idempotency-Key 校验、把 409 改成 200、或绕过 CSRF，任意一个都会让用例失败。
 */
@WebMvcTest(controllers = ItemController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "larder.identity.username=admin",
        "larder.identity.password=correct-horse"
})
class ItemControllerTest {

    private static final String BODY = "{\"kind\":\"WEB\",\"url\":\"https://example.com/a\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ItemIngestService ingestService;

    @Test
    @DisplayName("未登录 POST /api/items 返回 401")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/api/items").with(csrf())
                        .header("Idempotency-Key", "idem-key-0001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("缺少 Idempotency-Key 返回 400")
    void requiresIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/items").with(user("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("新建返回 202 + itemId/jobId")
    void createsItem() throws Exception {
        when(ingestService.ingest(anyString(), anyString(), any()))
                .thenReturn(new IngestOutcome("itm_1", "job_1", "CREATED", 202));

        mockMvc.perform(post("/api/items").with(user("admin")).with(csrf())
                        .header("Idempotency-Key", "idem-key-0002")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.itemId").value("itm_1"))
                .andExpect(jsonPath("$.data.jobId").value("job_1"))
                .andExpect(jsonPath("$.data.dedupe").value("CREATED"));
    }

    @Test
    @DisplayName("同 key 不同 body 返回 409 IDEMPOTENCY_CONFLICT")
    void reportsConflict() throws Exception {
        when(ingestService.ingest(anyString(), anyString(), any()))
                .thenThrow(new IngestRejectedException("IDEMPOTENCY_CONFLICT", "同 key 不同 body", 409, false));

        mockMvc.perform(post("/api/items").with(user("admin")).with(csrf())
                        .header("Idempotency-Key", "idem-key-0003")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    @DisplayName("未知 kind 返回 400，而不是 500")
    void rejectsUnknownKind() throws Exception {
        mockMvc.perform(post("/api/items").with(user("admin")).with(csrf())
                        .header("Idempotency-Key", "idem-key-0004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SONG\",\"url\":\"https://example.com/a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
