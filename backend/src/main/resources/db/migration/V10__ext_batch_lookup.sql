-- 供应商通知有问题时只给得出他们自己的批号，追溯要能按外部批号查
CREATE INDEX idx_batch_ext_no ON batch (ext_batch_no);
CREATE INDEX idx_genealogy_ext_no ON batch_genealogy (ext_batch_no);
