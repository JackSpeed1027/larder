package com.larder.identity;

import com.larder.common.contract.ApiErrorResponse;
import com.larder.common.contract.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Session 端点：登录 / 查看当前用户 / 登出。
 *
 * <p>会话只靠 HttpOnly Cookie 表达；登录成功必须显式 saveContext 到 session，
 * 否则同一请求内的 SecurityContext 不会延续到下一个请求（V01-T04 的测试就是抓这个）。
 *
 * <p>错误统一走 ApiErrorResponse 壳，错误码来自 contracts/error-codes.json：
 * 未登录 NEED_LOGIN(401)，密码错误也是 NEED_LOGIN（不区分"用户不存在/密码错"，避免枚举）。
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public SessionController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    /** 登录：成功创建 session，返回当前用户。 */
    @PostMapping
    public ApiResponse<SessionView> login(@RequestBody LoginRequest request,
                                          HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, httpRequest, httpResponse);

        return new ApiResponse<>("OK", "登录成功", view(authentication), traceId(httpRequest));
    }

    /** 当前登录态：未登录返回 401，而不是空对象。 */
    @GetMapping
    public ResponseEntity<ApiResponse<SessionView>> current(HttpServletRequest httpRequest) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    new ApiResponse<>("NEED_LOGIN", "未登录", null, traceId(httpRequest)));
        }
        return ResponseEntity.ok(new ApiResponse<>("OK", "已登录", view(authentication), traceId(httpRequest)));
    }

    /** 登出：使 session 失效并清空 SecurityContext。 */
    @DeleteMapping
    public ApiResponse<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(httpRequest, httpResponse, authentication);
        SecurityContextHolder.clearContext();
        return new ApiResponse<>("OK", "已退出", null, traceId(httpRequest));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> onAuthenticationFailure(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiErrorResponse(
                "NEED_LOGIN", "账号或密码不正确", traceId(request), false, Map.of()));
    }

    private SessionView view(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("ROLE_ADMIN")
                .replace("ROLE_", "");
        return new SessionView(authentication.getName(), role);
    }

    /** 链路标识：优先用调用方传入，便于前端日志与后端对齐。 */
    private String traceId(HttpServletRequest request) {
        String incoming = request.getHeader("X-Request-Id");
        return (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming;
    }
}
