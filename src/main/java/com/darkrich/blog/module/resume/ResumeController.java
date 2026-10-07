package com.darkrich.blog.module.resume;

import com.darkrich.blog.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 简历接口：公开读取 + 后台保存。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService service;

    /** 公开：读取指定语言简历，{@code GET /api/resume?lang=zh|en}，缺省中文。 */
    @GetMapping("/resume")
    public ApiResponse<ResumeContent> get(@RequestParam(defaultValue = "zh") String lang) {
        return ApiResponse.ok(service.get(lang));
    }

    /** 后台：整体覆盖某一语言的简历。路径在 /api/admin 下，由安全链统一要求登录。 */
    @PutMapping("/admin/resume/{lang}")
    public ApiResponse<ResumeContent> save(@PathVariable String lang, @Valid @RequestBody ResumeContent content) {
        return ApiResponse.ok(service.save(lang, content));
    }
}
