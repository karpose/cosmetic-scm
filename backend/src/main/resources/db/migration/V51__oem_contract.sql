-- 委托生产合同（《化妆品生产质量管理规范》第五十二条；检查要点委托生产版第 8 项）。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE oem_contract (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_no       VARCHAR(40)   NOT NULL,
    -- 双方签署文本上的合同编号，没有可不填
    ext_no            VARCHAR(60),
    factory_id        BIGINT        NOT NULL,
    title             VARCHAR(100)  NOT NULL,
    signed_on         DATE          NOT NULL,
    valid_from        DATE          NOT NULL,
    valid_to          DATE          NOT NULL,
    -- 原料、包材由谁采购：BRAND 委托方提供，OEM 受托方采购，MIXED 按 BOM 逐项约定
    rm_supply         VARCHAR(10)   NOT NULL,
    pk_supply         VARCHAR(10)   NOT NULL,
    other_terms       VARCHAR(500),
    -- 签批时确认的约定事项（逗号分隔的代码）
    clauses           VARCHAR(200),
    status            VARCHAR(12)   NOT NULL,
    reject_note       VARCHAR(300),
    created_by        VARCHAR(64)   NOT NULL,
    created_at        DATETIME      NOT NULL,
    submitted_by      VARCHAR(64),
    submitted_at      DATETIME,
    approved_by       VARCHAR(64),
    approved_at       DATETIME,
    terminated_by     VARCHAR(64),
    terminated_at     DATETIME,
    end_reason        VARCHAR(300),
    CONSTRAINT uk_oem_contract_no UNIQUE (contract_no),
    CONSTRAINT fk_oem_contract_factory FOREIGN KEY (factory_id) REFERENCES partner (id)
);
CREATE INDEX idx_oem_contract_status ON oem_contract (status);
CREATE INDEX idx_oem_contract_factory ON oem_contract (factory_id);

CREATE TABLE oem_contract_product (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_id  BIGINT  NOT NULL,
    fg_item_id   BIGINT  NOT NULL,
    CONSTRAINT uk_oem_contract_product UNIQUE (contract_id, fg_item_id),
    CONSTRAINT fk_oem_contract_product_contract FOREIGN KEY (contract_id) REFERENCES oem_contract (id),
    CONSTRAINT fk_oem_contract_product_item FOREIGN KEY (fg_item_id) REFERENCES item (id)
);
CREATE INDEX idx_oem_contract_product_item ON oem_contract_product (fg_item_id);
