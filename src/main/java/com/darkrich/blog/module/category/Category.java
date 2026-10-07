package com.darkrich.blog.module.category;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 文章领域（前端 / 后端 / 数据库 …）。 */
@Data
@TableName("category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 稳定的英文标识。导入分类建议（ArticleClassifier）按它匹配，修改时需同步。 */
    private String code;

    private String nameZh;

    private String nameEn;

    private String icon;

    private Integer sortOrder;

    /** 由数据库默认值填充，应用层不写。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 统计查询的附加列，不对应表字段。 */
    @TableField(exist = false)
    private Long articleCount;
}
