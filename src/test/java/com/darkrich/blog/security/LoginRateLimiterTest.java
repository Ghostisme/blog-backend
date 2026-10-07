package com.darkrich.blog.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {

    /** 可手动拨动的时钟，让窗口过期的测试不必真的等待。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2025-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(15), clock);

    @Test
    void blocksOnlyAfterReachingMaxFailures() {
        limiter.recordFailure("1.1.1.1");
        limiter.recordFailure("1.1.1.1");
        assertFalse(limiter.isBlocked("1.1.1.1"));

        limiter.recordFailure("1.1.1.1");
        assertTrue(limiter.isBlocked("1.1.1.1"));
    }

    @Test
    void ipsAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("1.1.1.1");
        }

        assertTrue(limiter.isBlocked("1.1.1.1"));
        assertFalse(limiter.isBlocked("2.2.2.2"));
    }

    @Test
    void lockExpiresAfterWindow() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("1.1.1.1");
        }
        clock.advance(Duration.ofMinutes(16));

        assertFalse(limiter.isBlocked("1.1.1.1"));
    }

    @Test
    void staleFailuresDoNotAccumulateAcrossWindows() {
        limiter.recordFailure("1.1.1.1");
        limiter.recordFailure("1.1.1.1");
        clock.advance(Duration.ofMinutes(16));
        limiter.recordFailure("1.1.1.1");

        assertFalse(limiter.isBlocked("1.1.1.1"), "窗口过期后应从 1 重新计数，而不是累加旧失败");
    }

    @Test
    void successfulLoginResetsCounter() {
        limiter.recordFailure("1.1.1.1");
        limiter.recordFailure("1.1.1.1");
        limiter.reset("1.1.1.1");
        limiter.recordFailure("1.1.1.1");

        assertFalse(limiter.isBlocked("1.1.1.1"));
    }
}
