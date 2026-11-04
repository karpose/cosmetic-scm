-- 一物一码：每件成品一个随机防伪溯源码，消费者扫码查询正品与批次信息
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。不记录扫码人的 IP 等个人信息，只计次数和时间。
CREATE TABLE serial_code (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(16) NOT NULL,          -- 12 位随机码，Crockford Base32
    batch_id      BIGINT      NOT NULL,
    seq           INT         NOT NULL,          -- 批次内流水，便于印刷核对
    created_by    VARCHAR(64) NOT NULL,
    created_at    DATETIME    NOT NULL,
    scan_count    INT         NOT NULL DEFAULT 0,
    first_scan_at DATETIME,
    last_scan_at  DATETIME,
    CONSTRAINT uk_serial_code UNIQUE (code),
    CONSTRAINT fk_serial_batch FOREIGN KEY (batch_id) REFERENCES batch (id)
);
CREATE INDEX idx_serial_batch ON serial_code (batch_id);
