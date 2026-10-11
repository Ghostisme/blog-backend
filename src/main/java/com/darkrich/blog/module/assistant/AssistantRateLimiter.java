package com.darkrich.blog.module.assistant;

import com.darkrich.blog.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class AssistantRateLimiter {
    private record Window(Instant until, int count) {}
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AssistantProperties properties;

    public void acquire(String ip) {
        Instant now = Instant.now();
        if (windows.size() >= 10_000) {
            windows.entrySet().removeIf(e -> e.getValue().until().isBefore(now));
            if (!windows.containsKey(ip) && windows.size() >= 10_000) {
                throw BusinessException.tooManyRequests("小R正在忙，请稍后再试");
            }
        }
        windows.compute(ip, (key, previous) -> {
            Window w = previous == null || previous.until().isBefore(now)
                    ? new Window(now.plusSeconds(600), 0) : previous;
            if (w.count() >= properties.requestsPerWindow()) {
                throw BusinessException.tooManyRequests("对话次数较多，请稍后再与小R聊天");
            }
            return new Window(w.until(), w.count() + 1);
        });
    }
}
