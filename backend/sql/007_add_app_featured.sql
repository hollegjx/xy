-- 在 005_add_app_deployment.sql 之后手动执行一次；006 仅用于指定管理员账号。
-- 既有应用默认非精选，不修改原有生成或发布数据；已执行过时不要重复执行。
USE xy;

ALTER TABLE `app`
    ADD COLUMN `featured` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否精选';
