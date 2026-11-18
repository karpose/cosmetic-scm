ALTER TABLE supplier_return ADD COLUMN receipt_source VARCHAR(16);
ALTER TABLE supplier_return ADD COLUMN received_on DATE;
ALTER TABLE supplier_return ADD COLUMN receipt_reference VARCHAR(80);

UPDATE supplier_return SET receipt_source = 'PORTAL' WHERE supplier_confirmed_at IS NOT NULL;
