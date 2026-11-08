-- 稳定性试验方案修改和结果更正。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
-- 方案修改：加时间点、取消还没做的时间点、加检测项目，每次记一版，写明原因。
CREATE TABLE stability_amendment (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    study_id    BIGINT         NOT NULL,
    rev_no      INT            NOT NULL,
    reason      VARCHAR(300)   NOT NULL,
    summary     VARCHAR(1000)  NOT NULL,
    created_by  VARCHAR(64)    NOT NULL,
    created_at  DATETIME       NOT NULL,
    CONSTRAINT uk_stability_amendment UNIQUE (study_id, rev_no),
    CONSTRAINT fk_stability_amendment_study FOREIGN KEY (study_id) REFERENCES stability_study (id)
);

-- 结果更正：提交后的结果不能直接改，申请更正、另一位质量安全负责人批准后才生效，原值留在这里
CREATE TABLE stability_correction (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    study_id      BIGINT         NOT NULL,
    pull_id       BIGINT         NOT NULL,
    test_id       BIGINT         NOT NULL,
    old_value     DECIMAL(18, 4),
    old_text      VARCHAR(200),
    old_passed    BOOLEAN        NOT NULL,
    new_value     DECIMAL(18, 4),
    new_text      VARCHAR(200),
    new_passed    BOOLEAN        NOT NULL,
    reason        VARCHAR(300)   NOT NULL,
    status        VARCHAR(12)    NOT NULL,
    requested_by  VARCHAR(64)    NOT NULL,
    requested_at  DATETIME       NOT NULL,
    decided_by    VARCHAR(64),
    decided_at    DATETIME,
    decide_note   VARCHAR(300),
    CONSTRAINT fk_stability_correction_study FOREIGN KEY (study_id) REFERENCES stability_study (id),
    CONSTRAINT fk_stability_correction_pull FOREIGN KEY (pull_id) REFERENCES stability_pull (id),
    CONSTRAINT fk_stability_correction_test FOREIGN KEY (test_id) REFERENCES stability_test (id)
);
CREATE INDEX idx_stability_correction_study ON stability_correction (study_id);
CREATE INDEX idx_stability_correction_status ON stability_correction (status);
