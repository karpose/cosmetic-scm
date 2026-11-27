-- 委托方向受托方提供物料验收标准（《化妆品生产质量管理规范》第五十四条第二款；检查要点委托生产版第 12 项）。
-- 品牌供料的原料、包材交给受托厂时，把生效的质量标准（验收标准）和原料成分提供给受托厂，受托厂逐版确认收到：
-- 在代工厂门户确认，或由质量部门登记线下交接。同一受托厂同一版标准只确认一次，换版后要重新确认。
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE oem_spec_ack (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    factory_id    BIGINT        NOT NULL,
    spec_id       BIGINT        NOT NULL,
    -- 冗余：标准对应的物料和版本，按物料查确认情况时不用再连标准表
    item_id       BIGINT        NOT NULL,
    spec_version  INT           NOT NULL,
    -- PORTAL 受托厂在门户确认、OFFLINE 质量部门登记线下交接
    method        VARCHAR(12)   NOT NULL,
    -- 受托厂的接收人：门户确认时是门户账号的名称，线下交接时由登记人填写
    receiver      VARCHAR(64)   NOT NULL,
    note          VARCHAR(300),
    created_by    VARCHAR(64)   NOT NULL,
    created_at    DATETIME      NOT NULL,
    CONSTRAINT uk_oem_spec_ack UNIQUE (factory_id, spec_id),
    CONSTRAINT fk_oem_spec_ack_factory FOREIGN KEY (factory_id) REFERENCES partner (id),
    CONSTRAINT fk_oem_spec_ack_spec FOREIGN KEY (spec_id) REFERENCES quality_spec (id),
    CONSTRAINT fk_oem_spec_ack_item FOREIGN KEY (item_id) REFERENCES item (id)
);
CREATE INDEX idx_oem_spec_ack_item ON oem_spec_ack (item_id, factory_id);
CREATE INDEX idx_oem_spec_ack_created ON oem_spec_ack (created_at);
