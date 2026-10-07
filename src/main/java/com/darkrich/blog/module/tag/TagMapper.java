package com.darkrich.blog.module.tag;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    /** 前台标签云：只含至少有一篇已发布文章的标签，按热度排序，限制数量避免标签云过长。 */
    @Select("""
            SELECT t.id, t.slug, t.name_zh, t.name_en, COUNT(a.id) AS article_count
            FROM tag t
            JOIN article_tag atg ON atg.tag_id = t.id
            JOIN article a ON a.id = atg.article_id AND a.status = 'PUBLISHED'
            GROUP BY t.id
            ORDER BY article_count DESC, t.id
            LIMIT #{limit}
            """)
    List<Tag> selectPublishedWithCount(@Param("limit") int limit);

    /** 后台标签管理：全部标签，文章数含草稿。 */
    @Select("""
            SELECT t.id, t.slug, t.name_zh, t.name_en, COUNT(atg.article_id) AS article_count
            FROM tag t
            LEFT JOIN article_tag atg ON atg.tag_id = t.id
            GROUP BY t.id
            ORDER BY article_count DESC, t.id
            """)
    List<Tag> selectAllWithCount();

    /**
     * 批量查询一组文章各自的标签：一次 JOIN 代替 N 次查询（避免列表页的 N+1）。
     * 每行带 article_id，调用方据此分组。
     */
    @Select("""
            <script>
            SELECT t.id, t.slug, t.name_zh, t.name_en, atg.article_id
            FROM article_tag atg
            JOIN tag t ON t.id = atg.tag_id
            WHERE atg.article_id IN
            <foreach collection="articleIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY t.id
            </script>
            """)
    List<Tag> selectByArticleIds(@Param("articleIds") Collection<Long> articleIds);
}
