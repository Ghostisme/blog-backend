package com.darkrich.blog.module.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增 / 修改领域的请求。
 *
 * @param code      英文标识，仅限小写字母、数字、连字符。
 *                  注意：导入时的自动分类（ArticleClassifier）依赖 frontend/backend/database/devops/mobile 这几个编码，
 *                  改动它们会让对应领域失去自动归类
 * @param sortOrder 排序值，越小越靠前；缺省按 0 处理
 */
public record CategoryRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[a-z0-9-]+$", message = "只能包含小写字母、数字和连字符")
        String code,
        @NotBlank @Size(max = 64) String nameZh,
        @NotBlank @Size(max = 64) String nameEn,
        @Size(max = 32) String icon,
        Integer sortOrder) {
}
