package com.darkrich.blog.module.auth;

import com.darkrich.blog.common.ApiResponse;
import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.config.BlogProperties;
import com.darkrich.blog.security.JwtService;
import com.darkrich.blog.security.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

/** 管理员登录 / 登出 / 当前登录态查询。令牌只通过 HttpOnly Cookie 传递，响应体里不返回。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AuthController {

    /** Cookie 的作用路径：只在管理接口上携带，前台公开页面的请求不会带上令牌。 */
    private static final String COOKIE_PATH = "/api/admin";

    private final BlogProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;

    /**
     * 登录请求。
     *
     * @param username 用户名
     * @param password 明文口令；上限 72 是 BCrypt 的输入长度上限，更长的部分会被静默截断，这里直接拒绝
     */
    public record LoginRequest(@NotBlank @Size(max = 64) String username,
                               @NotBlank @Size(max = 72) String password) {
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@Valid @RequestBody LoginRequest req,
                                                  HttpServletRequest request,
                                                  HttpServletResponse response) {
        // 已配置 server.forward-headers-strategy=native，这里拿到的是 Nginx 透传后的真实访客 IP
        String ip = request.getRemoteAddr();
        if (rateLimiter.isBlocked(ip)) {
            throw BusinessException.tooManyRequests("尝试次数过多，请稍后再试");
        }

        if (!credentialsMatch(req)) {
            rateLimiter.recordFailure(ip);
            // 用户名错和口令错返回同一句话，避免被用来探测用户名
            throw new BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        rateLimiter.reset(ip);
        String token = jwtService.issue(properties.admin().username());
        addCookie(response, token, Duration.ofSeconds(jwtService.ttlSeconds()));
        return ApiResponse.ok(Map.of("username", properties.admin().username()));
    }

    /** 前端启动时调用，用来判断 Cookie 是否仍有效（无效时被安全链直接返回 401）。 */
    @GetMapping("/me")
    public ApiResponse<Map<String, String>> me() {
        return ApiResponse.ok(Map.of("username", properties.admin().username()));
    }

    /** 登出。刻意公开：令牌已过期时也应该能把浏览器里的 Cookie 清掉。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        addCookie(response, "", Duration.ZERO);
        return ApiResponse.ok();
    }

    /**
     * 校验用户名和口令。
     *
     * <p>无论用户名是否匹配都执行一次 BCrypt 比对：若用户名不对就直接返回，
     * 响应时间会明显短于口令错误的情况，攻击者可据此判断用户名是否存在。
     */
    private boolean credentialsMatch(LoginRequest req) {
        boolean userOk = MessageDigest.isEqual(
                req.username().getBytes(StandardCharsets.UTF_8),
                properties.admin().username().getBytes(StandardCharsets.UTF_8));
        boolean passOk = passwordEncoder.matches(req.password(), properties.admin().passwordHash());
        return userOk && passOk;
    }

    private void addCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(properties.cookie().name(), value)
                .httpOnly(true)
                .secure(properties.cookie().secure())
                // Strict：跨站导航也不带 Cookie，是 CSRF 的第一道防线（第二道见 SameOriginFilter）
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
