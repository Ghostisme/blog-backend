package com.darkrich.blog.module.article;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台编辑用的文章全量视图。
 *
 * <p>与详情不同：分类只给 id（编辑表单用下拉选择）、标签只给名称（表单里是可输入的标签框），
 * 并且包含状态等前台不需要的字段。
 */
public record ArticleEditView(Long id, String slug, String title, String summary, String content,
                              ArticleLevel level, Long categoryId, ArticleStatus status, List<String> tags,
                              String sourceUrl, String sourceAuthor, String coverUrl,
                              long viewCount, LocalDateTime publishedAt,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
}
