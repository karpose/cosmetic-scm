-- 生产单、采购单的取消和关闭；账号邮箱（按岗位推送每日待办）；提醒发送记录的收件人
-- 语法同时兼容 MySQL 8 与 H2（MySQL 模式）。

ALTER TABLE mfg_order ADD COLUMN cancel_reason VARCHAR(300);
ALTER TABLE mfg_order ADD COLUMN cancelled_by VARCHAR(64);
ALTER TABLE mfg_order ADD COLUMN cancelled_at DATETIME;

ALTER TABLE purchase_order ADD COLUMN close_reason VARCHAR(300);
ALTER TABLE purchase_order ADD COLUMN closed_by VARCHAR(64);
ALTER TABLE purchase_order ADD COLUMN closed_at DATETIME;

ALTER TABLE app_user ADD COLUMN email VARCHAR(120);

ALTER TABLE notification_log ADD COLUMN recipient VARCHAR(120);
