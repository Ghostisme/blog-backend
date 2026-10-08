package com.darkrich.blog.module.article;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** Persistence for durable translation jobs. */
@Mapper
public interface ArticleTranslationMapper extends BaseMapper<ArticleTranslationJob> {

    @Select("""
            SELECT * FROM article_translation_job
            WHERE status = 'PENDING'
              AND (next_run_at IS NULL OR next_run_at <= #{now})
            ORDER BY id ASC
            LIMIT 1
            """)
    ArticleTranslationJob findReady(@Param("now") LocalDateTime now);

    /** Claim only a pending row; a second worker will not process the same job. */
    @Update("""
            UPDATE article_translation_job
            SET status = 'PROCESSING', attempts = attempts + 1, locked_at = NOW()
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int claim(@Param("id") Long id);

    @Update("""
            UPDATE article_translation_job
            SET status = 'PENDING', locked_at = NULL
            WHERE status = 'PROCESSING' AND locked_at < DATE_SUB(NOW(), INTERVAL 15 MINUTE)
            """)
    int requeueStale();
}
