-- V28: SRM 寻源多轮议价、退供物流与供应商签收、对账结算账期/多税率拆分/多票红字发票与退款闭环
-- 兼容 MySQL 8.0 与 H2 (MODE=MySQL)

-- 1. 退供单：增加退货物流与供应商门户签收确认字段
ALTER TABLE supplier_return ADD COLUMN carrier VARCHAR(60) NULL;
ALTER TABLE supplier_return ADD COLUMN tracking_no VARCHAR(60) NULL;
ALTER TABLE supplier_return ADD COLUMN shipped_by VARCHAR(64) NULL;
ALTER TABLE supplier_return ADD COLUMN shipped_at DATETIME NULL;
ALTER TABLE supplier_return ADD COLUMN supplier_confirmed_by VARCHAR(64) NULL;
ALTER TABLE supplier_return ADD COLUMN supplier_confirmed_at DATETIME NULL;
ALTER TABLE supplier_return ADD COLUMN supplier_note VARCHAR(300) NULL;

-- 2. 供应商对账单：增加信用账期天数与应付到期日
ALTER TABLE supplier_statement ADD COLUMN credit_days INT NOT NULL DEFAULT 30;
ALTER TABLE supplier_statement ADD COLUMN due_date DATE NULL;

-- 3. 供应商付款/退款记录：增加收付类型（PAY=付款, REFUND=退货冲减超额时供应商退款）
ALTER TABLE supplier_payment ADD COLUMN payment_type VARCHAR(12) NOT NULL DEFAULT 'PAY';

-- 4. 对账单多发票明细表（支持一单多票、蓝字发票 BLUE 与红字冲减发票 RED）
CREATE TABLE supplier_statement_invoice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    statement_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    invoice_no VARCHAR(60) NOT NULL,
    invoice_type VARCHAR(12) NOT NULL DEFAULT 'BLUE',
    invoice_date DATE NOT NULL,
    tax_rate DECIMAL(5, 2) NOT NULL DEFAULT 13.00,
    amount DECIMAL(18, 2) NOT NULL,
    tax_amount DECIMAL(18, 2) NOT NULL DEFAULT 0.00,
    note VARCHAR(300) NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_stmt_inv_statement FOREIGN KEY (statement_id) REFERENCES supplier_statement (id),
    CONSTRAINT fk_stmt_inv_supplier FOREIGN KEY (supplier_id) REFERENCES partner (id)
);

CREATE INDEX idx_stmt_inv_statement ON supplier_statement_invoice (statement_id, id);
CREATE INDEX idx_stmt_inv_supplier_no ON supplier_statement_invoice (supplier_id, invoice_no);

-- 5. 询价单与报价：支持多轮议价轮次与上轮报价对比
ALTER TABLE rfq ADD COLUMN round_no INT NOT NULL DEFAULT 1;
ALTER TABLE rfq ADD COLUMN round_note VARCHAR(300) NULL;

ALTER TABLE rfq_quote ADD COLUMN round_no INT NOT NULL DEFAULT 1;
ALTER TABLE rfq_quote ADD COLUMN prev_unit_price DECIMAL(18, 4) NULL;
