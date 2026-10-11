-- Public assistant state is isolated from article/admin data. Event replay and
-- pending decisions survive reconnects and deployments; anonymous runs expire.
CREATE TABLE assistant_run (
    id CHAR(36) NOT NULL PRIMARY KEY,
    owner_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    lang VARCHAR(8) NOT NULL,
    page_path VARCHAR(512) NOT NULL,
    message TEXT NOT NULL,
    context_json MEDIUMTEXT NOT NULL,
    pending_json MEDIUMTEXT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_assistant_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE assistant_event (
    run_id CHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    data_json MEDIUMTEXT NOT NULL,
    PRIMARY KEY (run_id, sequence_no),
    CONSTRAINT fk_assistant_event_run FOREIGN KEY (run_id)
        REFERENCES assistant_run(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
