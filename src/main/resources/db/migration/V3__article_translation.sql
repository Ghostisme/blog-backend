-- Article translations are stored beside the original article. The original
-- Chinese columns remain the source of truth and are never overwritten.
ALTER TABLE article
    ADD COLUMN title_en VARCHAR(255) NULL,
    ADD COLUMN summary_en VARCHAR(500) NULL,
    ADD COLUMN content_en LONGTEXT NULL,
    ADD COLUMN translation_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN translation_source_hash CHAR(64) NULL,
    ADD COLUMN translation_error TEXT NULL,
    ADD COLUMN translated_at DATETIME NULL,
    ADD COLUMN translation_locked TINYINT(1) NOT NULL DEFAULT 0,
    ADD FULLTEXT KEY ft_article_search_en (title_en, summary_en, content_en) WITH PARSER ngram,
    ADD KEY idx_article_translation_status (translation_status);

CREATE TABLE article_translation_job (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    article_id      BIGINT       NOT NULL,
    source_language  VARCHAR(8)   NOT NULL,
    target_language  VARCHAR(8)   NOT NULL,
    source_hash      CHAR(64)     NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       TEXT         NULL,
    next_run_at      DATETIME     NULL,
    locked_at        DATETIME     NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_article_translation_job_version (article_id, target_language, source_hash),
    KEY idx_article_translation_job_ready (status, next_run_at),
    CONSTRAINT fk_article_translation_job_article
        FOREIGN KEY (article_id) REFERENCES article (id) ON DELETE CASCADE,
    CONSTRAINT ck_article_translation_job_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REVIEW', 'SKIPPED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
