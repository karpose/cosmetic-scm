-- V45: 委外寄售料账核销 + 生产补料与余料退库
ALTER TABLE mfg_order ADD COLUMN oem_warehouse_id BIGINT NULL;

ALTER TABLE mfg_issue ADD COLUMN issue_type VARCHAR(16) NOT NULL DEFAULT 'INITIAL';
ALTER TABLE mfg_issue ADD COLUMN reason VARCHAR(300) NULL;
ALTER TABLE mfg_issue ADD COLUMN created_by VARCHAR(64) NULL;
ALTER TABLE mfg_issue ADD COLUMN created_at TIMESTAMP NULL;
