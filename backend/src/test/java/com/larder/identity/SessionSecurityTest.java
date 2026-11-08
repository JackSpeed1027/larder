package com.larder.identity;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证骨架的可证伪测试。
 *
 * <p>每个用例都能在错误实现下失败：去掉 CSRF → 第 3 条红；忘记 saveContext → 第 4 条红；
 * 不校验密码 → 第 2 条红；登出只清内存不 invalidate session → 第 5 条红。
 */
@WebMvcTest(controllers = SessionController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "larder.identity.username=admin",
        "larder.identity.password=correct-horse"
})
class SessionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("未登录访问 /api/session 返回 401")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/api/session"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET 会下发 XSRF-TOKEN cookie（SPA 首次请求就能拿到）")
    void csrfCookieIsIssued() throws Exception {
        mockMvc.perform(get("/api/session"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    @DisplayName("不带 CSRF 的登录 POST 被拒绝（403）")
    void loginWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("密码错误返回 401 且错误码是 NEED_LOGIN")
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/session").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NEED_LOGIN"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    @DisplayName("登录成功后同一 session 能读到当前用户")
    void loginPersistsSession() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/session").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andReturn();

        HttpSession session = login.getRequest().getSession(false);
        assertThat(session).as("登录必须真的创建 session").isNotNull();

        mockMvc.perform(get("/api/session").session((MockHttpSession) session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }

    @Test
    @DisplayName("登出后 session 失效，再访问返回 401")
    void logoutInvalidatesSession() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/session").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(delete("/api/session").session(session).with(csrf()))
                .andExpect(status().isOk());
        assertThat(session.isInvalid()).as("登出必须 invalidate 原 session").isTrue();

        mockMvc.perform(get("/api/session"))
                .andExpect(status().isUnauthorized());
    }
}
