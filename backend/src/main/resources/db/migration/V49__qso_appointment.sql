-- 质量安全负责人任职档案和协助履职授权（《化妆品生产质量管理规范》第七条、第五十条）。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE qso_appointment (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    appoint_no        VARCHAR(40)   NOT NULL,
    username          VARCHAR(64)   NOT NULL,
    person_name       VARCHAR(60)   NOT NULL,
    major_field       VARCHAR(20)   NOT NULL,
    major_detail      VARCHAR(100),
    education         VARCHAR(60),
    years_experience  INT           NOT NULL,
    experience        VARCHAR(500),
    appointed_on      DATE          NOT NULL,
    status            VARCHAR(12)   NOT NULL,
    -- 现任时为 'ACTIVE'，其余为空：唯一约束保证同一时间只有一位现任
    open_key          VARCHAR(10),
    ended_on          DATE,
    end_reason        VARCHAR(300),
    created_by        VARCHAR(64)   NOT NULL,
    created_at        DATETIME      NOT NULL,
    activated_by      VARCHAR(64),
    activated_at      DATETIME,
    CONSTRAINT uk_qso_appointment_no UNIQUE (appoint_no),
    CONSTRAINT uk_qso_appointment_open UNIQUE (open_key)
);

CREATE TABLE qso_delegation (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    delegation_no      VARCHAR(40)   NOT NULL,
    appointment_id     BIGINT        NOT NULL,
    delegate_username  VARCHAR(64)   NOT NULL,
    duties             VARCHAR(100)  NOT NULL,
    scope              VARCHAR(300)  NOT NULL,
    valid_from         DATE          NOT NULL,
    valid_to           DATE          NOT NULL,
    status             VARCHAR(12)   NOT NULL,
    granted_by         VARCHAR(64)   NOT NULL,
    granted_at         DATETIME      NOT NULL,
    revoked_by         VARCHAR(64),
    revoked_at         DATETIME,
    revoke_reason      VARCHAR(300),
    CONSTRAINT uk_qso_delegation_no UNIQUE (delegation_no),
    CONSTRAINT fk_qso_delegation_appointment FOREIGN KEY (appointment_id) REFERENCES qso_appointment (id)
);
CREATE INDEX idx_qso_delegation_status ON qso_delegation (status);

CREATE TABLE qso_delegation_review (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    delegation_id  BIGINT        NOT NULL,
    review_date    DATE          NOT NULL,
    note           VARCHAR(500)  NOT NULL,
    reviewed_by    VARCHAR(64)   NOT NULL,
    created_at     DATETIME      NOT NULL,
    CONSTRAINT fk_qso_review_delegation FOREIGN KEY (delegation_id) REFERENCES qso_delegation (id)
);
CREATE INDEX idx_qso_review_delegation ON qso_delegation_review (delegation_id);
