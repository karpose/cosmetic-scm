-- 质量标准（按物料、带版本）和批次检验记录。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE quality_spec (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    spec_no      VARCHAR(40)   NOT NULL,
    item_id      BIGINT        NOT NULL,
    version_no   INT           NOT NULL,
    status       VARCHAR(12)   NOT NULL,
    change_note  VARCHAR(300)  NOT NULL,
    reject_note  VARCHAR(300),
    created_by   VARCHAR(64)   NOT NULL,
    created_at   DATETIME      NOT NULL,
    approved_by  VARCHAR(64),
    approved_at  DATETIME,
    CONSTRAINT uk_quality_spec_no UNIQUE (spec_no),
    CONSTRAINT uk_quality_spec_version UNIQUE (item_id, version_no),
    CONSTRAINT fk_quality_spec_item FOREIGN KEY (item_id) REFERENCES item (id)
);
CREATE INDEX idx_quality_spec_status ON quality_spec (status, item_id);

CREATE TABLE quality_spec_line (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    spec_id    BIGINT         NOT NULL,
    line_no    INT            NOT NULL,
    name       VARCHAR(60)    NOT NULL,
    method     VARCHAR(100),
    unit       VARCHAR(20),
    min_value  DECIMAL(18, 4),
    max_value  DECIMAL(18, 4),
    criteria   VARCHAR(200),
    CONSTRAINT fk_quality_spec_line_spec FOREIGN KEY (spec_id) REFERENCES quality_spec (id)
);
CREATE INDEX idx_quality_spec_line_spec ON quality_spec_line (spec_id);

CREATE TABLE batch_inspection (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspection_no   VARCHAR(40)   NOT NULL,
    batch_id        BIGINT        NOT NULL,
    spec_id         BIGINT        NOT NULL,
    inspected_date  DATE          NOT NULL,
    inspector       VARCHAR(64)   NOT NULL,
    note            VARCHAR(500),
    passed          BOOLEAN       NOT NULL,
    status          VARCHAR(12)   NOT NULL,
    void_reason     VARCHAR(300),
    voided_by       VARCHAR(64),
    deviation_id    BIGINT,
    created_at      DATETIME      NOT NULL,
    CONSTRAINT uk_batch_inspection_no UNIQUE (inspection_no),
    CONSTRAINT fk_batch_inspection_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_batch_inspection_spec FOREIGN KEY (spec_id) REFERENCES quality_spec (id),
    CONSTRAINT fk_batch_inspection_deviation FOREIGN KEY (deviation_id) REFERENCES deviation (id)
);
CREATE INDEX idx_batch_inspection_batch ON batch_inspection (batch_id, status);

CREATE TABLE batch_inspection_result (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspection_id  BIGINT         NOT NULL,
    spec_line_id   BIGINT         NOT NULL,
    value_num      DECIMAL(18, 4),
    value_text     VARCHAR(200),
    passed         BOOLEAN        NOT NULL,
    CONSTRAINT uk_batch_inspection_result UNIQUE (inspection_id, spec_line_id),
    CONSTRAINT fk_inspection_result_inspection FOREIGN KEY (inspection_id) REFERENCES batch_inspection (id),
    CONSTRAINT fk_inspection_result_line FOREIGN KEY (spec_line_id) REFERENCES quality_spec_line (id)
);
