-- 发票退回只重开登记环节，保留原对账单和退回的发票。
ALTER TABLE supplier_statement ADD COLUMN invoice_review_version INT NOT NULL DEFAULT 0;

CREATE TABLE supplier_statement_invoice_return (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    statement_id BIGINT NOT NULL,
    reason VARCHAR(300) NOT NULL,
    returned_by VARCHAR(64) NOT NULL,
    returned_at DATETIME NOT NULL,
    invoice_no VARCHAR(60),
    invoice_date DATE,
    invoice_amount DECIMAL(18, 2),
    CONSTRAINT fk_stmt_invoice_return_statement FOREIGN KEY (statement_id) REFERENCES supplier_statement (id)
);
CREATE INDEX idx_stmt_invoice_return_statement ON supplier_statement_invoice_return (statement_id, id);

ALTER TABLE supplier_statement_invoice ADD COLUMN invoice_return_id BIGINT;
ALTER TABLE supplier_statement_invoice ADD CONSTRAINT fk_stmt_invoice_return FOREIGN KEY (invoice_return_id) REFERENCES supplier_statement_invoice_return (id);
