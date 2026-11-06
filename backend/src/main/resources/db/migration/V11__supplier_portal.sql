-- 供应商门户：确认订单、回复交期、送货通知。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE purchase_order ADD COLUMN expected_date DATE;
ALTER TABLE purchase_order ADD COLUMN promised_date DATE;
-- 第一次承诺的交期：准时率按它算，否则改一次交期就能把迟到变成准时
ALTER TABLE purchase_order ADD COLUMN first_promised_date DATE;
ALTER TABLE purchase_order ADD COLUMN supplier_confirmed_at DATETIME;
ALTER TABLE purchase_order ADD COLUMN supplier_confirmed_by VARCHAR(64);
ALTER TABLE purchase_order ADD COLUMN last_received_on DATE;

CREATE INDEX idx_po_supplier ON purchase_order (supplier_id, id);

CREATE TABLE po_promise_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_id         BIGINT       NOT NULL,
    promised_date DATE         NOT NULL,
    note          VARCHAR(300),
    created_by    VARCHAR(64)  NOT NULL,
    created_at    DATETIME     NOT NULL,
    CONSTRAINT fk_promise_po FOREIGN KEY (po_id) REFERENCES purchase_order (id)
);
CREATE INDEX idx_promise_po ON po_promise_log (po_id);

CREATE TABLE po_shipment (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_no  VARCHAR(40)  NOT NULL,
    po_id        BIGINT       NOT NULL,
    ship_date    DATE         NOT NULL,
    status       VARCHAR(12)  NOT NULL,
    note         VARCHAR(300),
    created_by   VARCHAR(64)  NOT NULL,
    created_at   DATETIME     NOT NULL,
    CONSTRAINT uk_shipment_no UNIQUE (shipment_no),
    CONSTRAINT fk_shipment_po FOREIGN KEY (po_id) REFERENCES purchase_order (id)
);
CREATE INDEX idx_shipment_po ON po_shipment (po_id);
CREATE INDEX idx_shipment_status ON po_shipment (status);

CREATE TABLE po_shipment_line (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_id   BIGINT         NOT NULL,
    po_line_id    BIGINT         NOT NULL,
    qty           DECIMAL(18, 4) NOT NULL,
    ext_batch_no  VARCHAR(60)    NOT NULL,
    mfg_date      DATE           NOT NULL,
    exp_date      DATE,
    CONSTRAINT fk_shipment_line_shipment FOREIGN KEY (shipment_id) REFERENCES po_shipment (id),
    CONSTRAINT fk_shipment_line_po_line FOREIGN KEY (po_line_id) REFERENCES po_line (id)
);
CREATE INDEX idx_shipment_line_shipment ON po_shipment_line (shipment_id);
