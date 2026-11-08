-- 法规目录条目的别名（同一物质的其它 INCI 写法）。对照键唯一：一个别名只属于一个条目。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE regulated_substance ADD COLUMN aliases VARCHAR(1000);

CREATE TABLE regulated_alias (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    substance_id  BIGINT        NOT NULL,
    alias_key     VARCHAR(200)  NOT NULL,
    CONSTRAINT uk_regulated_alias UNIQUE (alias_key),
    CONSTRAINT fk_regulated_alias_substance FOREIGN KEY (substance_id) REFERENCES regulated_substance (id)
);
CREATE INDEX idx_regulated_alias_substance ON regulated_alias (substance_id);
