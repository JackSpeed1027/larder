package com.larder.runner.ingest;

import com.larder.content.publication.PublicationResult;
import com.larder.content.publication.RevisionPublisher;
import com.larder.identity.SecurityConfig;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部回传接口的鉴权与错误码。
 *
 * <p>这几个用例能抓到"忘了校验令牌""令牌未配置时默认放行""runId 不一致仍接受"这类错误。
 */
@WebMvcTest(controllers = RunnerResultController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "larder.identity.username=admin",
        "larder.identity.password=correct-horse",
        "larder.internal.token=internal-secret"
})
class RunnerResultControllerTest {

    private static final String BODY = """
            {"jobId":"job_1","runId":"run_1","processingEpoch":0,"text":"hello",
             "contentSha256":"%s","extractorVersion":"v1","chunkerVersion":"v1","manifestJson":"{}"}
            """.formatted("a".repeat(64));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RevisionPublisher publisher;

    @Test
    @DisplayName("缺少内部令牌：401")
    void rejectsMissingToken() throws Exception {
        mockMvc.perform(post("/internal/runs/run_1/result")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NEED_LOGIN"));
    }

    @Test
    @DisplayName("令牌错误：401")
    void rejectsWrongToken() throws Exception {
        mockMvc.perform(post("/internal/runs/run_1/result")
                        .header("X-Internal-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("路径 runId 与 body 不一致：400")
    void rejectsRunIdMismatch() throws Exception {
        mockMvc.perform(post("/internal/runs/run_other/result")
                        .header("X-Internal-Token", "internal-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("合法回传：200 + revisionId")
    void acceptsValidResult() throws Exception {
        when(publisher.publish(any())).thenReturn(new PublicationResult("rev_1", "CREATED"));

        mockMvc.perform(post("/internal/runs/run_1/result")
                        .header("X-Internal-Token", "internal-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value("rev_1"))
                .andExpect(jsonPath("$.dedupe").value("CREATED"));
    }
}
