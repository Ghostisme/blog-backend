package com.darkrich.blog.module.article;

/** Validated translation payload returned by the provider. */
public record ArticleTranslationResult(String title, String summary, String content) {
}
