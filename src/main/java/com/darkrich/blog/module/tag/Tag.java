package com.darkrich.blog.module.tag;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 文章标签。 */
@Data
@TableName("tag")
public class Tag {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 由名称归一化生成，用来让「Vue」「vue」落到同一个标签上。创建后不随改名变化。 */
    private String slug;

    private String nameZh;

    private String nameEn;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    // ---- 以下为统计 / 关联查询的附加列，不对应 tag 表字段 ----

    @TableField(exist = false)
    private Long articleCount;

    /** 批量查询“文章 → 标签”时，标记这个标签属于哪篇文章。 */
    @TableField(exist = false)
    private Long articleId;
}
