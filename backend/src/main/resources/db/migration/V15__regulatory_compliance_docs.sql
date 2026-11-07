-- 原料法规目录、产品合规资料。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
-- 目录内容只由导入进来，系统不内置法规数据。
CREATE TABLE regulated_substance (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    catalog     VARCHAR(12)   NOT NULL,
    inci_name   VARCHAR(200)  NOT NULL,
    inci_key    VARCHAR(200)  NOT NULL,
    cn_name     VARCHAR(200),
    cas_no      VARCHAR(40),
    max_pct     DECIMAL(9, 4),
    conditions  VARCHAR(500),
    source      VARCHAR(200)  NOT NULL,
    active      BOOLEAN       NOT NULL,
    updated_by  VARCHAR(64)   NOT NULL,
    updated_at  DATETIME      NOT NULL,
    CONSTRAINT uk_regulated_inci UNIQUE (inci_key)
);

CREATE TABLE compliance_doc (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_no              VARCHAR(40)   NOT NULL,
    fg_item_id          BIGINT        NOT NULL,
    formula_version_id  BIGINT        NOT NULL,
    doc_type            VARCHAR(12)   NOT NULL,
    title               VARCHAR(120)  NOT NULL,
    claim               VARCHAR(300),
    evaluator           VARCHAR(100)  NOT NULL,
    doc_date            DATE          NOT NULL,
    note                VARCHAR(500),
    status              VARCHAR(12)   NOT NULL,
    reject_note         VARCHAR(300),
    void_reason         VARCHAR(300),
    signed_by           VARCHAR(64),
    signed_at           DATETIME,
    created_by          VARCHAR(64)   NOT NULL,
    created_at          DATETIME      NOT NULL,
    CONSTRAINT uk_compliance_doc_no UNIQUE (doc_no),
    CONSTRAINT fk_compliance_doc_item FOREIGN KEY (fg_item_id) REFERENCES item (id),
    CONSTRAINT fk_compliance_doc_version FOREIGN KEY (formula_version_id) REFERENCES formula_version (id)
);
CREATE INDEX idx_compliance_doc_item ON compliance_doc (fg_item_id, status);
CREATE INDEX idx_compliance_doc_status ON compliance_doc (status);
