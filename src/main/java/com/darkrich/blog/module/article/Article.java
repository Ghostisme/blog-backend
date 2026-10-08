package com.darkrich.blog.module.article;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章实体。
 *
 * <p>更新策略说明（MyBatis-Plus 默认会跳过值为 null 的字段，这里需要逐个明确）：
 * <ul>
 *   <li>可为空且允许“清空”的字段（摘要、来源、封面、分类、发布时间）用 {@code ALWAYS}，
 *       否则后台把它们清空时数据库里的旧值不会被覆盖；</li>
 *   <li>{@code viewCount} 用 {@code NEVER}：后台编辑是“先读后写”，若把读到的浏览量写回，
 *       会覆盖掉编辑期间读者产生的新增浏览；浏览量只通过专门的原子自增语句修改；</li>
 *   <li>{@code createdAt / updatedAt} 用 {@code NEVER}：完全交给数据库默认值与 ON UPDATE 维护。</li>
 * </ul>
 */
@Data
@TableName("article")
public class Article {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String slug;

    private String title;

    /** Automatically generated English title; the existing title remains the source Chinese title. */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String titleEn;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String summary;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String summaryEn;

    private String content;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String contentEn;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private TranslationStatus translationStatus;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String translationSourceHash;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String translationError;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime translatedAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Boolean translationLocked;

    /** Lightweight projection for list queries; never loads the English LONGTEXT body. */
    @TableField(exist = false)
    private Boolean hasEnglishTranslation;

    private ArticleLevel level;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long categoryId;

    private ArticleStatus status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceUrl;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceAuthor;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String coverUrl;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long viewCount;

    private Integer wordCount;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime publishedAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
