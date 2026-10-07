package com.darkrich.blog.module.article;

/** 文章状态。前台只展示 {@link #PUBLISHED}；导入的文章一律先进入 {@link #DRAFT}，由人工校正后再发布。 */
public enum ArticleStatus {
    DRAFT,
    PUBLISHED
}
