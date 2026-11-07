-- 询价比价、对账结算。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE po_line ADD COLUMN unit_price DECIMAL(18, 4);
ALTER TABLE po_line ADD COLUMN tax_rate DECIMAL(5, 2);

CREATE TABLE rfq (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    rfq_no       VARCHAR(40)  NOT NULL,
    title        VARCHAR(120) NOT NULL,
    deadline     DATE         NOT NULL,
    status       VARCHAR(12)  NOT NULL,
    close_reason VARCHAR(300),
    award_reason VARCHAR(300),
    awarded_by   VARCHAR(64),
    awarded_at   DATETIME,
    created_by   VARCHAR(64)  NOT NULL,
    created_at   DATETIME     NOT NULL,
    CONSTRAINT uk_rfq_no UNIQUE (rfq_no)
);
CREATE INDEX idx_rfq_status ON rfq (status, deadline);

CREATE TABLE rfq_line (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    rfq_id  BIGINT         NOT NULL,
    item_id BIGINT         NOT NULL,
    qty     DECIMAL(18, 4) NOT NULL,
    CONSTRAINT fk_rfq_line_rfq FOREIGN KEY (rfq_id) REFERENCES rfq (id),
    CONSTRAINT fk_rfq_line_item FOREIGN KEY (item_id) REFERENCES item (id)
);
CREATE INDEX idx_rfq_line_rfq ON rfq_line (rfq_id);

CREATE TABLE rfq_supplier (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    rfq_id      BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    CONSTRAINT uk_rfq_supplier UNIQUE (rfq_id, supplier_id),
    CONSTRAINT fk_rfq_supplier_rfq FOREIGN KEY (rfq_id) REFERENCES rfq (id),
    CONSTRAINT fk_rfq_supplier_partner FOREIGN KEY (supplier_id) REFERENCES partner (id)
);
CREATE INDEX idx_rfq_supplier_partner ON rfq_supplier (supplier_id);

CREATE TABLE rfq_quote (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    rfq_id      BIGINT         NOT NULL,
    rfq_line_id BIGINT         NOT NULL,
    supplier_id BIGINT         NOT NULL,
    unit_price  DECIMAL(18, 4) NOT NULL,
    tax_rate    DECIMAL(5, 2)  NOT NULL,
    lead_days   INT            NOT NULL,
    valid_until DATE           NOT NULL,
    note        VARCHAR(300),
    awarded     BOOLEAN        NOT NULL DEFAULT FALSE,
    po_id       BIGINT,
    updated_by  VARCHAR(64)    NOT NULL,
    updated_at  DATETIME       NOT NULL,
    CONSTRAINT uk_rfq_quote UNIQUE (rfq_line_id, supplier_id),
    CONSTRAINT fk_rfq_quote_rfq FOREIGN KEY (rfq_id) REFERENCES rfq (id),
    CONSTRAINT fk_rfq_quote_line FOREIGN KEY (rfq_line_id) REFERENCES rfq_line (id),
    CONSTRAINT fk_rfq_quote_supplier FOREIGN KEY (supplier_id) REFERENCES partner (id)
);
CREATE INDEX idx_rfq_quote_rfq ON rfq_quote (rfq_id);

CREATE TABLE supplier_statement (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    statement_no    VARCHAR(40)    NOT NULL,
    supplier_id     BIGINT         NOT NULL,
    period_to       DATE           NOT NULL,
    status          VARCHAR(12)    NOT NULL,
    goods_amount    DECIMAL(18, 2) NOT NULL,
    tax_amount      DECIMAL(18, 2) NOT NULL,
    adjustment      DECIMAL(18, 2) NOT NULL DEFAULT 0,
    adjustment_note VARCHAR(300),
    total_amount    DECIMAL(18, 2) NOT NULL,
    dispute_note    VARCHAR(300),
    cancel_reason   VARCHAR(300),
    confirmed_by    VARCHAR(64),
    confirmed_at    DATETIME,
    invoice_no      VARCHAR(60),
    invoice_date    DATE,
    invoice_amount  DECIMAL(18, 2),
    approved_by     VARCHAR(64),
    approved_at     DATETIME,
    paid_amount     DECIMAL(18, 2) NOT NULL DEFAULT 0,
    created_by      VARCHAR(64)    NOT NULL,
    created_at      DATETIME       NOT NULL,
    CONSTRAINT uk_statement_no UNIQUE (statement_no),
    CONSTRAINT fk_statement_supplier FOREIGN KEY (supplier_id) REFERENCES partner (id)
);
CREATE INDEX idx_statement_supplier ON supplier_statement (supplier_id, status);
CREATE INDEX idx_statement_status ON supplier_statement (status);

CREATE TABLE supplier_statement_line (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    statement_id BIGINT         NOT NULL,
    batch_id     BIGINT         NOT NULL,
    po_line_id   BIGINT         NOT NULL,
    qty          DECIMAL(18, 4) NOT NULL,
    unit_price   DECIMAL(18, 4) NOT NULL,
    tax_rate     DECIMAL(5, 2)  NOT NULL,
    amount       DECIMAL(18, 2) NOT NULL,
    tax_amount   DECIMAL(18, 2) NOT NULL,
    CONSTRAINT fk_statement_line_statement FOREIGN KEY (statement_id) REFERENCES supplier_statement (id),
    CONSTRAINT fk_statement_line_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_statement_line_po_line FOREIGN KEY (po_line_id) REFERENCES po_line (id)
);
CREATE INDEX idx_statement_line_statement ON supplier_statement_line (statement_id);
CREATE INDEX idx_statement_line_batch ON supplier_statement_line (batch_id);

CREATE TABLE supplier_payment (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    statement_id BIGINT         NOT NULL,
    pay_date     DATE           NOT NULL,
    amount       DECIMAL(18, 2) NOT NULL,
    reference_no VARCHAR(60),
    created_by   VARCHAR(64)    NOT NULL,
    created_at   DATETIME       NOT NULL,
    CONSTRAINT fk_payment_statement FOREIGN KEY (statement_id) REFERENCES supplier_statement (id)
);
CREATE INDEX idx_payment_statement ON supplier_payment (statement_id);
