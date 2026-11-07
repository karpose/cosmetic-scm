-- 退货给供应商。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE supplier_return (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    return_no             VARCHAR(40)    NOT NULL,
    supplier_id           BIGINT         NOT NULL,
    batch_id              BIGINT         NOT NULL,
    warehouse_id          BIGINT         NOT NULL,
    qty                   DECIMAL(18, 4) NOT NULL,
    reason                VARCHAR(300)   NOT NULL,
    status                VARCHAR(12)    NOT NULL,
    cancel_reason         VARCHAR(300),
    signed_by             VARCHAR(64),
    signed_at             DATETIME,
    -- 已经在哪张对账单里算过：结算前退的减数量，结算后退的冲减。作废对账单时清空
    settled_statement_id  BIGINT,
    created_by            VARCHAR(64)    NOT NULL,
    created_at            DATETIME       NOT NULL,
    CONSTRAINT uk_supplier_return_no UNIQUE (return_no),
    CONSTRAINT fk_supplier_return_partner FOREIGN KEY (supplier_id) REFERENCES partner (id),
    CONSTRAINT fk_supplier_return_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_supplier_return_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);
CREATE INDEX idx_supplier_return_supplier ON supplier_return (supplier_id, status);
CREATE INDEX idx_supplier_return_batch ON supplier_return (batch_id, status);
CREATE INDEX idx_supplier_return_status ON supplier_return (status);

-- 冲减行：结算后才退的货，在下一张对账单里记负数
ALTER TABLE supplier_statement_line ADD COLUMN return_id BIGINT;
