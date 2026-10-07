package com.darkrich.blog.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * CSRF 防护：管理接口的写操作要求 Origin 与 Host 同源。
 *
 * <p>为什么不用 Spring 自带的 CSRF token：登录态在 {@code SameSite=Strict} 的 Cookie 里，
 * 浏览器本身就不会在跨站请求里带上它；这里再校验 Origin 作为第二道防线
 * （覆盖老浏览器对 SameSite 支持不全的情况），比起维护 token 的下发/回传，
 * 对“前后端同域部署”的场景更简单且不易出错。
 *
 * <p>没有 Origin 头的请求（curl、服务器间调用）放行：攻击者无法让受害者的浏览器
 * 发出“不带 Origin 却带着 Cookie”的跨站写请求，所以这不构成绕过。
 *
 * <p>依赖 Nginx 以 {@code proxy_set_header Host $http_host} 透传原始 Host（含端口）。
 */
public class SameOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final String PROTECTED_PREFIX = "/api/admin";

    private final JsonSecurityHandlers errorWriter;

    public SameOriginFilter(JsonSecurityHandlers errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod())
                || !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !matchesHost(origin, request.getHeader("Host"))) {
            errorWriter.write(response, HttpStatus.FORBIDDEN, "跨站请求被拒绝");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Origin 的 authority（主机:端口）是否与 Host 头一致。任何解析失败都按“不一致”处理。 */
    private boolean matchesHost(String origin, String host) {
        if (host == null) {
            return false;
        }
        try {
            String authority = URI.create(origin).getAuthority();
            return authority != null && authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
