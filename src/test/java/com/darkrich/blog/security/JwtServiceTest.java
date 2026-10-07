package com.darkrich.blog.security;

import com.darkrich.blog.config.BlogProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";
    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    private static BlogProperties props(String secret) {
        return new BlogProperties(
                new BlogProperties.Admin("admin", "x"),
                new BlogProperties.Jwt(secret, Duration.ofHours(1)),
                new BlogProperties.Cookie("blog_token", true),
                new BlogProperties.Login(5, Duration.ofMinutes(15)));
    }

    private static JwtService serviceAt(Instant now) {
        return new JwtService(props(SECRET), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issuedTokenVerifiesToSameUser() {
        JwtService svc = serviceAt(T0);

        assertEquals("admin", svc.verify(svc.issue("admin")).orElseThrow());
    }

    @Test
    void expiredTokenIsRejected() {
        String token = serviceAt(T0).issue("admin");

        assertTrue(serviceAt(T0.plus(Duration.ofHours(2))).verify(token).isEmpty());
    }

    @Test
    void tokenStillValidJustBeforeExpiry() {
        String token = serviceAt(T0).issue("admin");

        assertTrue(serviceAt(T0.plus(Duration.ofMinutes(59))).verify(token).isPresent());
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService svc = serviceAt(T0);
        String token = svc.issue("admin");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertTrue(svc.verify(tampered).isEmpty());
    }

    @Test
    void tokenSignedWithOtherSecretIsRejected() {
        String foreign = new JwtService(props("another-secret-another-secret-0123456789ab"), Clock.fixed(T0, ZoneOffset.UTC))
                .issue("admin");

        assertTrue(serviceAt(T0).verify(foreign).isEmpty());
    }

    @Test
    void garbageIsRejectedWithoutThrowing() {
        JwtService svc = serviceAt(T0);

        assertTrue(svc.verify("not-a-jwt").isEmpty());
        assertTrue(svc.verify("").isEmpty());
    }

    @Test
    void ttlSecondsMatchesConfig() {
        assertEquals(3600, serviceAt(T0).ttlSeconds());
    }
}
