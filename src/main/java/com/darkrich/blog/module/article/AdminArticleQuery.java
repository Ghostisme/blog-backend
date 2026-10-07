package com.darkrich.blog.module.article;

import com.darkrich.blog.common.TextUtil;

/**
 * 后台列表查询参数。与前台相比多了 status 筛选（可看草稿），默认按最近修改排序，
 * 这样刚导入/刚编辑的文章总在最上面。
 */
public record AdminArticleQuery(Integer page, Integer size, ArticleStatus status, ArticleLevel level,
                                Long categoryId, String keyword, ArticleSort sort) {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 100;

    public AdminArticleQuery {
        page = (page == null || page < 1) ? 1 : page;
        size = (size == null || size < 1) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        keyword = TextUtil.truncate(TextUtil.blankToNull(keyword), MAX_KEYWORD_LENGTH, "");
        sort = sort == null ? ArticleSort.UPDATED : sort;
    }
}
