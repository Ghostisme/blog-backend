package com.darkrich.blog.module.article;

/** Provider abstraction so the storage/queue workflow is independent of one vendor. */
public interface ArticleTranslationProvider {

    ArticleTranslationResult translate(Article article, ArticleLanguage source, ArticleLanguage target);
}
