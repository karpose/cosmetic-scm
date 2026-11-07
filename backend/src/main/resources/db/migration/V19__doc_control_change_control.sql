-- 受控文件（带修订版本）和变更控制。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
CREATE TABLE controlled_doc (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_code          VARCHAR(40)   NOT NULL,
    title             VARCHAR(120)  NOT NULL,
    category          VARCHAR(12)   NOT NULL,
    owner_dept        VARCHAR(60),
    review_months     INT           NOT NULL,
    status            VARCHAR(12)   NOT NULL,
    last_reviewed_on  DATE,
    obsolete_reason   VARCHAR(300),
    created_by        VARCHAR(64)   NOT NULL,
    created_at        DATETIME      NOT NULL,
    CONSTRAINT uk_controlled_doc_code UNIQUE (doc_code)
);
CREATE INDEX idx_controlled_doc_status ON controlled_doc (status);

CREATE TABLE doc_revision (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id          BIGINT        NOT NULL,
    rev_no          INT           NOT NULL,
    change_summary  VARCHAR(300)  NOT NULL,
    effective_date  DATE,
    status          VARCHAR(12)   NOT NULL,
    reject_note     VARCHAR(300),
    drafted_by      VARCHAR(64)   NOT NULL,
    drafted_at      DATETIME      NOT NULL,
    approved_by     VARCHAR(64),
    approved_at     DATETIME,
    CONSTRAINT uk_doc_revision UNIQUE (doc_id, rev_no),
    CONSTRAINT fk_doc_revision_doc FOREIGN KEY (doc_id) REFERENCES controlled_doc (id)
);
CREATE INDEX idx_doc_revision_status ON doc_revision (status);

CREATE TABLE change_request (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    change_no    VARCHAR(40)    NOT NULL,
    title        VARCHAR(120)   NOT NULL,
    change_type  VARCHAR(12)    NOT NULL,
    reason       VARCHAR(500)   NOT NULL,
    description  VARCHAR(1000)  NOT NULL,
    scope        VARCHAR(300),
    status       VARCHAR(12)    NOT NULL,
    risk         VARCHAR(8),
    impact       VARCHAR(1000),
    assessed_by  VARCHAR(64),
    assessed_at  DATETIME,
    approved_by  VARCHAR(64),
    approved_at  DATETIME,
    reject_note  VARCHAR(300),
    close_note   VARCHAR(500),
    closed_by    VARCHAR(64),
    closed_at    DATETIME,
    created_by   VARCHAR(64)    NOT NULL,
    created_at   DATETIME       NOT NULL,
    CONSTRAINT uk_change_request_no UNIQUE (change_no)
);
CREATE INDEX idx_change_request_status ON change_request (status);

CREATE TABLE change_action (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    change_id  BIGINT        NOT NULL,
    line_no    INT           NOT NULL,
    content    VARCHAR(300)  NOT NULL,
    owner      VARCHAR(64)   NOT NULL,
    due_date   DATE          NOT NULL,
    done       BOOLEAN       NOT NULL,
    done_note  VARCHAR(300),
    done_by    VARCHAR(64),
    done_at    DATETIME,
    CONSTRAINT fk_change_action_change FOREIGN KEY (change_id) REFERENCES change_request (id)
);
CREATE INDEX idx_change_action_change ON change_action (change_id);
CREATE INDEX idx_change_action_open ON change_action (done, due_date);
