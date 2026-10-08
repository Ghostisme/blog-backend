package com.darkrich.blog.module.article;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章数据访问。复杂的多条件 + 全文检索查询写在 {@code mapper/ArticleMapper.xml}，
 * 简单的单条语句直接用注解，二者共用同一个命名空间。
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    /**
     * 多条件分页查询。返回的实体<b>不含 content</b>：正文是 LONGTEXT，列表页根本用不到，
     * 读出来既浪费带宽又会拖慢查询。需要正文时用 {@code selectById}。
     * 方法名不叫 selectPage，避免与 BaseMapper 的同名方法在 MyBatis 语句 id 上冲突。
     */
    IPage<Article> searchPage(IPage<Article> page, @Param("f") ArticleFilter filter);

    /** 原子自增浏览量。直接在 SQL 里 +1，而不是读出来再写回，避免并发访问时丢失计数。 */
    @Update("UPDATE article SET view_count = view_count + 1 WHERE id = #{id}")
    int incrementViewCount(@Param("id") Long id);

    /** Update generated English fields only if the original Chinese version is still the one translated. */
    @Update("""
            UPDATE article
            SET title_en = #{titleEn}, summary_en = #{summaryEn}, content_en = #{contentEn},
                translation_status = 'COMPLETED', translation_source_hash = #{sourceHash},
                translation_error = NULL, translated_at = NOW()
            WHERE id = #{id}
              AND translation_locked = 0
            AND SHA2(CONCAT(COALESCE(title, ''), CHAR(10), COALESCE(summary, ''), CHAR(10), COALESCE(content, '')), 256)
                    = #{sourceHash}
            """)
    int saveEnglishIfSourceMatches(@Param("id") Long id, @Param("sourceHash") String sourceHash,
                                   @Param("titleEn") String titleEn, @Param("summaryEn") String summaryEn,
                                   @Param("contentEn") String contentEn);

    /**
     * 比当前文章更早发布的最近一篇。
     * 以 (published_at, id) 二元组比较，而不是只比时间：批量发布时多篇文章的发布时间可能相同，
     * 只比时间会让上下篇导航漏文章或死循环。
     */
    @Select("""
            SELECT id, slug,
                   CASE WHEN #{english} = TRUE AND translation_status = 'COMPLETED'
                              AND title_en IS NOT NULL AND title_en <> ''
                              AND content_en IS NOT NULL AND content_en <> ''
                        THEN title_en ELSE title END AS title
            FROM article
            WHERE status = 'PUBLISHED'
              AND (published_at < #{publishedAt} OR (published_at = #{publishedAt} AND id < #{id}))
            ORDER BY published_at DESC, id DESC
            LIMIT 1
            """)
    Article selectOlder(@Param("id") Long id, @Param("publishedAt") LocalDateTime publishedAt,
                        @Param("english") boolean english);

    /** 比当前文章更晚发布的最近一篇，比较规则同 {@link #selectOlder}。 */
    @Select("""
            SELECT id, slug,
                   CASE WHEN #{english} = TRUE AND translation_status = 'COMPLETED'
                              AND title_en IS NOT NULL AND title_en <> ''
                              AND content_en IS NOT NULL AND content_en <> ''
                        THEN title_en ELSE title END AS title
            FROM article
            WHERE status = 'PUBLISHED'
              AND (published_at > #{publishedAt} OR (published_at = #{publishedAt} AND id > #{id}))
            ORDER BY published_at ASC, id ASC
            LIMIT 1
            """)
    Article selectNewer(@Param("id") Long id, @Param("publishedAt") LocalDateTime publishedAt,
                        @Param("english") boolean english);

    @Select("SELECT level, COUNT(*) AS count FROM article WHERE status = 'PUBLISHED' GROUP BY level")
    List<LevelCount> countPublishedByLevel();
}
