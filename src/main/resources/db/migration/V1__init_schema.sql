-- 博客初始表结构。
-- 约定：
--   * 全部使用 utf8mb4，文章里会有 emoji / 生僻字。
--   * 主键用有符号 BIGINT：MySQL 驱动会把 BIGINT UNSIGNED 映射成 BigInteger，和实体里的 Long 对不上。
--   * created_at / updated_at 完全由数据库维护，应用层不写（见实体上的 FieldStrategy.NEVER）。

-- 领域（前端 / 后端 / 数据库 / 运维 / 移动端 …），后台可增删，所以单独成表而不是枚举。
CREATE TABLE category (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32)  NOT NULL COMMENT '稳定的英文标识，导入分类建议、前端筛选都依赖它',
    name_zh    VARCHAR(64)  NOT NULL,
    name_en    VARCHAR(64)  NOT NULL,
    icon       VARCHAR(32)  NULL COMMENT '前端图标名，缺省时前端用默认图标',
    sort_order INT          NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 标签。slug 由名称归一化生成，用来做“同名不同大小写算同一个标签”的去重。
CREATE TABLE tag (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    slug       VARCHAR(96) NOT NULL,
    name_zh    VARCHAR(64) NOT NULL,
    name_en    VARCHAR(64) NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_slug (slug)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE article (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    slug          VARCHAR(160) NOT NULL COMMENT 'URL 标识，前台 /articles/:slug',
    title         VARCHAR(255) NOT NULL,
    summary       VARCHAR(500) NULL,
    content       LONGTEXT     NOT NULL COMMENT 'Markdown 原文',
    level         VARCHAR(16)  NOT NULL DEFAULT 'INTERMEDIATE' COMMENT 'BEGINNER入门 / INTERMEDIATE进阶 / ADVANCED高级 / EXPERT资深',
    category_id   BIGINT       NULL COMMENT '草稿允许为空，发布前由应用层校验必填',
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    source_url    VARCHAR(512) NULL COMMENT '原文链接（转载文章必须保留）',
    source_author VARCHAR(128) NULL COMMENT '原作者',
    cover_url     VARCHAR(512) NULL,
    view_count    BIGINT       NOT NULL DEFAULT 0,
    word_count    INT          NOT NULL DEFAULT 0 COMMENT '保存时计算，列表页估算阅读时长用，避免读 LONGTEXT 长度',
    published_at  DATETIME     NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_article_slug (slug),
    KEY idx_article_status_published (status, published_at),
    KEY idx_article_category (category_id),
    KEY idx_article_level (level),
    -- ngram 解析器才能对中文分词；列顺序必须和查询里 MATCH(...) 的列顺序完全一致，否则用不上该索引
    FULLTEXT KEY ft_article_search (title, summary, content) WITH PARSER ngram,
    CONSTRAINT fk_article_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE RESTRICT,
    CONSTRAINT ck_article_level CHECK (level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'EXPERT')),
    CONSTRAINT ck_article_status CHECK (status IN ('DRAFT', 'PUBLISHED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE article_tag (
    article_id BIGINT NOT NULL,
    tag_id     BIGINT NOT NULL,
    PRIMARY KEY (article_id, tag_id),
    KEY idx_article_tag_tag (tag_id),
    CONSTRAINT fk_article_tag_article FOREIGN KEY (article_id) REFERENCES article (id) ON DELETE CASCADE,
    CONSTRAINT fk_article_tag_tag FOREIGN KEY (tag_id) REFERENCES tag (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 简历：每种语言一行，内容整体存成 JSON。
-- 简历是“一次读取、整体编辑”的文档，拆成关系表只会增加维护成本。
CREATE TABLE resume (
    lang       VARCHAR(8) NOT NULL COMMENT 'zh / en',
    content    JSON       NOT NULL,
    updated_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (lang)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
