package com.darkrich.blog.module.article;

/** Summary for an administrator-triggered idempotent backfill. */
public record TranslationBatchResult(int scanned, int queued, int alreadyTranslated, int locked) {
}
