package com.darkrich.blog.module.resume;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 简历存储行：主键就是语言代码，content 是 {@link ResumeContent} 序列化后的 JSON 文本。 */
@Data
@TableName("resume")
public class Resume {

    /** 手动指定主键（zh / en），不是自增。 */
    @TableId(type = IdType.INPUT)
    private String lang;

    private String content;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
