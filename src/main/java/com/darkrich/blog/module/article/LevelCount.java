package com.darkrich.blog.module.article;

import lombok.Data;

/** 「等级 → 已发布文章数」的统计行，仅用于接收聚合查询结果。 */
@Data
public class LevelCount {
    private ArticleLevel level;
    private long count;
}
