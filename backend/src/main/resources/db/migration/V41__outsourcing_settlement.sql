-- 委外加工费对账结算（V41）。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE mfg_order ADD COLUMN processing_unit_fee DECIMAL(18, 4);
ALTER TABLE mfg_order ADD COLUMN processing_tax_rate DECIMAL(5, 2);

ALTER TABLE supplier_statement ADD COLUMN statement_type VARCHAR(16) NOT NULL DEFAULT 'PURCHASE';
ALTER TABLE supplier_statement_line MODIFY COLUMN po_line_id BIGINT NULL;
ALTER TABLE supplier_statement_line ADD COLUMN mfg_order_id BIGINT;
CREATE INDEX idx_statement_line_mo ON supplier_statement_line(mfg_order_id);
