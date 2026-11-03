-- 账号安全（登录锁定、密码有效期）、代工厂门户、经销商门户、电商对接
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。app_user_role 新增 OEM_PORTAL / DEALER 两个角色，原列长度够用。

-- ========== 账号安全与门户账号 ==========
ALTER TABLE app_user ADD COLUMN partner_id BIGINT;                         -- 门户账号所属的受托厂 / 经销商
ALTER TABLE app_user ADD COLUMN failed_logins INT NOT NULL DEFAULT 0;      -- 连续登录失败次数
ALTER TABLE app_user ADD COLUMN locked_until DATETIME;                     -- 锁定到期时间
ALTER TABLE app_user ADD COLUMN password_changed_at DATETIME;              -- 用于密码有效期
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD CONSTRAINT fk_user_partner FOREIGN KEY (partner_id) REFERENCES partner (id);

-- ========== 代工厂门户 ==========
-- 受托厂回传的进度（接单确认、排产、生产中、检验中……），只增不改
CREATE TABLE oem_progress (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    mo_id        BIGINT       NOT NULL,
    stage        VARCHAR(16)  NOT NULL,          -- CONFIRMED / SCHEDULED / PRODUCING / TESTING / NOTE
    promise_date DATE,                           -- 承诺交期
    note         VARCHAR(300),
    created_by   VARCHAR(64)  NOT NULL,
    created_at   DATETIME     NOT NULL,
    CONSTRAINT fk_oemprog_mo FOREIGN KEY (mo_id) REFERENCES mfg_order (id)
);
CREATE INDEX idx_oemprog_mo ON oem_progress (mo_id);

-- 受托厂的完工与出厂放行申报；品牌方完工入库时采纳，或退回让其修改后重报
CREATE TABLE oem_report (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    mo_id             BIGINT         NOT NULL,
    status            VARCHAR(12)    NOT NULL,   -- SUBMITTED / ACCEPTED / RETURNED
    actual_qty        DECIMAL(18, 4) NOT NULL,
    mfg_date          DATE           NOT NULL,
    ext_batch_no      VARCHAR(60)    NOT NULL,   -- 受托厂成品批号
    release_report_no VARCHAR(60)    NOT NULL,   -- 出厂检验报告编号
    released_by       VARCHAR(64)    NOT NULL,   -- 受托厂质量放行人
    balance_note      VARCHAR(300),
    submitted_by      VARCHAR(64)    NOT NULL,
    submitted_at      DATETIME       NOT NULL,
    reviewed_by       VARCHAR(64),
    reviewed_at       DATETIME,
    review_note       VARCHAR(300),
    CONSTRAINT fk_oemrep_mo FOREIGN KEY (mo_id) REFERENCES mfg_order (id)
);
CREATE INDEX idx_oemrep_mo ON oem_report (mo_id);

CREATE TABLE oem_report_material (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id    BIGINT         NOT NULL,
    item_id      BIGINT         NOT NULL,
    ext_batch_no VARCHAR(60)    NOT NULL,
    qty          DECIMAL(18, 4) NOT NULL,
    CONSTRAINT fk_oemmat_report FOREIGN KEY (report_id) REFERENCES oem_report (id),
    CONSTRAINT fk_oemmat_item FOREIGN KEY (item_id) REFERENCES item (id)
);

-- ========== 经销商订货 ==========
CREATE TABLE dealer_order (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no       VARCHAR(40)  NOT NULL,
    customer_id    BIGINT       NOT NULL,
    status         VARCHAR(12)  NOT NULL,        -- SUBMITTED / APPROVED / REJECTED / CANCELLED
    requested_date DATE,
    note           VARCHAR(300),
    so_id          BIGINT,                       -- 审核通过后生成的销售出库单
    review_note    VARCHAR(300),
    reviewed_by    VARCHAR(64),
    reviewed_at    DATETIME,
    created_by     VARCHAR(64)  NOT NULL,
    created_at     DATETIME     NOT NULL,
    CONSTRAINT uk_dealer_order_no UNIQUE (order_no),
    CONSTRAINT fk_do_customer FOREIGN KEY (customer_id) REFERENCES partner (id),
    CONSTRAINT fk_do_so FOREIGN KEY (so_id) REFERENCES sales_order (id)
);

CREATE TABLE dealer_order_line (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT         NOT NULL,
    item_id  BIGINT         NOT NULL,
    qty      DECIMAL(18, 4) NOT NULL,
    CONSTRAINT fk_dol_order FOREIGN KEY (order_id) REFERENCES dealer_order (id),
    CONSTRAINT fk_dol_item FOREIGN KEY (item_id) REFERENCES item (id)
);

-- ========== 电商对接 ==========
-- 电商 ERP 推送的订单；同一平台同一订单号只收一次（幂等）
CREATE TABLE channel_order (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    platform          VARCHAR(20)  NOT NULL,     -- TMALL / JD / DOUYIN / XHS / OTHER
    platform_order_no VARCHAR(64)  NOT NULL,
    customer_id       BIGINT       NOT NULL,
    warehouse_id      BIGINT       NOT NULL,
    status            VARCHAR(12)  NOT NULL,     -- RECEIVED / SHIPPED / FAILED / CANCELLED
    so_id             BIGINT,
    error             VARCHAR(500),
    attempts          INT          NOT NULL DEFAULT 0,
    ordered_at        DATETIME,
    received_at       DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,
    CONSTRAINT uk_channel_order UNIQUE (platform, platform_order_no),
    CONSTRAINT fk_co_customer FOREIGN KEY (customer_id) REFERENCES partner (id),
    CONSTRAINT fk_co_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
    CONSTRAINT fk_co_so FOREIGN KEY (so_id) REFERENCES sales_order (id)
);
CREATE INDEX idx_co_status ON channel_order (status);

CREATE TABLE channel_order_line (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT         NOT NULL,
    sku      VARCHAR(40)    NOT NULL,            -- 平台侧商品编码，按物料编码匹配
    item_id  BIGINT,
    qty      DECIMAL(18, 4) NOT NULL,
    CONSTRAINT fk_col_order FOREIGN KEY (order_id) REFERENCES channel_order (id),
    CONSTRAINT fk_col_item FOREIGN KEY (item_id) REFERENCES item (id)
);

-- 对接日志：每次接口调用一行，便于和 ERP 对账、排查
CREATE TABLE integration_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    direction  VARCHAR(4)   NOT NULL,            -- IN 平台推给我们 / OUT 我们提供给平台
    platform   VARCHAR(20),
    action     VARCHAR(40)  NOT NULL,
    ref        VARCHAR(64),
    success    BOOLEAN      NOT NULL,
    message    VARCHAR(500),
    created_at DATETIME     NOT NULL
);
CREATE INDEX idx_intlog_time ON integration_log (created_at);
