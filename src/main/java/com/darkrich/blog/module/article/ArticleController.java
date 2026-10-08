package com.darkrich.blog.module.article;

import com.darkrich.blog.common.ApiResponse;
import com.darkrich.blog.common.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 前台公开接口：文章列表、详情、筛选项。无需登录。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleQueryService service;

    @GetMapping("/articles")
    public ApiResponse<PageResult<ArticleListItem>> list(ArticleQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    @GetMapping("/articles/{slug}")
    public ApiResponse<ArticleDetail> detail(@PathVariable String slug,
                                             @RequestParam(defaultValue = "zh") String lang) {
        return ApiResponse.ok(service.detail(slug, ArticleLanguage.from(lang)));
    }

    /** 筛选栏数据（等级 / 领域 / 标签及各自文章数）。 */
    @GetMapping("/filters")
    public ApiResponse<FilterOptions> filters() {
        return ApiResponse.ok(service.filters());
    }
}
