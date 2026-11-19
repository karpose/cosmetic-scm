-- 客户退货冲减应收对账（V44）。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE customer_statement_line MODIFY COLUMN so_id BIGINT NULL;
ALTER TABLE customer_statement_line MODIFY COLUMN so_line_id BIGINT NULL;
ALTER TABLE customer_statement_line ADD COLUMN sales_return_id BIGINT;
ALTER TABLE customer_statement_line ADD COLUMN sales_return_line_id BIGINT;
CREATE INDEX idx_ar_line_return_line ON customer_statement_line(sales_return_line_id);
