package com.darkrich.blog.module.article;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.config.BlogProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Enqueue, backfill, and run durable English translation work. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleTranslationService {

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_ERROR_LENGTH = 2000;

    private final ArticleMapper articleMapper;
    private final ArticleTranslationMapper jobMapper;
    private final ArticleTranslationProvider provider;
    private final BlogProperties properties;

    /** Queue the current Chinese content version; repeated calls are idempotent. */
    @Transactional
    public boolean enqueueFor(Long articleId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            return false;
        }
        if (Boolean.TRUE.equals(article.getTranslationLocked())) {
            return false;
        }
        String hash = ArticleTranslationHash.of(article);
        if (article.getTranslationStatus() == TranslationStatus.COMPLETED
                && hash.equals(article.getTranslationSourceHash())
                && hasText(article.getTitleEn()) && hasText(article.getContentEn())) {
            return false;
        }

        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .set(Article::getTranslationStatus, TranslationStatus.PENDING)
                .set(Article::getTranslationSourceHash, hash)
                .set(Article::getTranslationError, null));

        ArticleTranslationJob job = jobMapper.selectOne(new LambdaQueryWrapper<ArticleTranslationJob>()
                .eq(ArticleTranslationJob::getArticleId, articleId)
                .eq(ArticleTranslationJob::getTargetLanguage, "en")
                .eq(ArticleTranslationJob::getSourceHash, hash));
        if (job != null) {
            if (job.getStatus() == TranslationStatus.COMPLETED
                    || job.getStatus() == TranslationStatus.PROCESSING
                    || job.getStatus() == TranslationStatus.PENDING) {
                return false;
            }
            job.setStatus(TranslationStatus.PENDING);
            job.setAttempts(0);
            job.setLastError(null);
            job.setNextRunAt(null);
            job.setLockedAt(null);
            jobMapper.updateById(job);
            return true;
        }

        ArticleTranslationJob created = new ArticleTranslationJob();
        created.setArticleId(articleId);
        created.setSourceLanguage("zh");
        created.setTargetLanguage("en");
        created.setSourceHash(hash);
        created.setStatus(TranslationStatus.PENDING);
        created.setAttempts(0);
        try {
            jobMapper.insert(created);
            return true;
        } catch (DuplicateKeyException concurrentEnqueue) {
            // Another request inserted the same article/version job concurrently.
            log.debug("Translation job already queued for article {}", articleId);
            articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, articleId)
                    .set(Article::getTranslationStatus, TranslationStatus.PENDING));
            return false;
        }
    }

    /** Reconcile existing articles. The source columns stay untouched. */
    @Transactional
    public TranslationBatchResult backfill() {
        requireTranslationService();
        List<Long> ids = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .select(Article::getId).orderByAsc(Article::getId))
                .stream().map(Article::getId).toList();
        int queued = 0;
        int locked = 0;
        int alreadyTranslated = 0;
        for (Long id : ids) {
            Article article = articleMapper.selectById(id);
            if (article == null) {
                continue;
            }
            if (Boolean.TRUE.equals(article.getTranslationLocked())) {
                locked++;
                continue;
            }
            String hash = ArticleTranslationHash.of(article);
            if (article.getTranslationStatus() == TranslationStatus.COMPLETED
                    && hash.equals(article.getTranslationSourceHash())
                    && hasText(article.getTitleEn()) && hasText(article.getContentEn())) {
                alreadyTranslated++;
            } else if (enqueueFor(id)) {
                queued++;
            }
        }
        return new TranslationBatchResult(ids.size(), queued, alreadyTranslated, locked);
    }

    /**
     * Do not report a successful queue operation when no worker can consume it.
     * New article saves still enqueue while the service is disabled, so an
     * operator can enable translation later and run this reconciliation again.
     */
    private void requireTranslationService() {
        BlogProperties.Translation config = properties.translation();
        if (!config.enabled()) {
            throw BusinessException.badRequest("翻译服务未启用，请在部署参数中设置 BLOG_TRANSLATION_ENABLED=true");
        }
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw BusinessException.badRequest("翻译服务缺少 API Key，请先配置 BLOG_TRANSLATION_API_KEY");
        }
    }

    /** Process a single claimed job. A failed item is retried with exponential backoff up to three times. */
    public void process(ArticleTranslationJob job) {
        if (jobMapper.claim(job.getId()) == 0) {
            return;
        }
        Article current = articleMapper.selectById(job.getArticleId());
        if (current == null) {
            finishJob(job.getId(), TranslationStatus.SKIPPED, "文章不存在", null);
            return;
        }
        if (Boolean.TRUE.equals(current.getTranslationLocked())) {
            finishJob(job.getId(), TranslationStatus.SKIPPED, "人工锁定，未覆盖", null);
            return;
        }
        String currentHash = ArticleTranslationHash.of(current);
        if (!currentHash.equals(job.getSourceHash())) {
            finishJob(job.getId(), TranslationStatus.SKIPPED, "原文已更新", null);
            enqueueFor(current.getId());
            return;
        }

        try {
            ArticleTranslationResult translated = provider.translate(
                    current, ArticleLanguage.ZH, ArticleLanguage.EN);
            int updated = articleMapper.saveEnglishIfSourceMatches(current.getId(), currentHash,
                    translated.title(), translated.summary(), translated.content());
            if (updated == 0) {
                finishJob(job.getId(), TranslationStatus.SKIPPED, "原文翻译期间已更新或人工锁定", null);
                enqueueFor(current.getId());
                return;
            }
            finishJob(job.getId(), TranslationStatus.COMPLETED, null, null);
        } catch (Exception e) {
            int attempts = job.getAttempts() == null ? 1 : job.getAttempts() + 1;
            String error = safeMessage(e);
            if (attempts < MAX_ATTEMPTS) {
                long delayMinutes = 1L << attempts;
                finishJob(job.getId(), TranslationStatus.PENDING, error,
                        LocalDateTime.now().plusMinutes(delayMinutes));
                articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                        .eq(Article::getId, current.getId())
                        .eq(Article::getTranslationSourceHash, currentHash)
                        .set(Article::getTranslationStatus, TranslationStatus.PENDING)
                        .set(Article::getTranslationError, error));
            } else {
                finishJob(job.getId(), TranslationStatus.FAILED, error, null);
                articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                        .eq(Article::getId, current.getId())
                        .eq(Article::getTranslationSourceHash, currentHash)
                        .set(Article::getTranslationStatus, TranslationStatus.FAILED)
                        .set(Article::getTranslationError, error));
            }
            log.warn("Article translation attempt failed, articleId={}, attempt={}", current.getId(), attempts, e);
        }
    }

    private void finishJob(Long id, TranslationStatus status, String error, LocalDateTime nextRunAt) {
        ArticleTranslationJob job = new ArticleTranslationJob();
        job.setId(id);
        job.setStatus(status);
        job.setLastError(error);
        job.setNextRunAt(nextRunAt);
        job.setLockedAt(null);
        jobMapper.updateById(job);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
