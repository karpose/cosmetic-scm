-- 产品销售记录（检查要点委托生产版第 19 项）：出库时记下购买者和产品信息，以后改主数据不影响已出库的记录
ALTER TABLE item ADD COLUMN net_label VARCHAR(30);
ALTER TABLE sales_order ADD COLUMN buyer_name VARCHAR(100);
ALTER TABLE sales_order ADD COLUMN buyer_address VARCHAR(200);
ALTER TABLE sales_order ADD COLUMN buyer_contact VARCHAR(100);
ALTER TABLE so_line ADD COLUMN filing_no VARCHAR(80);
ALTER TABLE so_line ADD COLUMN net_content VARCHAR(40);
