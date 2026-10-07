package com.darkrich.blog.security;

import com.darkrich.blog.config.BlogProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 的签发与校验（HS256）。
 *
 * <p>令牌里只放用户名，不放角色：本系统只有唯一管理员，权限由过滤器统一授予，
 * 没必要把权限信息放进客户端可见的载荷。
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final BlogProperties.Jwt props;
    private final Clock clock;

    // 存在两个构造器时 Spring 不会自动挑选，必须显式标注注入入口
    @Autowired
    public JwtService(BlogProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 可注入时钟，便于单测验证过期逻辑而不必真的等待。 */
    JwtService(BlogProperties properties, Clock clock) {
        this.props = properties.jwt();
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    /** 为指定用户签发令牌，有效期取配置。 */
    public String issue(String username) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(Date.from(clock.instant().plus(props.ttl())))
                .signWith(key)
                .compact();
    }

    /**
     * 校验令牌并返回用户名。
     *
     * @return 签名正确且未过期时返回用户名；任何异常（篡改、过期、格式错误）一律返回空，
     * 调用方无需区分原因——对外都是“未登录”。
     */
    public Optional<String> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.ofNullable(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 令牌有效期（秒），用于设置 Cookie 的 Max-Age，使两者保持一致。 */
    public long ttlSeconds() {
        return props.ttl().toSeconds();
    }
}
