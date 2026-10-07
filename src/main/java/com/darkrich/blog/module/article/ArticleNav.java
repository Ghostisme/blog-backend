package com.darkrich.blog.module.article;

/** 上下篇导航所需的最少信息。 */
public record ArticleNav(Long id, String slug, String title) {

    /** article 为 null（已经是最早/最新一篇）时返回 null。 */
    public static ArticleNav from(Article article) {
        return article == null ? null : new ArticleNav(article.getId(), article.getSlug(), article.getTitle());
    }
}
