package com.darkrich.blog.module.tag;

/** 嵌入文章列表 / 详情的标签简要信息，中英文名都带上，由前端按语言选择。 */
public record TagBrief(Long id, String slug, String nameZh, String nameEn) {

    public static TagBrief from(Tag t) {
        return new TagBrief(t.getId(), t.getSlug(), t.getNameZh(), t.getNameEn());
    }
}
