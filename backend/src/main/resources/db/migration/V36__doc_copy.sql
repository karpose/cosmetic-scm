-- 受控文件纸质副本的发放和回收。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE doc_copy (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id       BIGINT        NOT NULL,
    revision_id  BIGINT        NOT NULL,
    copy_no      INT           NOT NULL,
    holder       VARCHAR(100)  NOT NULL,
    status       VARCHAR(12)   NOT NULL,
    issued_by    VARCHAR(64)   NOT NULL,
    issued_at    DATETIME      NOT NULL,
    closed_by    VARCHAR(64),
    closed_at    DATETIME,
    close_note   VARCHAR(300),
    CONSTRAINT uk_doc_copy UNIQUE (revision_id, copy_no),
    CONSTRAINT fk_doc_copy_doc FOREIGN KEY (doc_id) REFERENCES controlled_doc (id),
    CONSTRAINT fk_doc_copy_revision FOREIGN KEY (revision_id) REFERENCES doc_revision (id)
);
CREATE INDEX idx_doc_copy_doc ON doc_copy (doc_id, status);
CREATE INDEX idx_doc_copy_status ON doc_copy (status);

-- 作废日期：作废后在用的纸质副本从这天起算回收期限。已作废的旧文件为空
ALTER TABLE controlled_doc ADD COLUMN obsoleted_on DATE;
