-- 贮存区域和温湿度记录（《化妆品生产质量管理规范》第五十六条、第五十九条；检查要点委托生产版第 16、20 项）。
-- 温湿度范围不内置，由质量部门按产品要求给每个区域设定。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE storage_area (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(40)   NOT NULL,
    name            VARCHAR(100)  NOT NULL,
    -- WAREHOUSE 仓储区、RETAIN 留样区、OTHER 其他
    kind            VARCHAR(12)   NOT NULL,
    -- 区域所在的仓库（超限时列出这个仓库现有的批次），可以不关联
    warehouse_id    BIGINT,
    temp_min        DECIMAL(5,1),
    temp_max        DECIMAL(5,1),
    rh_min          DECIMAL(4,1),
    rh_max          DECIMAL(4,1),
    -- 要求每天至少记录一次；从 monitor_from 这天起算漏记
    daily_required  BOOLEAN       NOT NULL,
    monitor_from    DATE          NOT NULL,
    active          BOOLEAN       NOT NULL,
    note            VARCHAR(300),
    created_by      VARCHAR(64)   NOT NULL,
    created_at      DATETIME      NOT NULL,
    updated_by      VARCHAR(64),
    updated_at      DATETIME,
    CONSTRAINT uk_storage_area_code UNIQUE (code),
    CONSTRAINT fk_storage_area_wh FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

-- 一次超限：同一区域、偏差还没关闭、和上一条超限记录相隔不超过 scm.storage-env.excursion-merge-hours 小时的超限记录算一次，生成一份偏差
CREATE TABLE env_excursion (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    excursion_no    VARCHAR(40)   NOT NULL,
    area_id         BIGINT        NOT NULL,
    started_at      DATETIME      NOT NULL,
    last_at         DATETIME      NOT NULL,
    reading_count   INT           NOT NULL,
    temp_low        DECIMAL(5,1),
    temp_high       DECIMAL(5,1),
    rh_low          DECIMAL(4,1),
    rh_high         DECIMAL(4,1),
    deviation_id    BIGINT,
    -- 超限记录都作废了（录错），偏差由质量部门评估后关闭
    voided          BOOLEAN       NOT NULL,
    created_by      VARCHAR(64)   NOT NULL,
    created_at      DATETIME      NOT NULL,
    CONSTRAINT uk_env_excursion_no UNIQUE (excursion_no),
    CONSTRAINT fk_env_excursion_area FOREIGN KEY (area_id) REFERENCES storage_area (id),
    CONSTRAINT fk_env_excursion_dev FOREIGN KEY (deviation_id) REFERENCES deviation (id)
);
CREATE INDEX idx_env_excursion_area ON env_excursion (area_id, last_at);
CREATE INDEX idx_env_excursion_started ON env_excursion (started_at);

-- 温湿度记录：只增不改，录错的由质量部门作废（写明原因），作废的记录保留
CREATE TABLE env_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    area_id         BIGINT        NOT NULL,
    recorded_at     DATETIME      NOT NULL,
    recorded_on     DATE          NOT NULL,
    temp            DECIMAL(5,1),
    rh              DECIMAL(4,1),
    -- 记录时区域的范围（之后改范围不重判旧记录）
    temp_min        DECIMAL(5,1),
    temp_max        DECIMAL(5,1),
    rh_min          DECIMAL(4,1),
    rh_max          DECIMAL(4,1),
    in_range        BOOLEAN       NOT NULL,
    -- MANUAL 手工录入、IMPORT 记录仪导入
    source          VARCHAR(8)    NOT NULL,
    device          VARCHAR(60),
    note            VARCHAR(300),
    excursion_id    BIGINT,
    -- 没作废时是“区域 id:时间”，作废后清空；同一区域同一分钟只能有一条有效记录
    dup_key         VARCHAR(40),
    voided          BOOLEAN       NOT NULL,
    void_reason     VARCHAR(300),
    voided_by       VARCHAR(64),
    voided_at       DATETIME,
    created_by      VARCHAR(64)   NOT NULL,
    created_at      DATETIME      NOT NULL,
    CONSTRAINT uk_env_record_dup UNIQUE (dup_key),
    CONSTRAINT fk_env_record_area FOREIGN KEY (area_id) REFERENCES storage_area (id),
    CONSTRAINT fk_env_record_excursion FOREIGN KEY (excursion_id) REFERENCES env_excursion (id)
);
CREATE INDEX idx_env_record_area_time ON env_record (area_id, recorded_at);
CREATE INDEX idx_env_record_day ON env_record (recorded_on, area_id);
CREATE INDEX idx_env_record_excursion ON env_record (excursion_id);
