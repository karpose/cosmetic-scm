-- 检验项目可采用供方报告、取样记录。语法同时兼容 MySQL 8 与 H2（MySQL 模式）。
-- 标准：取样要求（文字，系统不内置抽样方案）；连续采用供方报告的批数上限（空 = 不限）
ALTER TABLE quality_spec ADD COLUMN sampling_plan VARCHAR(300) NULL;
ALTER TABLE quality_spec ADD COLUMN coa_max_streak INT NULL;

-- 检测项目：是否可以不自检、采用供方的检验报告。已有的项目都是每批自检
ALTER TABLE quality_spec_line ADD COLUMN coa_allowed BOOLEAN DEFAULT FALSE NOT NULL;

-- 检验记录：取样情况、供方报告编号、这次有没有采用供方报告
ALTER TABLE batch_inspection ADD COLUMN sample_note VARCHAR(200) NULL;
ALTER TABLE batch_inspection ADD COLUMN coa_no VARCHAR(60) NULL;
ALTER TABLE batch_inspection ADD COLUMN used_coa BOOLEAN DEFAULT FALSE NOT NULL;

-- 每个结果的来源：TEST 自检，COA 供方报告。已有的结果都是自检
ALTER TABLE batch_inspection_result ADD COLUMN source VARCHAR(8) DEFAULT 'TEST' NOT NULL;
