package com.darkrich.blog.module.tag;

/** 带文章数的标签，用于前台标签云与后台标签管理。 */
public record TagView(Long id, String slug, String nameZh, String nameEn, long articleCount) {

    public static TagView from(Tag t) {
        return new TagView(t.getId(), t.getSlug(), t.getNameZh(), t.getNameEn(),
                t.getArticleCount() == null ? 0 : t.getArticleCount());
    }
}
