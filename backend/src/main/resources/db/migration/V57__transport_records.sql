-- 产品运输条件和运输记录（《化妆品生产质量管理规范》第五十九条贮存和运输；检查要点委托生产版第 20 项）。
-- 运输条件不内置，由质量部门按产品标签和质量标准给成品设定（温度范围、是否要记录途中温度、注意事项）。
-- 销售发运和受托厂成品到仓各登记一条运输记录，一条记录可以包含多张出库单或委托单（同一车、同一批快递揽收）。
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE transport_req (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id           BIGINT        NOT NULL,
    temp_min          DECIMAL(5,1),
    temp_max          DECIMAL(5,1),
    -- 途中要记录温度（记录仪或冷链温度记录）；只有设了温度范围才能要求
    monitor_required  BOOLEAN       NOT NULL,
    -- 避光、防冻、防压、堆码层数等
    handling_note     VARCHAR(300),
    -- 这天及以后的出库、到仓才要求登记运输记录（设条件前的单据不追溯）
    effective_from    DATE          NOT NULL,
    active            BOOLEAN       NOT NULL,
    created_by        VARCHAR(64)   NOT NULL,
    created_at        DATETIME      NOT NULL,
    updated_by        VARCHAR(64),
    updated_at        DATETIME,
    CONSTRAINT uk_transport_req_item UNIQUE (item_id),
    CONSTRAINT fk_transport_req_item FOREIGN KEY (item_id) REFERENCES item (id)
);

-- 运输记录：只增不改，录错的由质量部门作废（写明原因），作废的保留
CREATE TABLE transport_record (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_no         VARCHAR(40)   NOT NULL,
    -- OUTBOUND 销售发运、INBOUND 受托厂成品到仓
    direction         VARCHAR(8)    NOT NULL,
    -- VEHICLE 专车、LOGISTICS 物流、EXPRESS 快递、COLD_CHAIN 冷链、PICKUP 客户自提
    mode              VARCHAR(12)   NOT NULL,
    carrier           VARCHAR(100),
    waybill_no        VARCHAR(100),
    shipped_at        DATETIME      NOT NULL,
    delivered_at      DATETIME,
    -- 登记时这些产品的运输条件（取最严的；之后改条件不重判旧记录）
    req_temp_min      DECIMAL(5,1),
    req_temp_max      DECIMAL(5,1),
    req_monitor       BOOLEAN       NOT NULL,
    req_text          VARCHAR(600),
    temp_monitored    BOOLEAN       NOT NULL,
    temp_low          DECIMAL(5,1),
    temp_high         DECIMAL(5,1),
    device            VARCHAR(60),
    -- 到货时包装破损、受潮、污染等
    damaged           BOOLEAN       NOT NULL,
    damage_note       VARCHAR(300),
    conforming        BOOLEAN       NOT NULL,
    problem           VARCHAR(600),
    deviation_id      BIGINT,
    note              VARCHAR(300),
    voided            BOOLEAN       NOT NULL,
    void_reason       VARCHAR(300),
    voided_by         VARCHAR(64),
    voided_at         DATETIME,
    created_by        VARCHAR(64)   NOT NULL,
    created_at        DATETIME      NOT NULL,
    CONSTRAINT uk_transport_record_no UNIQUE (record_no),
    CONSTRAINT fk_transport_record_dev FOREIGN KEY (deviation_id) REFERENCES deviation (id)
);
CREATE INDEX idx_transport_record_shipped ON transport_record (shipped_at);
CREATE INDEX idx_transport_record_dev ON transport_record (deviation_id);

-- 运输记录包含的单据：SO 销售出库单、MO 委托单
CREATE TABLE transport_doc (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id         BIGINT        NOT NULL,
    doc_type          VARCHAR(4)    NOT NULL,
    doc_id            BIGINT        NOT NULL,
    -- 记录没作废时是“类型:id”，作废后清空；一张单据只能在一条有效的运输记录里
    dup_key           VARCHAR(30),
    CONSTRAINT uk_transport_doc_dup UNIQUE (dup_key),
    CONSTRAINT fk_transport_doc_record FOREIGN KEY (record_id) REFERENCES transport_record (id)
);
CREATE INDEX idx_transport_doc_doc ON transport_doc (doc_type, doc_id);
CREATE INDEX idx_transport_doc_record ON transport_doc (record_id);
