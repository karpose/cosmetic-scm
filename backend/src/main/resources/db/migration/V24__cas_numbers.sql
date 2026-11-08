-- 物料的 CAS 号，法规目录按 CAS 号对照。一个 CAS 号可能对应多条目录（不唯一），对上多条时取最严的。
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE item ADD COLUMN cas_no VARCHAR(20);
CREATE INDEX idx_item_cas ON item (cas_no);

CREATE TABLE regulated_cas (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    substance_id  BIGINT       NOT NULL,
    cas           VARCHAR(20)  NOT NULL,
    CONSTRAINT fk_regulated_cas_substance FOREIGN KEY (substance_id) REFERENCES regulated_substance (id)
);
CREATE INDEX idx_regulated_cas ON regulated_cas (cas);
CREATE INDEX idx_regulated_cas_substance ON regulated_cas (substance_id);
