-- 配方版本与备案（PLM）、质量体系（QMS）、自产批生产记录（MES）、消息提醒
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。现有数据在这里回填，升级后立即受新规则约束。

-- ========== 配方版本：bom / bom_line 始终是“当前生效版本”的内容，其它模块读取方式不变 ==========
CREATE TABLE formula_version (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    fg_item_id   BIGINT       NOT NULL,
    version_no   INT          NOT NULL,
    status       VARCHAR(12)  NOT NULL,            -- DRAFT / PENDING / EFFECTIVE / RETIRED / DISCARDED
    change_note  VARCHAR(500) NOT NULL,
    reject_note  VARCHAR(300),
    created_by   VARCHAR(64)  NOT NULL,
    created_at   DATETIME     NOT NULL,
    submitted_at DATETIME,
    approved_by  VARCHAR(64),
    approved_at  DATETIME,
    CONSTRAINT uk_formula_version UNIQUE (fg_item_id, version_no),
    CONSTRAINT fk_fv_item FOREIGN KEY (fg_item_id) REFERENCES item (id)
);

CREATE TABLE formula_line (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id  BIGINT         NOT NULL,
    line_no     INT            NOT NULL,
    item_id     BIGINT         NOT NULL,
    qty_per     DECIMAL(18, 6) NOT NULL,
    supply_mode VARCHAR(8)     NOT NULL,
    CONSTRAINT fk_fl_version FOREIGN KEY (version_id) REFERENCES formula_version (id),
    CONSTRAINT fk_fl_item FOREIGN KEY (item_id) REFERENCES item (id)
);
CREATE INDEX idx_fl_version ON formula_line (version_id);

INSERT INTO formula_version (fg_item_id, version_no, status, change_note, created_by, created_at, approved_by, approved_at)
SELECT b.fg_item_id, 1, 'EFFECTIVE', '升级时由现有 BOM 生成', COALESCE(b.updated_by, 'system'), b.updated_at,
       COALESCE(b.updated_by, 'system'), b.updated_at
FROM bom b;

INSERT INTO formula_line (version_id, line_no, item_id, qty_per, supply_mode)
SELECT v.id, l.line_no, l.item_id, l.qty_per, l.supply_mode
FROM bom_line l
JOIN bom b ON l.bom_id = b.id
JOIN formula_version v ON v.fg_item_id = b.fg_item_id;

-- 生产单锁定配方版本：配方之后再变，已发料的单子仍按当时的版本
ALTER TABLE mfg_order ADD COLUMN formula_version_id BIGINT;
UPDATE mfg_order SET formula_version_id = (SELECT v.id FROM formula_version v WHERE v.fg_item_id = mfg_order.fg_item_id);

CREATE TABLE product_filing (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    fg_item_id         BIGINT       NOT NULL,
    formula_version_id BIGINT,                     -- 这次备案对应的配方版本；升级前的老记录为空
    filing_type        VARCHAR(10)  NOT NULL,      -- GENERAL 普通化妆品备案 / SPECIAL 特殊化妆品注册
    filing_no          VARCHAR(60),
    status             VARCHAR(12)  NOT NULL,      -- SUBMITTED / FILED / WITHDRAWN
    submitted_date     DATE,
    filed_date         DATE,
    expiry_date        DATE,                       -- 特殊化妆品注册证有效期
    note               VARCHAR(300),
    created_by         VARCHAR(64)  NOT NULL,
    created_at         DATETIME     NOT NULL,
    CONSTRAINT fk_filing_item FOREIGN KEY (fg_item_id) REFERENCES item (id),
    CONSTRAINT fk_filing_version FOREIGN KEY (formula_version_id) REFERENCES formula_version (id)
);
CREATE INDEX idx_filing_item ON product_filing (fg_item_id);

INSERT INTO product_filing (fg_item_id, filing_type, filing_no, status, note, created_by, created_at)
SELECT i.id, 'GENERAL', i.filing_no, 'FILED', '升级时由物料的备案编号生成，请核对类型和日期', 'system', CURRENT_TIMESTAMP
FROM item i
WHERE i.item_type = 'FG' AND i.filing_no IS NOT NULL AND i.filing_no <> '';

-- ========== 偏差与 CAPA ==========
CREATE TABLE deviation (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    deviation_no     VARCHAR(40)   NOT NULL,
    occurred_date    DATE          NOT NULL,
    area             VARCHAR(12)   NOT NULL,       -- PRODUCTION / QC / WAREHOUSE / SUPPLIER / OTHER
    level            VARCHAR(10)   NOT NULL,       -- MINOR / MAJOR / CRITICAL
    batch_id         BIGINT,
    mo_id            BIGINT,
    title            VARCHAR(200)  NOT NULL,
    description      VARCHAR(1000) NOT NULL,
    immediate_action VARCHAR(500),
    root_cause       VARCHAR(1000),
    due_date         DATE          NOT NULL,
    status           VARCHAR(10)   NOT NULL,       -- OPEN / CLOSED
    conclusion       VARCHAR(1000),
    closed_by        VARCHAR(64),
    closed_at        DATETIME,
    created_by       VARCHAR(64)   NOT NULL,
    created_at       DATETIME      NOT NULL,
    CONSTRAINT uk_deviation_no UNIQUE (deviation_no),
    CONSTRAINT fk_dev_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_dev_mo FOREIGN KEY (mo_id) REFERENCES mfg_order (id)
);
CREATE INDEX idx_dev_batch ON deviation (batch_id);
CREATE INDEX idx_dev_mo ON deviation (mo_id);

CREATE TABLE capa (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    capa_no      VARCHAR(40)   NOT NULL,
    source_type  VARCHAR(12)   NOT NULL,           -- DEVIATION / COMPLAINT / RECALL / AUDIT / OTHER
    source_no    VARCHAR(40),
    deviation_id BIGINT,
    action_type  VARCHAR(12)   NOT NULL,           -- CORRECTIVE / PREVENTIVE
    description  VARCHAR(1000) NOT NULL,
    owner        VARCHAR(64)   NOT NULL,
    due_date     DATE          NOT NULL,
    status       VARCHAR(10)   NOT NULL,           -- OPEN / DONE / VERIFIED
    done_note    VARCHAR(500),
    done_by      VARCHAR(64),
    done_at      DATETIME,
    verify_note  VARCHAR(500),
    verified_by  VARCHAR(64),
    verified_at  DATETIME,
    created_by   VARCHAR(64)   NOT NULL,
    created_at   DATETIME      NOT NULL,
    CONSTRAINT uk_capa_no UNIQUE (capa_no),
    CONSTRAINT fk_capa_dev FOREIGN KEY (deviation_id) REFERENCES deviation (id)
);
CREATE INDEX idx_capa_dev ON capa (deviation_id);

-- ========== 供应商 / 受托厂审核 ==========
CREATE TABLE supplier_audit (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    audit_no        VARCHAR(40)  NOT NULL,
    partner_id      BIGINT       NOT NULL,
    audit_date      DATE         NOT NULL,
    audit_type      VARCHAR(10)  NOT NULL,         -- INITIAL / ROUTINE / FOR_CAUSE
    score           INT,
    result          VARCHAR(12)  NOT NULL,         -- PASS / CONDITIONAL / FAIL
    findings        VARCHAR(1000),
    auditor         VARCHAR(100) NOT NULL,
    next_audit_date DATE,
    signed_by       VARCHAR(64)  NOT NULL,
    signed_at       DATETIME     NOT NULL,
    CONSTRAINT uk_audit_no UNIQUE (audit_no),
    CONSTRAINT fk_audit_partner FOREIGN KEY (partner_id) REFERENCES partner (id)
);
CREATE INDEX idx_audit_partner ON supplier_audit (partner_id);

-- ========== 留样台账：上市放行时自动入账；老数据从放行记录回填 ==========
CREATE TABLE retain_sample (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      BIGINT       NOT NULL,
    qty           INT          NOT NULL,
    remaining_qty INT          NOT NULL,
    location      VARCHAR(100) NOT NULL,
    retain_until  DATE         NOT NULL,
    status        VARCHAR(10)  NOT NULL,           -- HELD / USED_UP / DESTROYED
    created_by    VARCHAR(64)  NOT NULL,
    created_at    DATETIME     NOT NULL,
    CONSTRAINT uk_retain_batch UNIQUE (batch_id),
    CONSTRAINT fk_retain_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);

INSERT INTO retain_sample (batch_id, qty, remaining_qty, location, retain_until, status, created_by, created_at)
SELECT d.batch_id, d.retain_qty, d.retain_qty, d.retain_location, d.retain_until, 'HELD', d.signed_by_name, d.signed_at
FROM quality_decision d
WHERE d.stage = 'MARKET_RELEASE' AND d.decision = 'RELEASED' AND d.retain_qty IS NOT NULL AND d.retain_until IS NOT NULL;

CREATE TABLE retain_txn (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sample_id BIGINT       NOT NULL,
    txn_type  VARCHAR(10)  NOT NULL,               -- USE / DESTROY
    qty       INT          NOT NULL,
    reason    VARCHAR(300) NOT NULL,
    done_by   VARCHAR(64)  NOT NULL,
    done_at   DATETIME     NOT NULL,
    CONSTRAINT fk_rt_sample FOREIGN KEY (sample_id) REFERENCES retain_sample (id)
);
CREATE INDEX idx_rt_sample ON retain_txn (sample_id);

-- ========== 自产：工艺路线模板 → 发料时固化为批生产记录 ==========
CREATE TABLE routing_step (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    fg_item_id  BIGINT       NOT NULL,
    step_no     INT          NOT NULL,
    name        VARCHAR(100) NOT NULL,
    instruction VARCHAR(500),
    critical    BOOLEAN      NOT NULL DEFAULT FALSE, -- 关键工序须他人复核
    ipc_name    VARCHAR(60),                         -- 过程检验项目；为空表示本工序不检
    ipc_min     DECIMAL(18, 4),
    ipc_max     DECIMAL(18, 4),
    ipc_unit    VARCHAR(20),
    CONSTRAINT uk_routing_step UNIQUE (fg_item_id, step_no),
    CONSTRAINT fk_routing_item FOREIGN KEY (fg_item_id) REFERENCES item (id)
);

CREATE TABLE batch_record_step (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    mo_id        BIGINT       NOT NULL,
    step_no      INT          NOT NULL,
    name         VARCHAR(100) NOT NULL,
    instruction  VARCHAR(500),
    critical     BOOLEAN      NOT NULL DEFAULT FALSE,
    ipc_name     VARCHAR(60),
    ipc_min      DECIMAL(18, 4),
    ipc_max      DECIMAL(18, 4),
    ipc_unit     VARCHAR(20),
    status       VARCHAR(10)  NOT NULL,              -- PENDING / DONE / REVIEWED
    ipc_value    DECIMAL(18, 4),
    ipc_pass     BOOLEAN,
    note         VARCHAR(500),
    operator     VARCHAR(64),
    done_at      DATETIME,
    reviewer     VARCHAR(64),
    reviewed_at  DATETIME,
    deviation_id BIGINT,
    CONSTRAINT uk_record_step UNIQUE (mo_id, step_no),
    CONSTRAINT fk_record_mo FOREIGN KEY (mo_id) REFERENCES mfg_order (id),
    CONSTRAINT fk_record_dev FOREIGN KEY (deviation_id) REFERENCES deviation (id)
);

CREATE TABLE line_clearance (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    mo_id        BIGINT       NOT NULL,
    phase        VARCHAR(8)   NOT NULL,              -- BEFORE 开工前 / AFTER 完工后
    area         VARCHAR(100) NOT NULL,
    prev_product VARCHAR(100),
    note         VARCHAR(300),
    signed_by    VARCHAR(64)  NOT NULL,
    signed_at    DATETIME     NOT NULL,
    CONSTRAINT uk_clearance UNIQUE (mo_id, phase),
    CONSTRAINT fk_clearance_mo FOREIGN KEY (mo_id) REFERENCES mfg_order (id)
);

-- ========== 消息提醒发送记录 ==========
CREATE TABLE notification_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel    VARCHAR(10)  NOT NULL,                -- MAIL / WECOM
    subject    VARCHAR(200) NOT NULL,
    item_count INT          NOT NULL,
    success    BOOLEAN      NOT NULL,
    error      VARCHAR(500),
    triggered  VARCHAR(64)  NOT NULL,                -- 定时任务记 schedule，手工发送记账号
    sent_at    DATETIME     NOT NULL
);
