package com.darkrich.blog.module.article;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量修改请求：对选中的多篇文章统一设置状态 / 等级 / 领域。
 * status、level、categoryId 为 null 表示“不修改该项”，至少要给一项。
 * 主要用于导入后的集中校正——导入的几十篇草稿按领域、等级批量归类再统一发布。
 */
public record ArticleBatchRequest(@NotEmpty @Size(max = 200) List<Long> ids,
                                  ArticleStatus status, ArticleLevel level, Long categoryId) {
}
