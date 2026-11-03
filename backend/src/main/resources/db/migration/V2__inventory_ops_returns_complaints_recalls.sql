-- 库存作业（调拨、盘点、报废）、销售退货、投诉与召回
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
-- warehouse.kind 新增 RETURN（退货隔离区），stock_txn.txn_type 新增 TRANSFER / ADJUST / SCRAP / RETURN_IN / RESTOCK，
-- 原列长度够用，无需改表。

-- ========== 库存作业 ==========
CREATE TABLE inv_doc (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_no         VARCHAR(40)  NOT NULL,
    doc_type       VARCHAR(12)  NOT NULL,          -- TRANSFER / ADJUST / SCRAP
    from_wh_id     BIGINT       NOT NULL,          -- 调出仓；盘点、报废时为作业仓
    to_wh_id       BIGINT,                         -- 调入仓，仅调拨
    reason         VARCHAR(300) NOT NULL,
    signed_by_name VARCHAR(64),                    -- 报废须质量人员签批
    created_by     VARCHAR(64),
    created_at     DATETIME     NOT NULL,
    CONSTRAINT uk_inv_doc_no UNIQUE (doc_no),
    CONSTRAINT fk_invdoc_from FOREIGN KEY (from_wh_id) REFERENCES warehouse (id),
    CONSTRAINT fk_invdoc_to FOREIGN KEY (to_wh_id) REFERENCES warehouse (id)
);

CREATE TABLE inv_doc_line (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id   BIGINT         NOT NULL,
    batch_id BIGINT         NOT NULL,
    item_id  BIGINT         NOT NULL,
    book_qty DECIMAL(18, 4),                       -- 盘点时的账面数量
    qty      DECIMAL(18, 4) NOT NULL,              -- 调拨 / 报废数量；盘点为差异（可为负）
    CONSTRAINT fk_invline_doc FOREIGN KEY (doc_id) REFERENCES inv_doc (id),
    CONSTRAINT fk_invline_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_invline_item FOREIGN KEY (item_id) REFERENCES item (id)
);
CREATE INDEX idx_invline_batch ON inv_doc_line (batch_id);

-- ========== 投诉与召回 ==========
CREATE TABLE recall (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    recall_no      VARCHAR(40)    NOT NULL,
    batch_id       BIGINT         NOT NULL,
    complaint_id   BIGINT,
    reason         VARCHAR(500)   NOT NULL,
    status         VARCHAR(12)    NOT NULL,        -- OPEN / CLOSED
    shipped_qty    DECIMAL(18, 4) NOT NULL,        -- 发起时已出库总量
    conclusion     VARCHAR(1000),
    opened_by_name VARCHAR(64)    NOT NULL,
    opened_at      DATETIME       NOT NULL,
    closed_by_name VARCHAR(64),
    closed_at      DATETIME,
    CONSTRAINT uk_recall_no UNIQUE (recall_no),
    CONSTRAINT fk_recall_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);
CREATE INDEX idx_recall_batch ON recall (batch_id);

-- 召回通知：发起时按出库记录逐个客户生成，记录何时由谁通知
CREATE TABLE recall_notice (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    recall_id   BIGINT         NOT NULL,
    customer_id BIGINT         NOT NULL,
    shipped_qty DECIMAL(18, 4) NOT NULL,
    notified_at DATETIME,
    notified_by VARCHAR(64),
    note        VARCHAR(300),
    CONSTRAINT fk_notice_recall FOREIGN KEY (recall_id) REFERENCES recall (id),
    CONSTRAINT fk_notice_customer FOREIGN KEY (customer_id) REFERENCES partner (id)
);

CREATE TABLE complaint (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    complaint_no  VARCHAR(40)   NOT NULL,
    received_date DATE          NOT NULL,
    channel       VARCHAR(40)   NOT NULL,          -- 客服热线 / 电商平台 / 经销商 / 监管转办 等
    customer_id   BIGINT,
    consumer      VARCHAR(100),                    -- 投诉人及联系方式
    item_id       BIGINT        NOT NULL,
    batch_id      BIGINT,
    category      VARCHAR(12)   NOT NULL,          -- ADVERSE / QUALITY / PACKAGE / LOGISTICS / OTHER
    severity      VARCHAR(8)    NOT NULL,          -- NORMAL / SERIOUS
    description   VARCHAR(1000) NOT NULL,
    status        VARCHAR(12)   NOT NULL,          -- OPEN / CLOSED
    report_no     VARCHAR(60),                     -- 不良反应监测报告编号
    reported_date DATE,
    conclusion    VARCHAR(1000),
    capa          VARCHAR(1000),                   -- 纠正预防措施
    recall_id     BIGINT,
    closed_by     VARCHAR(64),
    closed_at     DATETIME,
    created_by    VARCHAR(64),
    created_at    DATETIME      NOT NULL,
    CONSTRAINT uk_complaint_no UNIQUE (complaint_no),
    CONSTRAINT fk_cp_customer FOREIGN KEY (customer_id) REFERENCES partner (id),
    CONSTRAINT fk_cp_item FOREIGN KEY (item_id) REFERENCES item (id),
    CONSTRAINT fk_cp_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_cp_recall FOREIGN KEY (recall_id) REFERENCES recall (id)
);

-- ========== 销售退货 ==========
-- 退回的货先进退货隔离区，不参与先到期先出分配；由质量人员逐行判定转可售或报废
CREATE TABLE sales_return (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    return_no    VARCHAR(40)  NOT NULL,
    customer_id  BIGINT       NOT NULL,
    warehouse_id BIGINT       NOT NULL,
    recall_id    BIGINT,
    return_date  DATE         NOT NULL,
    reason       VARCHAR(300) NOT NULL,
    created_by   VARCHAR(64),
    created_at   DATETIME     NOT NULL,
    CONSTRAINT uk_return_no UNIQUE (return_no),
    CONSTRAINT fk_rt_customer FOREIGN KEY (customer_id) REFERENCES partner (id),
    CONSTRAINT fk_rt_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
    CONSTRAINT fk_rt_recall FOREIGN KEY (recall_id) REFERENCES recall (id)
);

CREATE TABLE sales_return_line (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    return_id        BIGINT         NOT NULL,
    batch_id         BIGINT         NOT NULL,
    item_id          BIGINT         NOT NULL,
    qty              DECIMAL(18, 4) NOT NULL,
    disposition      VARCHAR(12)    NOT NULL,      -- PENDING / RESTOCK / SCRAP
    target_wh_id     BIGINT,
    disposition_note VARCHAR(300),
    disposed_by_name VARCHAR(64),
    disposed_at      DATETIME,
    CONSTRAINT fk_rtline_return FOREIGN KEY (return_id) REFERENCES sales_return (id),
    CONSTRAINT fk_rtline_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_rtline_item FOREIGN KEY (item_id) REFERENCES item (id),
    CONSTRAINT fk_rtline_wh FOREIGN KEY (target_wh_id) REFERENCES warehouse (id)
);
CREATE INDEX idx_rtline_batch ON sales_return_line (batch_id);
