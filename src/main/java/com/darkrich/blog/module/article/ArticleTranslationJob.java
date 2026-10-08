package com.darkrich.blog.module.article;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable, retryable translation work item. */
@Data
@TableName("article_translation_job")
public class ArticleTranslationJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long articleId;
    private String sourceLanguage;
    private String targetLanguage;
    private String sourceHash;
    private TranslationStatus status;
    private Integer attempts;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextRunAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lockedAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
