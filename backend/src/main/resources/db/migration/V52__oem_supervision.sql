-- 受托生产监督记录（《化妆品生产质量管理规范》第五十三条第一款、第五十七条第二款；检查要点委托生产版第 9、18 项）。
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE oem_supervision (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    supervision_no  VARCHAR(40)   NOT NULL,
    factory_id      BIGINT        NOT NULL,
    -- 针对某张委托单的监督（驻厂、调阅这张单的批记录）；定期现场检查可以不关联
    mo_id           BIGINT,
    -- ON_SITE 驻厂监督、INSPECTION 现场检查、RECORD_REVIEW 调阅记录、REMOTE 远程视频
    method          VARCHAR(16)   NOT NULL,
    supervised_on   DATE          NOT NULL,
    -- 覆盖的环节（逗号分隔的代码）：MATERIAL、PROCESS、ENVIRONMENT、TESTING、RELEASE、RECORDS
    scopes          VARCHAR(200)  NOT NULL,
    participants    VARCHAR(200),
    content         VARCHAR(1000) NOT NULL,
    status          VARCHAR(12)   NOT NULL,
    reject_note     VARCHAR(300),
    review_note     VARCHAR(300),
    cancel_reason   VARCHAR(300),
    created_by      VARCHAR(64)   NOT NULL,
    created_at      DATETIME      NOT NULL,
    submitted_by    VARCHAR(64),
    submitted_at    DATETIME,
    reviewed_by     VARCHAR(64),
    reviewed_at     DATETIME,
    CONSTRAINT uk_oem_supervision_no UNIQUE (supervision_no),
    CONSTRAINT fk_oem_supervision_factory FOREIGN KEY (factory_id) REFERENCES partner (id),
    CONSTRAINT fk_oem_supervision_mo FOREIGN KEY (mo_id) REFERENCES mfg_order (id)
);
CREATE INDEX idx_oem_supervision_factory ON oem_supervision (factory_id, supervised_on);
CREATE INDEX idx_oem_supervision_date ON oem_supervision (supervised_on);
CREATE INDEX idx_oem_supervision_status ON oem_supervision (status);
CREATE INDEX idx_oem_supervision_mo ON oem_supervision (mo_id);

-- 监督发现的问题：一般问题当场纠正或转 CAPA，重大问题必须转 CAPA
CREATE TABLE oem_supervision_finding (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    supervision_id  BIGINT        NOT NULL,
    seq             INT           NOT NULL,
    -- MINOR 一般问题、MAJOR 重大问题
    severity        VARCHAR(8)    NOT NULL,
    description     VARCHAR(500)  NOT NULL,
    -- 当场纠正的情况
    correction      VARCHAR(500),
    capa_id         BIGINT,
    created_by      VARCHAR(64)   NOT NULL,
    created_at      DATETIME      NOT NULL,
    CONSTRAINT uk_oem_supervision_finding UNIQUE (supervision_id, seq),
    CONSTRAINT fk_oem_supervision_finding_sup FOREIGN KEY (supervision_id) REFERENCES oem_supervision (id),
    CONSTRAINT fk_oem_supervision_finding_capa FOREIGN KEY (capa_id) REFERENCES capa (id)
);
