-- 受控文件对应哪些质量管理制度（检查要点要求建立的制度），逗号分隔的代码。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE controlled_doc ADD COLUMN procedures VARCHAR(500);
