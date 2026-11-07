-- 新品立项和试制记录。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE npd_project (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_no      VARCHAR(40)   NOT NULL,
    name            VARCHAR(100)  NOT NULL,
    category        VARCHAR(60)   NOT NULL,
    filing_type     VARCHAR(12)   NOT NULL,
    claims          VARCHAR(300),
    target_launch   DATE          NOT NULL,
    note            VARCHAR(500),
    fg_item_id      BIGINT,
    status          VARCHAR(14)   NOT NULL,
    approved_by     VARCHAR(64),
    approved_at     DATETIME,
    reject_note     VARCHAR(300),
    submitted_by    VARCHAR(64),
    submitted_at    DATETIME,
    launched_by     VARCHAR(64),
    launched_at     DATETIME,
    cancel_reason   VARCHAR(300),
    created_by      VARCHAR(64)   NOT NULL,
    created_at      DATETIME      NOT NULL,
    CONSTRAINT uk_npd_project_no UNIQUE (project_no),
    CONSTRAINT fk_npd_project_item FOREIGN KEY (fg_item_id) REFERENCES item (id)
);
CREATE INDEX idx_npd_project_status ON npd_project (status);
CREATE INDEX idx_npd_project_item ON npd_project (fg_item_id);

CREATE TABLE npd_trial (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id          BIGINT        NOT NULL,
    trial_no            VARCHAR(40)   NOT NULL,
    kind                VARCHAR(8)    NOT NULL,
    trial_date          DATE          NOT NULL,
    formula_version_id  BIGINT,
    batch_size          VARCHAR(60),
    result              VARCHAR(8)    NOT NULL,
    summary             VARCHAR(500)  NOT NULL,
    recorded_by         VARCHAR(64)   NOT NULL,
    recorded_at         DATETIME      NOT NULL,
    CONSTRAINT uk_npd_trial_no UNIQUE (trial_no),
    CONSTRAINT fk_npd_trial_project FOREIGN KEY (project_id) REFERENCES npd_project (id),
    CONSTRAINT fk_npd_trial_version FOREIGN KEY (formula_version_id) REFERENCES formula_version (id)
);
CREATE INDEX idx_npd_trial_project ON npd_trial (project_id);
