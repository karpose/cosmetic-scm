-- 数据量大时常用查询要用到的索引（实测：3 万批次、2 万出库单、10 万流水）
CREATE INDEX idx_so_order_date ON sales_order (order_date);
CREATE INDEX idx_mo_status ON mfg_order (status);
CREATE INDEX idx_po_status ON purchase_order (status);
CREATE INDEX idx_txn_created ON stock_txn (created_at);
CREATE INDEX idx_qd_signed ON quality_decision (signed_at);
CREATE INDEX idx_complaint_received ON complaint (received_date);
CREATE INDEX idx_stock_qty ON stock (qty);
