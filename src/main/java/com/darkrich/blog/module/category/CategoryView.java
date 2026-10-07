package com.darkrich.blog.module.category;

/** 带文章数的领域信息，用于筛选栏（前台）和领域管理（后台）。 */
public record CategoryView(Long id, String code, String nameZh, String nameEn, String icon,
                           int sortOrder, long articleCount) {

    public static CategoryView from(Category c) {
        return new CategoryView(c.getId(), c.getCode(), c.getNameZh(), c.getNameEn(), c.getIcon(),
                c.getSortOrder() == null ? 0 : c.getSortOrder(),
                c.getArticleCount() == null ? 0 : c.getArticleCount());
    }
}
