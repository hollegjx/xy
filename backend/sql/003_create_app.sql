-- 在 001_create_database.sql、002_create_user.sql 之后手动执行；仅新增应用表。
-- 如已存在 app 表，请先核对结构，不要删除原表。
-- 本步保存创建信息；生成版本、发布版本与部署字段随对应模块的 SQL 增加。
USE xy;

CREATE TABLE IF NOT EXISTS `app` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '应用 ID',
    `userId` BIGINT NOT NULL COMMENT '创建者 ID',
    `appName` VARCHAR(128) NOT NULL COMMENT '应用名称',
    `initPrompt` TEXT NOT NULL COMMENT '初始需求，最多 16383 个 Unicode 码点',
    `codeGenType` VARCHAR(16) NOT NULL DEFAULT 'multi_file' COMMENT '生成模式：html / multi_file',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
