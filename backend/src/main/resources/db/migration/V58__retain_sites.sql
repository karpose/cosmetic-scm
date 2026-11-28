-- 留样地点（《化妆品生产质量管理规范》第五十六条；检查要点委托生产版第 15 项）。
-- 委托方在住所或主要经营场所留样，也可以在住所或主要经营场所所在地的其他经营场所留样；
-- 不是住所或主要经营场所的，要在首次留样之日起 20 个工作日内把地址等信息报告所在地负责药品监督管理的部门。
-- 留样台账的每份留样记一个留样地点（V58 以前的留样为空，由质量部门指定）。
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE retain_site (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    code              VARCHAR(40)   NOT NULL,
    name              VARCHAR(100)  NOT NULL,
    address           VARCHAR(300)  NOT NULL,
    -- DOMICILE 住所、MAIN_PREMISES 主要经营场所、OTHER_PREMISES 住所或主要经营场所所在地的其他经营场所
    kind              VARCHAR(16)   NOT NULL,
    -- 其他经营场所：使用者确认和住所或主要经营场所在同一所在地（系统不判断行政区划）
    same_locality     BOOLEAN       NOT NULL,
    -- 系统上线前就开始在这里留样的，填开始的日期（首次留样日取它和台账里最早一份留样中较早的）
    used_since        DATE,
    active            BOOLEAN       NOT NULL,
    -- 默认地点：放行时不选地点就用它。默认的为 1，其余为空，唯一约束保证只有一个
    default_key       INT,
    note              VARCHAR(300),
    -- 向药品监督管理部门报告（只有其他经营场所要报告）；登记后不能改，录错的作废后重新登记
    report_date       DATE,
    report_authority  VARCHAR(100),
    report_ref        VARCHAR(60),
    report_note       VARCHAR(300),
    reported_by       VARCHAR(64),
    reported_at       DATETIME,
    created_by        VARCHAR(64)   NOT NULL,
    created_at        DATETIME      NOT NULL,
    updated_by        VARCHAR(64),
    updated_at        DATETIME,
    CONSTRAINT uk_retain_site_code UNIQUE (code),
    CONSTRAINT uk_retain_site_default UNIQUE (default_key)
);

ALTER TABLE retain_sample ADD COLUMN site_id BIGINT;
CREATE INDEX idx_retain_sample_site ON retain_sample (site_id, created_at);
ALTER TABLE retain_sample ADD CONSTRAINT fk_retain_sample_site FOREIGN KEY (site_id) REFERENCES retain_site (id);
