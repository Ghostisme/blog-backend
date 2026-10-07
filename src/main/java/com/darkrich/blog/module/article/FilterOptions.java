package com.darkrich.blog.module.article;

import com.darkrich.blog.module.category.CategoryView;
import com.darkrich.blog.module.tag.TagView;

import java.util.List;

/**
 * 前台筛选栏所需的全部选项，一次请求返回，省去前端并发请求三个接口。
 * 所有计数都只统计已发布文章。
 */
public record FilterOptions(List<LevelOption> levels, List<CategoryView> categories, List<TagView> tags) {

    /** 等级及其文章数；即使为 0 也会返回，保证前端四个等级入口始终齐全、顺序固定。 */
    public record LevelOption(ArticleLevel level, long count) {
    }
}
