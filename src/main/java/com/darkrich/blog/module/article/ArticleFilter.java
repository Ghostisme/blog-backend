package com.darkrich.blog.module.article;

import java.util.List;

/**
 * 传给 Mapper 的查询条件（已归一化）。
 *
 * <p>和 Controller 层的查询参数分开，是为了让 SQL 层只看到“可以直接用”的值：
 * 关键词已被拆成全文检索表达式和 LIKE 模式，排序是枚举而非任意字符串，
 * 因此 XML 里没有任何 {@code ${}} 拼接，不存在排序列注入的可能。
 *
 * @param status      为 null 表示不限状态（仅后台使用；前台服务层会强制为 PUBLISHED）
 * @param ftsExpr     MySQL 布尔全文检索表达式；为 null 表示没有可走全文索引的关键词
 * @param likePatterns 不适合全文检索的短词（如单个字符、"c++"）各自转成的 LIKE 模式，彼此 AND
 */
public record ArticleFilter(ArticleStatus status, ArticleLevel level, Long categoryId, Long tagId,
                            String ftsExpr, List<String> likePatterns, ArticleSort sort) {
}
