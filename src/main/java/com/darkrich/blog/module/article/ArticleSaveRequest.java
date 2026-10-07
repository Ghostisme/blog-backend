package com.darkrich.blog.module.article;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 新增 / 修改文章的请求体。
 *
 * <p>来源链接和封面只接受 http(s)：这些值最终会渲染成前端的 {@code <a href>} / {@code <img src>}，
 * 放行 {@code javascript:} 之类的协议就是一个 XSS 入口，所以在入口处限定协议。
 *
 * @param slug        可选。新增时为空则按标题自动生成；修改时为空表示保持原 slug 不变
 * @param level       为空按“进阶”处理
 * @param categoryId  草稿可为空，但发布时必须有
 * @param status      为空按草稿处理
 * @param tags        标签名列表，不存在的标签会自动创建；最多 10 个
 * @param publishedAt 可选。发布时为空则取当前时间，用于手动指定/修正发布时间
 */
public record ArticleSaveRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 160)
        @Pattern(regexp = "^([a-z0-9]+(-[a-z0-9]+)*)?$", message = "只能包含小写字母、数字和连字符")
        String slug,
        @Size(max = 500) String summary,
        @NotBlank String content,
        ArticleLevel level,
        Long categoryId,
        ArticleStatus status,
        @Size(max = 10) List<@NotBlank @Size(max = 64) String> tags,
        @Size(max = 512) @Pattern(regexp = "^(https?://\\S+)?$", message = "必须是 http(s) 链接") String sourceUrl,
        @Size(max = 128) String sourceAuthor,
        @Size(max = 512) @Pattern(regexp = "^(https?://\\S+)?$", message = "必须是 http(s) 链接") String coverUrl,
        LocalDateTime publishedAt) {
}
