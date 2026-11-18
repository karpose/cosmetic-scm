-- 往来单位财务档案与默认账期/税率。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE partner ADD COLUMN tax_id VARCHAR(40);
ALTER TABLE partner ADD COLUMN bank_name VARCHAR(120);
ALTER TABLE partner ADD COLUMN bank_account VARCHAR(60);
ALTER TABLE partner ADD COLUMN default_credit_days INT;
ALTER TABLE partner ADD COLUMN default_tax_rate DECIMAL(5,2);
ALTER TABLE partner ADD COLUMN finance_code VARCHAR(40);
