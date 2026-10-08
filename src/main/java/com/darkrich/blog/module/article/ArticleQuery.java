package com.darkrich.blog.module.article;

import com.darkrich.blog.common.TextUtil;

/**
 * 前台列表查询参数（query string 直接绑定）。
 *
 * <p>不依赖 Bean Validation，而是在紧凑构造器里把非法值“夹”回合法范围：
 * 对公开的只读接口来说，size=9999 或 page=-1 没必要报错，按上限/下限处理对调用方更友好。
 */
public record ArticleQuery(Integer page, Integer size, ArticleLevel level, Long categoryId, Long tagId,
                           String keyword, ArticleSort sort, String lang) {

    private static final int DEFAULT_SIZE = 12;
    private static final int MAX_SIZE = 50;
    private static final int MAX_KEYWORD_LENGTH = 100;

    public ArticleQuery {
        page = (page == null || page < 1) ? 1 : page;
        size = (size == null || size < 1) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        keyword = TextUtil.truncate(TextUtil.blankToNull(keyword), MAX_KEYWORD_LENGTH, "");
        // UPDATED 仅后台有意义（按修改时间排），前台不开放
        sort = (sort == null || sort == ArticleSort.UPDATED) ? ArticleSort.LATEST : sort;
        lang = ArticleLanguage.from(lang).code();
    }
}
