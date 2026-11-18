-- 送货通知明细逐批绑定供方 COA 报告与附件，收货时自动带入批次。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE po_shipment_line ADD COLUMN coa_no VARCHAR(60);
ALTER TABLE po_shipment_line ADD COLUMN coa_attachment_id BIGINT;
ALTER TABLE batch ADD COLUMN coa_no VARCHAR(60);
