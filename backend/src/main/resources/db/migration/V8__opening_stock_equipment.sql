-- 设备台账与校准；工序可要求记录设备。期初库存不需要新表：批次来源新增 OPEN（batch.source_type 是 VARCHAR(4)）
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。

CREATE TABLE equipment (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    code              VARCHAR(40)  NOT NULL,
    name              VARCHAR(100) NOT NULL,
    location          VARCHAR(100),
    needs_calibration BOOLEAN      NOT NULL DEFAULT TRUE,
    cycle_months      INT,                             -- 校准周期；不需校准的设备为空
    next_due          DATE,                            -- 下次校准到期日，到期后不能用于批记录
    status            VARCHAR(10)  NOT NULL,           -- IN_USE 在用 / STOPPED 停用
    stop_reason       VARCHAR(300),
    created_by        VARCHAR(64)  NOT NULL,
    created_at        DATETIME     NOT NULL,
    CONSTRAINT uk_equipment_code UNIQUE (code)
);

CREATE TABLE calibration_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    equipment_id    BIGINT       NOT NULL,
    calibrated_date DATE         NOT NULL,
    result          VARCHAR(8)   NOT NULL,             -- PASS / FAIL
    certificate_no  VARCHAR(60),
    next_due        DATE,
    note            VARCHAR(300),
    done_by         VARCHAR(64)  NOT NULL,
    done_at         DATETIME     NOT NULL,
    CONSTRAINT fk_cal_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id)
);
CREATE INDEX idx_cal_equipment ON calibration_record (equipment_id);

ALTER TABLE routing_step ADD COLUMN requires_equipment BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE batch_record_step ADD COLUMN requires_equipment BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE batch_record_step ADD COLUMN equipment_id BIGINT;
ALTER TABLE batch_record_step ADD CONSTRAINT fk_record_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id);
CREATE INDEX idx_record_equipment ON batch_record_step (equipment_id);
