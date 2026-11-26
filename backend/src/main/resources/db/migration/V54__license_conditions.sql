-- 生产许可项目标注的特殊生产条件（《化妆品生产质量管理规范》第四十八条；检查要点委托生产版第 2 项）。
-- 委托生产眼部护肤类、儿童护肤类产品的，受托方许可项目要标注具备相应生产条件。成品标类别，生产企业标许可证上的标注。
-- 默认都是“否”：成品没标类别时不拦任何单子。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
ALTER TABLE item ADD COLUMN eye_care BOOLEAN NOT NULL DEFAULT FALSE;              -- 成品属于眼部护肤类
ALTER TABLE item ADD COLUMN child_care BOOLEAN NOT NULL DEFAULT FALSE;            -- 成品属于儿童护肤类
ALTER TABLE partner ADD COLUMN license_eye_care BOOLEAN NOT NULL DEFAULT FALSE;   -- 生产许可项目标注具备眼部护肤类生产条件
ALTER TABLE partner ADD COLUMN license_child_care BOOLEAN NOT NULL DEFAULT FALSE; -- 生产许可项目标注具备儿童护肤类生产条件
