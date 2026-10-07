package com.darkrich.blog.module.category;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    /**
     * 前台用：文章数只统计已发布。
     * 用 LEFT JOIN 而不是内连接，让暂时没有文章的领域也能返回（计数为 0），由前端决定是否隐藏。
     * 把状态条件放在 ON 里而不是 WHERE 里，否则会把 LEFT JOIN 退化成内连接。
     */
    @Select("""
            SELECT c.id, c.code, c.name_zh, c.name_en, c.icon, c.sort_order, COUNT(a.id) AS article_count
            FROM category c
            LEFT JOIN article a ON a.category_id = c.id AND a.status = 'PUBLISHED'
            GROUP BY c.id
            ORDER BY c.sort_order, c.id
            """)
    List<Category> selectWithPublishedCount();

    /** 后台用：统计所有状态的文章（含草稿），方便判断某领域是否还能删除。 */
    @Select("""
            SELECT c.id, c.code, c.name_zh, c.name_en, c.icon, c.sort_order, COUNT(a.id) AS article_count
            FROM category c
            LEFT JOIN article a ON a.category_id = c.id
            GROUP BY c.id
            ORDER BY c.sort_order, c.id
            """)
    List<Category> selectWithTotalCount();
}
