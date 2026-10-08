package com.darkrich.blog.module.article;

/** State of the generated English article. */
public enum TranslationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    REVIEW,
    SKIPPED
}
