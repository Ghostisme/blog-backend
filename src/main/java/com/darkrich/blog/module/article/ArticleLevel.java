package com.darkrich.blog.module.article;

/**
 * 文章难度等级。数据库和接口里存/传枚举名（如 {@code ADVANCED}），展示文案由前端 i18n 负责，
 * 这样后端不绑定任何一种语言。
 */
public enum ArticleLevel {
    /** 入门 */
    BEGINNER,
    /** 进阶 */
    INTERMEDIATE,
    /** 高级 */
    ADVANCED,
    /** 资深 */
    EXPERT
}
