package com.darkrich.blog.module.importer;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 从公开链接导入。条数上限压在 10：每条都要出网，太多会拖垮构建节点和目标站。
 */
public record ImportUrlsRequest(
        @NotEmpty(message = "请填写文章链接")
        @Size(max = 10, message = "单次最多 10 条链接")
        List<@Size(max = 2048, message = "链接过长") String> urls) {
}
