-- 包材打样（签样）和印刷批次记录。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
-- 每个印刷供应商对每一版已批准的设计稿打样，质量签样后才能放行这家供应商的这一版包材。
CREATE TABLE artwork_proof (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    proof_no     VARCHAR(40)   NOT NULL,
    artwork_id   BIGINT        NOT NULL,
    supplier_id  BIGINT        NOT NULL,
    round_no     INT           NOT NULL,
    proof_date   DATE          NOT NULL,
    status       VARCHAR(12)   NOT NULL,
    note         VARCHAR(300),
    checklist    VARCHAR(200),
    result_note  VARCHAR(300),
    created_by   VARCHAR(64)   NOT NULL,
    created_at   DATETIME      NOT NULL,
    signed_by    VARCHAR(64),
    signed_at    DATETIME,
    CONSTRAINT uk_artwork_proof_no UNIQUE (proof_no),
    CONSTRAINT uk_artwork_proof_round UNIQUE (artwork_id, supplier_id, round_no),
    CONSTRAINT fk_artwork_proof_artwork FOREIGN KEY (artwork_id) REFERENCES label_artwork (id),
    CONSTRAINT fk_artwork_proof_supplier FOREIGN KEY (supplier_id) REFERENCES partner (id)
);
CREATE INDEX idx_artwork_proof_status ON artwork_proof (status);

-- 包材批次放行时按哪一版设计稿、哪次签样核对的
ALTER TABLE batch ADD COLUMN artwork_id BIGINT NULL;
ALTER TABLE batch ADD COLUMN proof_id BIGINT NULL;
ALTER TABLE batch ADD CONSTRAINT fk_batch_artwork FOREIGN KEY (artwork_id) REFERENCES label_artwork (id);
ALTER TABLE batch ADD CONSTRAINT fk_batch_proof FOREIGN KEY (proof_id) REFERENCES artwork_proof (id);
CREATE INDEX idx_batch_artwork ON batch (artwork_id);
