-- 受控文件培训考核：已读确认之后，由质量人员登记考核结果。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE controlled_doc ADD COLUMN assess_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE doc_assessment (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    revision_id  BIGINT        NOT NULL,
    username     VARCHAR(64)   NOT NULL,
    method       VARCHAR(12)   NOT NULL,
    score        INT,
    passed       BOOLEAN       NOT NULL,
    note         VARCHAR(300),
    assessed_on  DATE          NOT NULL,
    assessed_by  VARCHAR(64)   NOT NULL,
    created_at   DATETIME      NOT NULL,
    CONSTRAINT fk_doc_assessment_revision FOREIGN KEY (revision_id) REFERENCES doc_revision (id)
);
CREATE INDEX idx_doc_assessment_rev ON doc_assessment (revision_id, username);
