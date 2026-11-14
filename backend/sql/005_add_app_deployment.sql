-- 在 004_add_generation.sql 之后手动执行一次。不修改生成记录与已有文件。
-- 已执行过时不要重复执行；如有同名字段或索引，请先核对结构。
USE xy;

ALTER TABLE `app`
    ADD COLUMN `publishedVersion` BIGINT NULL COMMENT '已发布成功版本的 AI 消息 ID',
    ADD COLUMN `deployKey` VARCHAR(32) NULL COMMENT '服务端生成的稳定分享标识',
    ADD COLUMN `deployedAt` DATETIME NULL COMMENT '最近发布时间',
    ADD UNIQUE INDEX `uk_app_deployKey` (`deployKey`);
