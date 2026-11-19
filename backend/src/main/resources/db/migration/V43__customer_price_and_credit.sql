-- 客户协议价 / 渠道供货价目表与客户信用额度（V43）。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE partner ADD COLUMN credit_limit DECIMAL(18, 2);

CREATE TABLE customer_price (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT         NOT NULL,
    item_id     BIGINT         NOT NULL,
    unit_price  DECIMAL(18, 4) NOT NULL,
    tax_rate    DECIMAL(5, 2)  NOT NULL DEFAULT 13.00,
    valid_from  DATE           NOT NULL,
    valid_to    DATE,
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    note        VARCHAR(300),
    created_by  VARCHAR(64),
    created_at  DATETIME       NOT NULL,
    updated_by  VARCHAR(64),
    updated_at  DATETIME
);
CREATE INDEX idx_customer_price_lookup ON customer_price(customer_id, item_id, active);
