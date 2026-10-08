package com.darkrich.blog.module.article;

import com.darkrich.blog.config.BlogProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Polls the database-backed queue; jobs survive restarts and are safe across multiple app instances. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleTranslationWorker {

    private final BlogProperties properties;
    private final ArticleTranslationMapper jobMapper;
    private final ArticleTranslationService service;

    @Scheduled(fixedDelayString = "${blog.translation.poll-delay-ms:10000}")
    public void poll() {
        BlogProperties.Translation config = properties.translation();
        if (!config.enabled() || config.apiKey() == null || config.apiKey().isBlank()) {
            return;
        }
        jobMapper.requeueStale();
        for (int i = 0; i < config.batchSize(); i++) {
            ArticleTranslationJob job = jobMapper.findReady(LocalDateTime.now());
            if (job == null) {
                return;
            }
            service.process(job);
        }
    }
}
