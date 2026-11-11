-- 在 003_create_app.sql 之后手动执行一次。新增消息表与最近成功版本字段，不修改已有应用内容。
-- 已执行过时不要重复 ALTER；如有同名表/字段，请先核对结构。
USE xy;

CREATE TABLE IF NOT EXISTS `chat_history` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息 ID；AI 消息 ID 同时作为生成版本 ID',
    `appId` BIGINT NOT NULL COMMENT '所属应用',
    `userId` BIGINT NOT NULL COMMENT '发起生成的用户',
    `messageRole` VARCHAR(16) NOT NULL COMMENT 'user / assistant',
    `content` LONGTEXT NOT NULL COMMENT '需求或模型输出',
    `status` VARCHAR(16) NOT NULL COMMENT 'running / completed / failed / cancelled',
    `errorMessage` VARCHAR(256) NULL COMMENT '可公开展示的失败原因，不保存上游原始异常',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `isDelete` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE `app` ADD COLUMN `generatedVersion` BIGINT NULL COMMENT '最近成功生成的 AI 消息 ID';
