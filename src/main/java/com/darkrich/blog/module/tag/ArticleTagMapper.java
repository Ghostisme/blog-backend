package com.darkrich.blog.module.tag;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;

/**
 * 文章-标签关联表。联合主键没有单列 id，不适合继承 BaseMapper，所以只手写两个操作。
 * 放在 tag 模块：关联关系由 {@link TagService#replaceArticleTags} 统一维护，文章模块不直接碰这张表。
 */
@Mapper
public interface ArticleTagMapper {

    @Delete("DELETE FROM article_tag WHERE article_id = #{articleId}")
    void deleteByArticleId(@Param("articleId") Long articleId);

    @Insert("""
            <script>
            INSERT INTO article_tag (article_id, tag_id) VALUES
            <foreach collection="tagIds" item="tagId" separator=",">(#{articleId}, #{tagId})</foreach>
            </script>
            """)
    void insertBatch(@Param("articleId") Long articleId, @Param("tagIds") Collection<Long> tagIds);
}
