package com.larder.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 保证 GET/HEAD 请求后浏览器拿到 CSRF cookie。
 *
 * <p>Spring Security 6/7 的 CSRF token 延迟生成：不主动 load 一次，cookie 可能不出现，
 * SPA 首次 POST 就 403。本过滤器纳入安全过滤链（不是独立 @Component），避免被容器重复注册。
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    private final CsrfTokenRepository tokenRepository;

    public CsrfCookieFilter(CsrfTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod())) {
            CsrfToken token = tokenRepository.loadToken(request);
            if (token == null) {
                token = tokenRepository.generateToken(request);
                tokenRepository.saveToken(token, request, response);
            }
            request.setAttribute(CsrfToken.class.getName(), token);
            request.setAttribute(token.getParameterName(), token);
        }
        filterChain.doFilter(request, response);
    }
}
