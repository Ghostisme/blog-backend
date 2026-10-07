package com.darkrich.blog.module.tag;

import com.darkrich.blog.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台：标签管理。不提供“新增”接口——标签随文章保存自动创建，
 * 这里只负责改显示名和清理无用标签。
 */
@RestController
@RequestMapping("/api/admin/tags")
@RequiredArgsConstructor
public class AdminTagController {

    private final TagService service;

    @GetMapping
    public ApiResponse<List<TagView>> list() {
        return ApiResponse.ok(service.listAdmin());
    }

    @PutMapping("/{id}")
    public ApiResponse<TagView> update(@PathVariable Long id, @Valid @RequestBody TagRequest req) {
        return ApiResponse.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
