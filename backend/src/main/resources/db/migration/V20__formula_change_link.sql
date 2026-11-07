-- 配方版本关联变更控制。旧版本没有关联，可为空。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE formula_version ADD COLUMN change_id BIGINT;
ALTER TABLE formula_version ADD CONSTRAINT fk_formula_version_change FOREIGN KEY (change_id) REFERENCES change_request (id);
CREATE INDEX idx_formula_version_change ON formula_version (change_id);
