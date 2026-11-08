-- 受控文件的已读确认（培训记录）。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE controlled_doc ADD COLUMN train_roles VARCHAR(200);

CREATE TABLE doc_ack (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    revision_id  BIGINT       NOT NULL,
    username     VARCHAR(64)  NOT NULL,
    acked_at     DATETIME     NOT NULL,
    CONSTRAINT uk_doc_ack UNIQUE (revision_id, username),
    CONSTRAINT fk_doc_ack_revision FOREIGN KEY (revision_id) REFERENCES doc_revision (id)
);
