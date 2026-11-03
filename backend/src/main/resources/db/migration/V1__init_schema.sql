-- 化妆品品牌方供应链系统 · 一期批次主线
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。生产库请使用 utf8mb4 字符集。

-- ========== 账号与权限 ==========
CREATE TABLE app_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME     NOT NULL,
    CONSTRAINT uk_user_username UNIQUE (username)
);

CREATE TABLE app_user_role (
    user_id   BIGINT      NOT NULL,
    role_name VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role_name),
    CONSTRAINT fk_role_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

-- 单号流水：每个前缀 + 日期一行，取号时行锁
CREATE TABLE doc_sequence (
    seq_key   VARCHAR(40) PRIMARY KEY,
    seq_value INT         NOT NULL
);

-- ========== 主数据 ==========
CREATE TABLE item (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(40)  NOT NULL,
    name         VARCHAR(100) NOT NULL,
    item_type    VARCHAR(8)   NOT NULL,            -- RM 原料 / PK 包材 / FG 成品
    unit         VARCHAR(16)  NOT NULL,
    inci         VARCHAR(200),
    limit_pct    DECIMAL(9, 4),                    -- 限用原料浓度上限 %
    banned       BOOLEAN      NOT NULL DEFAULT FALSE,
    shelf_months INT,
    filing_no    VARCHAR(80),                      -- 成品备案 / 注册编号
    net_g        DECIMAL(12, 3),                   -- 成品净含量（g）
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_item_code UNIQUE (code)
);

CREATE TABLE partner (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    code              VARCHAR(40)  NOT NULL,
    name              VARCHAR(100) NOT NULL,
    kind              VARCHAR(16)  NOT NULL,       -- SUPPLIER / OEM / OWN_FACTORY / CUSTOMER
    license_no        VARCHAR(100),
    license_expiry    DATE,
    license_scope     VARCHAR(300),
    batch_prefix      VARCHAR(10),
    channel           VARCHAR(40),
    min_shelf_pct     INT          NOT NULL DEFAULT 0,
    contact           VARCHAR(100),
    address           VARCHAR(200),
    qualified         BOOLEAN      NOT NULL DEFAULT TRUE,
    critical_supplier BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_partner_code UNIQUE (code)
);

CREATE TABLE warehouse (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(40)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    kind       VARCHAR(16)  NOT NULL,              -- OWN / OEM / PLATFORM / THIRD_PARTY
    partner_id BIGINT,
    CONSTRAINT uk_warehouse_code UNIQUE (code),
    CONSTRAINT fk_wh_partner FOREIGN KEY (partner_id) REFERENCES partner (id)
);

CREATE TABLE bom (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    fg_item_id BIGINT      NOT NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64),
    CONSTRAINT uk_bom_fg UNIQUE (fg_item_id),
    CONSTRAINT fk_bom_item FOREIGN KEY (fg_item_id) REFERENCES item (id)
);

CREATE TABLE bom_line (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    bom_id      BIGINT         NOT NULL,
    line_no     INT            NOT NULL,
    item_id     BIGINT         NOT NULL,
    qty_per     DECIMAL(18, 6) NOT NULL,           -- 每件成品单耗，按物料单位
    supply_mode VARCHAR(8)     NOT NULL,           -- BRAND 品牌供料 / OEM 工厂代采
    CONSTRAINT fk_bomline_bom FOREIGN KEY (bom_id) REFERENCES bom (id),
    CONSTRAINT fk_bomline_item FOREIGN KEY (item_id) REFERENCES item (id)
);

-- ========== 采购 ==========
CREATE TABLE purchase_order (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_no       VARCHAR(40) NOT NULL,
    supplier_id BIGINT      NOT NULL,
    order_date  DATE        NOT NULL,
    status      VARCHAR(12) NOT NULL,
    created_by  VARCHAR(64),
    created_at  DATETIME    NOT NULL,
    CONSTRAINT uk_po_no UNIQUE (po_no),
    CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id) REFERENCES partner (id)
);

CREATE TABLE po_line (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_id        BIGINT         NOT NULL,
    line_no      INT            NOT NULL,
    item_id      BIGINT         NOT NULL,
    qty          DECIMAL(18, 4) NOT NULL,
    received_qty DECIMAL(18, 4) NOT NULL DEFAULT 0,
    CONSTRAINT fk_poline_po FOREIGN KEY (po_id) REFERENCES purchase_order (id),
    CONSTRAINT fk_poline_item FOREIGN KEY (item_id) REFERENCES item (id)
);

-- ========== 批次与库存 ==========
CREATE TABLE batch (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no             VARCHAR(40)    NOT NULL,
    item_id              BIGINT         NOT NULL,
    mfg_date             DATE,
    exp_date             DATE,
    status               VARCHAR(20)    NOT NULL,  -- QUARANTINE / PENDING_RELEASE / RELEASED / REJECTED / FROZEN
    source_type          VARCHAR(4)     NOT NULL,  -- PO / MO
    source_no            VARCHAR(40)    NOT NULL,
    partner_id           BIGINT,
    ext_batch_no         VARCHAR(60),
    qty_in               DECIMAL(18, 4) NOT NULL,
    oem_released         BOOLEAN        NOT NULL DEFAULT FALSE,
    freeze_reason        VARCHAR(300),
    status_before_freeze VARCHAR(20),
    created_at           DATETIME       NOT NULL,
    CONSTRAINT uk_batch_no UNIQUE (batch_no),
    CONSTRAINT fk_batch_item FOREIGN KEY (item_id) REFERENCES item (id),
    CONSTRAINT fk_batch_partner FOREIGN KEY (partner_id) REFERENCES partner (id)
);
CREATE INDEX idx_batch_status ON batch (status);
CREATE INDEX idx_batch_item ON batch (item_id);

-- 批次谱系：成品批次 ← 原料 / 包材批次；工厂代采物料只有外部批号
CREATE TABLE batch_genealogy (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    child_batch_id  BIGINT         NOT NULL,
    parent_batch_id BIGINT,
    item_id         BIGINT         NOT NULL,
    qty             DECIMAL(18, 6) NOT NULL,
    ext_batch_no    VARCHAR(60),
    supply_mode     VARCHAR(8)     NOT NULL,
    CONSTRAINT fk_gen_child FOREIGN KEY (child_batch_id) REFERENCES batch (id),
    CONSTRAINT fk_gen_parent FOREIGN KEY (parent_batch_id) REFERENCES batch (id),
    CONSTRAINT fk_gen_item FOREIGN KEY (item_id) REFERENCES item (id)
);
CREATE INDEX idx_gen_parent ON batch_genealogy (parent_batch_id);
CREATE INDEX idx_gen_child ON batch_genealogy (child_batch_id);

CREATE TABLE stock (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id     BIGINT         NOT NULL,
    item_id      BIGINT         NOT NULL,
    warehouse_id BIGINT         NOT NULL,
    qty          DECIMAL(18, 4) NOT NULL,
    row_version  BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_stock_batch_wh UNIQUE (batch_id, warehouse_id),
    CONSTRAINT fk_stock_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_stock_item FOREIGN KEY (item_id) REFERENCES item (id),
    CONSTRAINT fk_stock_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);
CREATE INDEX idx_stock_item ON stock (item_id);

-- 库存流水：每一次数量变化都留一条，用于对账
CREATE TABLE stock_txn (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id     BIGINT         NOT NULL,
    warehouse_id BIGINT         NOT NULL,
    qty_delta    DECIMAL(18, 4) NOT NULL,
    txn_type     VARCHAR(12)    NOT NULL,          -- RECEIPT / ISSUE / PRODUCE / SHIP
    ref_no       VARCHAR(40),
    operator     VARCHAR(64),
    created_at   DATETIME       NOT NULL,
    CONSTRAINT fk_txn_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_txn_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);
CREATE INDEX idx_txn_batch ON stock_txn (batch_id);

-- ========== 质量 ==========
CREATE TABLE quality_decision (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id        BIGINT       NOT NULL,
    stage           VARCHAR(20)  NOT NULL,         -- MATERIAL_QC / MARKET_RELEASE
    decision        VARCHAR(12)  NOT NULL,         -- RELEASED / REJECTED
    report_no       VARCHAR(60),
    note            VARCHAR(500) NOT NULL,
    checklist       VARCHAR(500),
    retain_qty      INT,
    retain_location VARCHAR(100),
    retain_until    DATE,
    signed_by_id    BIGINT       NOT NULL,
    signed_by_name  VARCHAR(64)  NOT NULL,
    signed_at       DATETIME     NOT NULL,
    CONSTRAINT fk_qd_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_qd_user FOREIGN KEY (signed_by_id) REFERENCES app_user (id)
);
CREATE INDEX idx_qd_batch ON quality_decision (batch_id);

-- ========== 生产与委托 ==========
CREATE TABLE mfg_order (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    mo_no        VARCHAR(40)    NOT NULL,
    mo_type      VARCHAR(4)     NOT NULL,          -- OWN 自产 / OEM 委托
    factory_id   BIGINT         NOT NULL,
    fg_item_id   BIGINT         NOT NULL,
    planned_qty  DECIMAL(18, 4) NOT NULL,
    planned_date DATE,
    status       VARCHAR(12)    NOT NULL,          -- PLANNED / ISSUED / COMPLETED
    out_batch_id BIGINT,
    actual_qty   DECIMAL(18, 4),
    created_by   VARCHAR(64),
    created_at   DATETIME       NOT NULL,
    issued_at    DATETIME,
    completed_at DATETIME,
    CONSTRAINT uk_mo_no UNIQUE (mo_no),
    CONSTRAINT fk_mo_factory FOREIGN KEY (factory_id) REFERENCES partner (id),
    CONSTRAINT fk_mo_item FOREIGN KEY (fg_item_id) REFERENCES item (id),
    CONSTRAINT fk_mo_batch FOREIGN KEY (out_batch_id) REFERENCES batch (id)
);

CREATE TABLE mfg_issue (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    mo_id        BIGINT         NOT NULL,
    batch_id     BIGINT         NOT NULL,
    warehouse_id BIGINT         NOT NULL,
    item_id      BIGINT         NOT NULL,
    qty          DECIMAL(18, 4) NOT NULL,
    CONSTRAINT fk_issue_mo FOREIGN KEY (mo_id) REFERENCES mfg_order (id),
    CONSTRAINT fk_issue_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);
CREATE INDEX idx_issue_batch ON mfg_issue (batch_id);

-- ========== 销售出库 ==========
CREATE TABLE sales_order (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    so_no        VARCHAR(40) NOT NULL,
    customer_id  BIGINT      NOT NULL,
    warehouse_id BIGINT      NOT NULL,
    order_date   DATE        NOT NULL,
    status       VARCHAR(12) NOT NULL,
    created_by   VARCHAR(64),
    created_at   DATETIME    NOT NULL,
    CONSTRAINT uk_so_no UNIQUE (so_no),
    CONSTRAINT fk_so_customer FOREIGN KEY (customer_id) REFERENCES partner (id),
    CONSTRAINT fk_so_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

CREATE TABLE so_line (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    so_id   BIGINT         NOT NULL,
    item_id BIGINT         NOT NULL,
    qty     DECIMAL(18, 4) NOT NULL,
    CONSTRAINT fk_soline_so FOREIGN KEY (so_id) REFERENCES sales_order (id)
);

CREATE TABLE so_allocation (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    so_id        BIGINT         NOT NULL,
    item_id      BIGINT         NOT NULL,
    batch_id     BIGINT         NOT NULL,
    warehouse_id BIGINT         NOT NULL,
    qty          DECIMAL(18, 4) NOT NULL,
    CONSTRAINT fk_alloc_so FOREIGN KEY (so_id) REFERENCES sales_order (id),
    CONSTRAINT fk_alloc_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);
CREATE INDEX idx_alloc_batch ON so_allocation (batch_id);

-- ========== 审计日志（只增不改） ==========
CREATE TABLE audit_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_at   DATETIME      NOT NULL,
    user_id      BIGINT,
    username     VARCHAR(64)   NOT NULL,
    display_name VARCHAR(64),
    action       VARCHAR(40)   NOT NULL,
    target       VARCHAR(200),
    detail       VARCHAR(1000),
    reason       VARCHAR(500)
);
CREATE INDEX idx_audit_time ON audit_log (created_at);
