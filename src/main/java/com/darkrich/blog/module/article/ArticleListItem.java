package com.darkrich.blog.module.article;

import com.darkrich.blog.module.category.CategoryBrief;
import com.darkrich.blog.module.tag.TagBrief;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章列表项（不含正文）。前台列表和后台列表共用：
 * status / updatedAt 对前台无害（前台只会看到已发布文章），共用可以少维护一套几乎相同的结构。
 *
 * @param readingMinutes 预估阅读分钟数，由保存时计算好的字数推出，至少为 1
 */
public record ArticleListItem(Long id, String slug, String title, String summary, ArticleLevel level,
                              CategoryBrief category, List<TagBrief> tags, String coverUrl,
                              long viewCount, int readingMinutes, ArticleStatus status,
                              LocalDateTime publishedAt, LocalDateTime updatedAt,
                              String contentLanguage, TranslationStatus translationStatus) {
}
