package com.darkrich.blog.module.article;

import com.darkrich.blog.module.category.CategoryBrief;
import com.darkrich.blog.module.tag.TagBrief;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 前台文章详情。
 *
 * <p>导航字段叫 older / newer 而不是 prev / next：中文站点里“上一篇”通常指更早的一篇，
 * 英文站点常相反，由前端按语言决定文案与位置，后端只陈述“时间上更早/更晚”这个无歧义的事实。
 *
 * @param sourceUrl    转载来源链接；非转载文章为 null
 * @param sourceAuthor 原作者；非转载文章为 null
 * @param older        时间上更早的一篇，没有则为 null
 * @param newer        时间上更晚的一篇，没有则为 null
 */
public record ArticleDetail(Long id, String slug, String title, String summary, String content,
                            ArticleLevel level, CategoryBrief category, List<TagBrief> tags,
                            String coverUrl, String sourceUrl, String sourceAuthor,
                            long viewCount, int wordCount, int readingMinutes,
                            LocalDateTime publishedAt, LocalDateTime updatedAt,
                            ArticleNav older, ArticleNav newer) {
}
