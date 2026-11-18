-- 外部财务系统对接：对账单同步状态/外部凭证号 + 收付款记录外部流水幂等号。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE supplier_statement ADD COLUMN finance_sync_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE supplier_statement ADD COLUMN ext_voucher_no VARCHAR(60);
ALTER TABLE supplier_statement ADD COLUMN finance_synced_at DATETIME;
ALTER TABLE supplier_statement ADD COLUMN finance_sync_note VARCHAR(300);

ALTER TABLE supplier_payment ADD COLUMN ext_payment_no VARCHAR(60);
CREATE UNIQUE INDEX uk_supplier_payment_ext_no ON supplier_payment(ext_payment_no);
