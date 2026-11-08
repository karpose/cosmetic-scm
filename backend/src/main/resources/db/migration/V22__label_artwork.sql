-- 标签设计稿评审：按包材分版本，关联成品和当时的生效配方版本。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE label_artwork (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    artwork_no          VARCHAR(40)   NOT NULL,
    pk_item_id          BIGINT        NOT NULL,
    fg_item_id          BIGINT        NOT NULL,
    formula_version_id  BIGINT        NOT NULL,
    version_no          INT           NOT NULL,
    status              VARCHAR(12)   NOT NULL,
    change_note         VARCHAR(300)  NOT NULL,
    checklist           VARCHAR(200),
    reject_note         VARCHAR(300),
    created_by          VARCHAR(64)   NOT NULL,
    created_at          DATETIME      NOT NULL,
    approved_by         VARCHAR(64),
    approved_at         DATETIME,
    CONSTRAINT uk_label_artwork_no UNIQUE (artwork_no),
    CONSTRAINT uk_label_artwork_version UNIQUE (pk_item_id, version_no),
    CONSTRAINT fk_label_artwork_pk FOREIGN KEY (pk_item_id) REFERENCES item (id),
    CONSTRAINT fk_label_artwork_fg FOREIGN KEY (fg_item_id) REFERENCES item (id),
    CONSTRAINT fk_label_artwork_version FOREIGN KEY (formula_version_id) REFERENCES formula_version (id)
);
CREATE INDEX idx_label_artwork_status ON label_artwork (status);
