package com.darkrich.blog.security;

import com.darkrich.blog.config.BlogProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全规则：无状态、Cookie + JWT、默认拒绝。
 *
 * <p>原则是「白名单放行、其余一律拒绝」：新增接口忘了配置权限时，结果是 403 而不是意外公开。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                                   BlogProperties properties,
                                                   JsonSecurityHandlers handlers) throws Exception {
        // 过滤器以 new 方式创建而不是声明为 @Component：
        // Boot 会把容器里的 Filter Bean 另外注册进 Servlet 容器，导致每个请求执行两遍
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtService, properties);
        SameOriginFilter sameOriginFilter = new SameOriginFilter(handlers);

        http
                // 纯 JSON API + 无状态 Cookie：表单登录、Basic、Session、Spring 自带 CSRF 都用不上。
                // CSRF 改由 SameSite=Strict + SameOriginFilter 承担
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // 生产环境前后端同域（Nginx 反代），不需要 CORS；dev 走 Vite 代理也是同源
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.deny()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .authorizeHttpRequests(auth -> auth
                        // 登录/登出必须公开：登出要在令牌已过期时也能清掉 Cookie
                        .requestMatchers(HttpMethod.POST, "/api/admin/login", "/api/admin/logout").permitAll()
                        .requestMatchers("/api/admin/**").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/articles", "/api/articles/*", "/api/filters", "/api/resume").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(sameOriginFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 显式提供一个永远查不到用户的 UserDetailsService。
     *
     * <p>目的只有一个：阻止 Boot 自动配置出“user + 启动时随机口令”的内存账号。
     * 我们的登录走自己的 AuthController，不经过 Spring Security 的认证管理器。
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("不使用 UserDetailsService 认证");
        };
    }
}
