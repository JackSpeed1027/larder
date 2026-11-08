package com.larder.identity;

import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * 最小认证骨架：单管理员、同源 Cookie session、CSRF 双提交。
 *
 * <p>职责边界（docs/06 §3）：session cookie HttpOnly + SameSite；状态变更必须带 CSRF；
 * 未登录统一 401（SPA 不跳登录页）。
 *
 * <p>账号只来自环境变量：优先 LARDER_ADMIN_PASSWORD_HASH（bcrypt），开发期允许
 * LARDER_ADMIN_PASSWORD 启动时现算 hash；两者都没有则启动失败——不允许默认密码。
 *
 * <p>V01 限制：session 在内存（单实例够用）。docs/06 要求落 DB，等 V02 数据层就绪后换 JdbcSession。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 公开路径：登录/登出/当前用户、健康检查。其余 /api 一律要求已认证。 */
    private static final String[] PUBLIC_PATHS = {
            "/api/session", "/actuator/health", "/actuator/health/**", "/healthz"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${larder.identity.username:admin}") String username,
            @Value("${larder.identity.password:}") String rawPassword,
            @Value("${larder.identity.password-hash:}") String passwordHash,
            PasswordEncoder encoder) {
        String stored;
        if (!passwordHash.isBlank()) {
            stored = passwordHash;
        } else if (!rawPassword.isBlank()) {
            stored = encoder.encode(rawPassword);
        } else {
            throw new IllegalStateException(
                    "必须配置 LARDER_ADMIN_PASSWORD_HASH（或开发用的 LARDER_ADMIN_PASSWORD）；拒绝内置默认密码");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(stored).roles("ADMIN").build());
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    /** SPA 读 cookie 回传 header：XSRF-TOKEN（非 HttpOnly）+ X-XSRF-TOKEN。 */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository)
            throws Exception {
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(requestHandler))
                .addFilterAfter(new CsrfCookieFilter(csrfTokenRepository), CsrfFilter.class)
                .sessionManagement(sm -> sm
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable()); // 登出由 SessionController 显式处理，便于返回统一 JSON
        return http.build();
    }
}
