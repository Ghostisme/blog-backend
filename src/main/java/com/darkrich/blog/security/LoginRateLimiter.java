package com.darkrich.blog.security;

import com.darkrich.blog.config.BlogProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录防爆破：按来源 IP 统计失败次数，窗口内超过上限就锁定该 IP 一个窗口的时间。
 *
 * <p>为什么用内存实现：只有单实例、单管理员，引入 Redis 只为限流得不偿失。
 * 代价是重启后计数清零——对个人博客可以接受。
 *
 * <p>为什么按 IP 而不是按用户名：用户名只有一个，按用户名锁定会让攻击者
 * 随便发几次错误请求就把真正的管理员锁在门外。
 */
@Component
public class LoginRateLimiter {

    /** 单个 IP 的失败记录；windowStart 之后累计 failures 次。 */
    private record Attempt(int failures, Instant windowStart) {
    }

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;

    // 存在两个构造器时 Spring 不会自动挑选，必须显式标注注入入口
    @Autowired
    public LoginRateLimiter(BlogProperties properties) {
        this(properties.login().maxFailures(), properties.login().window(), Clock.systemUTC());
    }

    LoginRateLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    /** 该 IP 当前是否被锁定。 */
    public boolean isBlocked(String ip) {
        Attempt a = attempts.get(ip);
        if (a == null) {
            return false;
        }
        if (expired(a)) {
            attempts.remove(ip, a);
            return false;
        }
        return a.failures() >= maxFailures;
    }

    /** 记录一次失败。窗口过期则从 1 重新计数。 */
    public void recordFailure(String ip) {
        Instant now = clock.instant();
        attempts.merge(ip, new Attempt(1, now),
                (old, fresh) -> expired(old) ? fresh : new Attempt(old.failures() + 1, old.windowStart()));
        evictExpiredIfLarge();
    }

    /** 登录成功后清除该 IP 的记录。 */
    public void reset(String ip) {
        attempts.remove(ip);
    }

    private boolean expired(Attempt a) {
        return a.windowStart().plus(window).isBefore(clock.instant());
    }

    /**
     * 兜底清理：没人会专门去“登录成功”来清掉爆破者留下的条目，
     * 不清理的话海量随机 IP 的失败记录会一直占内存。表大到一定规模时顺手扫一遍过期项。
     */
    private void evictExpiredIfLarge() {
        if (attempts.size() > 10_000) {
            attempts.entrySet().removeIf(e -> expired(e.getValue()));
        }
    }
}
