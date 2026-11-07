-- 稳定性试验：方案、检测项目、取样计划、检测结果。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE stability_study (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    study_no            VARCHAR(40)   NOT NULL,
    fg_item_id          BIGINT        NOT NULL,
    formula_version_id  BIGINT        NOT NULL,
    batch_id            BIGINT,
    sample_ref          VARCHAR(100),
    study_type          VARCHAR(12)   NOT NULL,
    condition_text      VARCHAR(120)  NOT NULL,
    start_date          DATE          NOT NULL,
    note                VARCHAR(500),
    status              VARCHAR(12)   NOT NULL,
    conclusion          VARCHAR(1000),
    supported_months    INT,
    submitted_by        VARCHAR(64),
    submitted_at        DATETIME,
    signed_by           VARCHAR(64),
    signed_at           DATETIME,
    reject_note         VARCHAR(300),
    cancel_reason       VARCHAR(300),
    created_by          VARCHAR(64)   NOT NULL,
    created_at          DATETIME      NOT NULL,
    CONSTRAINT uk_stability_no UNIQUE (study_no),
    CONSTRAINT fk_stability_item FOREIGN KEY (fg_item_id) REFERENCES item (id),
    CONSTRAINT fk_stability_version FOREIGN KEY (formula_version_id) REFERENCES formula_version (id),
    CONSTRAINT fk_stability_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);
CREATE INDEX idx_stability_item ON stability_study (fg_item_id, status);
CREATE INDEX idx_stability_status ON stability_study (status);

CREATE TABLE stability_test (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    study_id   BIGINT         NOT NULL,
    line_no    INT            NOT NULL,
    name       VARCHAR(60)    NOT NULL,
    unit       VARCHAR(20),
    min_value  DECIMAL(18, 4),
    max_value  DECIMAL(18, 4),
    criteria   VARCHAR(200),
    CONSTRAINT fk_stability_test_study FOREIGN KEY (study_id) REFERENCES stability_study (id)
);
CREATE INDEX idx_stability_test_study ON stability_test (study_id);

CREATE TABLE stability_pull (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    study_id      BIGINT       NOT NULL,
    label         VARCHAR(30)  NOT NULL,
    months        INT          NOT NULL,
    days          INT          NOT NULL,
    due_date      DATE         NOT NULL,
    status        VARCHAR(12)  NOT NULL,
    done_date     DATE,
    tested_by     VARCHAR(64),
    recorded_at   DATETIME,
    skip_reason   VARCHAR(300),
    note          VARCHAR(500),
    passed        BOOLEAN,
    deviation_id  BIGINT,
    CONSTRAINT fk_stability_pull_study FOREIGN KEY (study_id) REFERENCES stability_study (id),
    CONSTRAINT fk_stability_pull_deviation FOREIGN KEY (deviation_id) REFERENCES deviation (id)
);
CREATE INDEX idx_stability_pull_study ON stability_pull (study_id);
CREATE INDEX idx_stability_pull_due ON stability_pull (status, due_date);

CREATE TABLE stability_result (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    pull_id     BIGINT         NOT NULL,
    test_id     BIGINT         NOT NULL,
    value_num   DECIMAL(18, 4),
    value_text  VARCHAR(200),
    passed      BOOLEAN        NOT NULL,
    CONSTRAINT uk_stability_result UNIQUE (pull_id, test_id),
    CONSTRAINT fk_stability_result_pull FOREIGN KEY (pull_id) REFERENCES stability_pull (id),
    CONSTRAINT fk_stability_result_test FOREIGN KEY (test_id) REFERENCES stability_test (id)
);
