ALTER TABLE po_shipment ADD COLUMN receipt_dispute_note VARCHAR(500);
ALTER TABLE po_shipment ADD COLUMN receipt_disputed_at DATETIME;
ALTER TABLE po_shipment ADD COLUMN receipt_resolution_note VARCHAR(500);
ALTER TABLE po_shipment ADD COLUMN receipt_resolved_at DATETIME;
CREATE INDEX idx_shipment_receipt_dispute ON po_shipment (receipt_resolved_at, receipt_disputed_at);
