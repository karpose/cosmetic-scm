-- 标签设计稿关联变更控制。已有的设计稿没有关联，可为空。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE label_artwork ADD COLUMN change_id BIGINT;
ALTER TABLE label_artwork ADD CONSTRAINT fk_label_artwork_change FOREIGN KEY (change_id) REFERENCES change_request (id);
CREATE INDEX idx_label_artwork_change ON label_artwork (change_id);
