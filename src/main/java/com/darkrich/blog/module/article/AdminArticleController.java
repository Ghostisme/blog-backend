package com.darkrich.blog.module.article;

import com.darkrich.blog.common.ApiResponse;
import com.darkrich.blog.common.PageResult;
import com.darkrich.blog.module.importer.ImportResult;
import com.darkrich.blog.module.importer.ImportService;
import com.darkrich.blog.module.importer.ImportUrlsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** 后台：文章管理与 Markdown 导入。鉴权由 SecurityConfig 对 /api/admin/** 统一要求。 */
@RestController
@RequestMapping("/api/admin/articles")
@RequiredArgsConstructor
public class AdminArticleController {

    private final AdminArticleService service;
    private final ImportService importService;
    private final ArticleTranslationService translationService;

    @GetMapping
    public ApiResponse<PageResult<ArticleListItem>> list(AdminArticleQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<ArticleEditView> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ApiResponse<ArticleEditView> create(@Valid @RequestBody ArticleSaveRequest req) {
        return ApiResponse.ok(service.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<ArticleEditView> update(@PathVariable Long id, @Valid @RequestBody ArticleSaveRequest req) {
        return ApiResponse.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    /** 批量删除：{@code DELETE /api/admin/articles?ids=1,2,3}。 */
    @DeleteMapping
    public ApiResponse<Map<String, Integer>> deleteBatch(@RequestParam List<Long> ids) {
        return ApiResponse.ok(Map.of("deleted", service.deleteBatch(ids)));
    }

    /** 批量修改状态 / 等级 / 领域。 */
    @PatchMapping
    public ApiResponse<Map<String, Integer>> patchBatch(@Valid @RequestBody ArticleBatchRequest req) {
        return ApiResponse.ok(Map.of("updated", service.patchBatch(req)));
    }

    /** 批量导入 Markdown 文件，统一进入草稿。单个文件失败不影响其它文件，逐个返回结果。 */
    @PostMapping("/import")
    public ApiResponse<ImportResult> importFiles(@RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok(importService.importFiles(files));
    }

    /** 从公开链接导入，统一进入草稿。单条失败不影响其它链接。 */
    @PostMapping("/import-urls")
    public ApiResponse<ImportResult> importUrls(@Valid @RequestBody ImportUrlsRequest req) {
        return ApiResponse.ok(importService.importUrls(req.urls()));
    }

    /** Queue missing/outdated English versions for existing articles; safe to repeat. */
    @PostMapping("/translations/backfill")
    public ApiResponse<TranslationBatchResult> backfillTranslations() {
        return ApiResponse.ok(translationService.backfill());
    }
}
