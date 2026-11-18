-- 只有明确按送货通知收货的批次才写关联。历史数据无法确定对应通知，保持为空。
ALTER TABLE batch ADD COLUMN po_shipment_id BIGINT;
ALTER TABLE batch ADD CONSTRAINT fk_batch_po_shipment FOREIGN KEY (po_shipment_id) REFERENCES po_shipment (id);
CREATE INDEX idx_batch_partner_source_no ON batch (partner_id, source_type, source_no);
