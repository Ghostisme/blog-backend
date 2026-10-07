package com.darkrich.blog.module.article;

/** 列表排序方式。用枚举限定可选项，SQL 里按枚举分支写死 ORDER BY，杜绝把用户输入拼进排序子句。 */
public enum ArticleSort {
    /** 按发布时间倒序（默认） */
    LATEST,
    /** 按浏览量倒序 */
    HOT,
    /** 按最近修改倒序（后台用，草稿没有发布时间，按它排才有意义） */
    UPDATED
}
