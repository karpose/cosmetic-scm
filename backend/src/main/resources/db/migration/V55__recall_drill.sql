-- 召回演练（模拟召回）（《化妆品生产质量管理规范》第六十三条；检查要点委托生产版第 24 项）。
-- 不冻结批次、不发召回通知：按出库和库存记录列出要联系的客户、受托厂和仓库，逐一登记联系结果，按目标用时判断是否达标。
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE recall_drill (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    drill_no         VARCHAR(40)    NOT NULL,
    batch_id         BIGINT         NOT NULL,
    -- 模拟的召回情景（假设的原因）
    scenario         VARCHAR(500)   NOT NULL,
    -- 目标用时（小时）：从发起到最后一家联系完，企业自定，不内置
    target_hours     INT            NOT NULL,
    -- 发起时的数量平衡（按库存流水类型汇总），文字快照
    balance          VARCHAR(1000),
    -- IN_PROGRESS 进行中、SUBMITTED 待审核、REVIEWED 已审核、CANCELLED 已作废
    status           VARCHAR(12)    NOT NULL,
    summary          VARCHAR(1000),
    -- 提交时算出：最后一家联系完的时间、用时（分钟）、在外数量的定位率、是否达标
    finished_at      DATETIME,
    elapsed_minutes  INT,
    located_pct      DECIMAL(5, 1),
    met              BOOLEAN,
    capa_id          BIGINT,
    reject_note      VARCHAR(300),
    review_note      VARCHAR(300),
    cancel_reason    VARCHAR(300),
    started_by       VARCHAR(64)    NOT NULL,
    started_at       DATETIME       NOT NULL,
    submitted_by     VARCHAR(64),
    submitted_at     DATETIME,
    reviewed_by      VARCHAR(64),
    reviewed_at      DATETIME,
    CONSTRAINT uk_recall_drill_no UNIQUE (drill_no),
    CONSTRAINT fk_recall_drill_batch FOREIGN KEY (batch_id) REFERENCES batch (id),
    CONSTRAINT fk_recall_drill_capa FOREIGN KEY (capa_id) REFERENCES capa (id)
);
CREATE INDEX idx_recall_drill_status ON recall_drill (status);
CREATE INDEX idx_recall_drill_started ON recall_drill (started_at);
CREATE INDEX idx_recall_drill_batch ON recall_drill (batch_id);

-- 要联系的对象：收货客户（在外数量）、受托生产企业（通知停止生产）、仓库（核对在库数量）
CREATE TABLE recall_drill_line (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    drill_id        BIGINT         NOT NULL,
    seq             INT            NOT NULL,
    -- CUSTOMER 客户、FACTORY 受托生产企业、WAREHOUSE 仓库
    target          VARCHAR(12)    NOT NULL,
    partner_id      BIGINT,
    warehouse_id    BIGINT,
    -- 发起时的快照：客户为发出减普通退回，仓库为账面在库；受托厂为空
    expected_qty    DECIMAL(18, 4),
    -- 发起时的联系人和电话快照
    contact         VARCHAR(200),
    -- REACHED 已联系上、UNREACHABLE 联系不上
    result          VARCHAR(12),
    -- 客户反馈还在手上的数量、仓库实盘数量
    confirmed_qty   DECIMAL(18, 4),
    contacted_at    DATETIME,
    recorded_by     VARCHAR(64),
    note            VARCHAR(300),
    CONSTRAINT uk_recall_drill_line UNIQUE (drill_id, seq),
    CONSTRAINT fk_recall_drill_line_drill FOREIGN KEY (drill_id) REFERENCES recall_drill (id),
    CONSTRAINT fk_recall_drill_line_partner FOREIGN KEY (partner_id) REFERENCES partner (id),
    CONSTRAINT fk_recall_drill_line_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);
