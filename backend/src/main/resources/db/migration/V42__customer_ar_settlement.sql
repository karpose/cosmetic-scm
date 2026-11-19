-- 客户应收对账与回款核销（V42）。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE so_line ADD COLUMN unit_price DECIMAL(18, 4);
ALTER TABLE so_line ADD COLUMN tax_rate DECIMAL(5, 2);

CREATE TABLE customer_statement (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    statement_no        VARCHAR(40)    NOT NULL UNIQUE,
    customer_id         BIGINT         NOT NULL,
    period_to           DATE           NOT NULL,
    credit_days         INT            NOT NULL DEFAULT 30,
    due_date            DATE           NOT NULL,
    status              VARCHAR(16)    NOT NULL,
    goods_amount        DECIMAL(18, 2) NOT NULL,
    tax_amount          DECIMAL(18, 2) NOT NULL,
    adjustment          DECIMAL(18, 2) NOT NULL DEFAULT 0.00,
    adjustment_note     VARCHAR(300),
    total_amount        DECIMAL(18, 2) NOT NULL,
    received_amount     DECIMAL(18, 2) NOT NULL DEFAULT 0.00,
    invoice_no          VARCHAR(60),
    invoice_date        DATE,
    approved_by         VARCHAR(64),
    approved_at         DATETIME,
    finance_sync_status VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    ext_voucher_no      VARCHAR(60),
    finance_synced_at   DATETIME,
    finance_sync_note   VARCHAR(300),
    created_by          VARCHAR(64),
    created_at          DATETIME       NOT NULL
);
CREATE INDEX idx_ar_statement_customer ON customer_statement(customer_id, status);

CREATE TABLE customer_statement_line (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    statement_id BIGINT         NOT NULL,
    so_id        BIGINT         NOT NULL,
    so_line_id   BIGINT         NOT NULL,
    item_id      BIGINT         NOT NULL,
    qty          DECIMAL(18, 4) NOT NULL,
    unit_price   DECIMAL(18, 4) NOT NULL,
    tax_rate     DECIMAL(5, 2)  NOT NULL,
    amount       DECIMAL(18, 2) NOT NULL,
    tax_amount   DECIMAL(18, 2) NOT NULL
);
CREATE INDEX idx_ar_line_statement ON customer_statement_line(statement_id);
CREATE INDEX idx_ar_line_so_line ON customer_statement_line(so_line_id);

CREATE TABLE customer_receipt (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    statement_id   BIGINT         NOT NULL,
    receipt_date   DATE           NOT NULL,
    amount         DECIMAL(18, 2) NOT NULL,
    reference_no   VARCHAR(60),
    ext_receipt_no VARCHAR(60),
    created_by     VARCHAR(64),
    created_at     DATETIME       NOT NULL
);
CREATE INDEX idx_ar_receipt_statement ON customer_receipt(statement_id);
CREATE UNIQUE INDEX uk_customer_receipt_ext_no ON customer_receipt(ext_receipt_no);
