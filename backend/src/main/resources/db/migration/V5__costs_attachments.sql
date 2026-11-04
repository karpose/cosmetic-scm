-- 成本与金额、附件
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。

-- 标准成本（元 / 物料单位）；成品指导价（元 / 件）。成品没填标准成本时按 BOM 物料成本估算。
ALTER TABLE item ADD COLUMN std_cost DECIMAL(18, 4);
ALTER TABLE item ADD COLUMN list_price DECIMAL(18, 4);

-- 附件：COA、检验报告、受托厂出厂报告、资质证书、投诉照片等。只增不删（作为记录的一部分），文件存在服务器的文件卷里。
CREATE TABLE attachment (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_type   VARCHAR(20)  NOT NULL,          -- BATCH / PURCHASE_ORDER / MFG_ORDER / COMPLAINT / RECALL / SALES_RETURN / PARTNER
    owner_id     BIGINT       NOT NULL,
    file_name    VARCHAR(200) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    sha256       VARCHAR(64)  NOT NULL,          -- 上传时计算，下载时可核对文件未被改动
    storage_key  VARCHAR(200) NOT NULL,
    note         VARCHAR(200),
    uploaded_by  VARCHAR(64)  NOT NULL,
    uploaded_at  DATETIME     NOT NULL,
    CONSTRAINT uk_attachment_key UNIQUE (storage_key)
);
CREATE INDEX idx_attachment_owner ON attachment (owner_type, owner_id);
