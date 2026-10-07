package com.darkrich.blog.module.resume;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ResumeMapper extends BaseMapper<Resume> {

    /**
     * 存在则覆盖，不存在则新建。用单条 upsert 而不是“先查再决定 insert/update”，
     * 省掉一次往返，也不存在两步之间被别的请求插队的竞态。
     */
    @Insert("""
            INSERT INTO resume (lang, content) VALUES (#{lang}, #{content})
            ON DUPLICATE KEY UPDATE content = VALUES(content)
            """)
    int upsert(@Param("lang") String lang, @Param("content") String content);
}
