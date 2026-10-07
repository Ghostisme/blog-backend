package com.darkrich.blog.security;

import com.darkrich.blog.config.BlogProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 从 HttpOnly Cookie 中取出 JWT 并建立登录态。
 *
 * <p>只认 Cookie、不认 Authorization 头：令牌对 JS 不可见是本方案防 XSS 窃取令牌的核心，
 * 若再开一个头部入口，就等于给“把令牌拿到手里”留了合法用途，容易被后续改动带偏。
 *
 * <p>令牌无效时不在这里直接拒绝，而是什么都不设置，交给后面的授权规则：
 * 公开接口照常放行，受保护接口会触发 401。这样一个过期的旧 Cookie 不会连累公开页面。
 *
 * <p>注意：本类不是 Spring Bean（见 SecurityConfig），避免被 Boot 自动注册成 Servlet 过滤器而执行两次。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final String cookieName;
    private final String adminUsername;

    public JwtAuthFilter(JwtService jwtService, BlogProperties properties) {
        this.jwtService = jwtService;
        this.cookieName = properties.cookie().name();
        this.adminUsername = properties.admin().username();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = readToken(request);
        if (token != null) {
            jwtService.verify(token)
                    // 令牌里的用户名必须仍是当前配置的管理员：改了管理员用户名后，旧令牌随即作废
                    .filter(adminUsername::equals)
                    .ifPresent(username -> SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(
                                    username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))));
        }
        chain.doFilter(request, response);
    }

    private String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (cookieName.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
