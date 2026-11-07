-- 审计记录里有价格、金额的打上标记，读取时按角色遮盖。内容照常完整保存。
ALTER TABLE audit_log ADD COLUMN money_related BOOLEAN NOT NULL DEFAULT FALSE;
