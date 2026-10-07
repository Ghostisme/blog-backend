package com.darkrich.blog.module.category;

/**
 * 嵌入文章列表 / 详情的领域简要信息。中英文名称都带上，由前端按当前语言选择，
 * 这样切换语言不需要重新请求数据。
 */
public record CategoryBrief(Long id, String code, String nameZh, String nameEn, String icon) {

    public static CategoryBrief from(Category c) {
        return new CategoryBrief(c.getId(), c.getCode(), c.getNameZh(), c.getNameEn(), c.getIcon());
    }
}
