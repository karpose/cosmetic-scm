-- 供应商协议价 / 价目表（V40）。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE supplier_price (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    supplier_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    unit_price DECIMAL(18, 4) NOT NULL,
    tax_rate DECIMAL(5, 2) NOT NULL DEFAULT 13.00,
    valid_from DATE NOT NULL,
    valid_to DATE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    note VARCHAR(300),
    created_by VARCHAR(60),
    created_at DATETIME NOT NULL,
    updated_by VARCHAR(60),
    updated_at DATETIME NOT NULL
);

CREATE INDEX idx_supplier_price_lookup ON supplier_price(supplier_id, item_id, active);
