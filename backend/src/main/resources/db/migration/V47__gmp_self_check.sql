-- GMP 自查：按《化妆品生产质量管理规范检查要点（委托生产版）》逐项自查。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE gmp_self_check (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    check_no      VARCHAR(40)    NOT NULL,
    edition       VARCHAR(20)    NOT NULL,
    period_from   DATE           NOT NULL,
    period_to     DATE           NOT NULL,
    scope         VARCHAR(500),
    status        VARCHAR(12)    NOT NULL,
    -- 进行中（自查中、待签批）时等于 edition，结束后为空：唯一约束保证同一版本同时只有一份在进行
    open_key      VARCHAR(20),
    conclusion    VARCHAR(12),
    summary       VARCHAR(1000),
    reject_note   VARCHAR(300),
    cancel_reason VARCHAR(300),
    created_by    VARCHAR(64)    NOT NULL,
    created_at    DATETIME       NOT NULL,
    submitted_by  VARCHAR(64),
    submitted_at  DATETIME,
    approved_by   VARCHAR(64),
    approved_at   DATETIME,
    CONSTRAINT uk_gmp_self_check_no UNIQUE (check_no),
    CONSTRAINT uk_gmp_self_check_open UNIQUE (open_key)
);
CREATE INDEX idx_gmp_self_check_status ON gmp_self_check (status);

CREATE TABLE gmp_self_check_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    check_id    BIGINT         NOT NULL,
    item_no     INT            NOT NULL,
    result      VARCHAR(16)    NOT NULL,
    finding     VARCHAR(1000),
    evidence    VARCHAR(1000),
    capa_id     BIGINT,
    checked_by  VARCHAR(64),
    checked_at  DATETIME,
    CONSTRAINT uk_gmp_self_check_item UNIQUE (check_id, item_no),
    CONSTRAINT fk_gmp_self_check_item_check FOREIGN KEY (check_id) REFERENCES gmp_self_check (id),
    CONSTRAINT fk_gmp_self_check_item_capa FOREIGN KEY (capa_id) REFERENCES capa (id)
);
