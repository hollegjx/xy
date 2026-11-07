-- 在 001_create_database.sql 之后手动执行；仅创建用户表，不修改已有数据。
-- 如已存在 user 表，请先核对结构，不要删除原表。
USE xy;

CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
    `userAccount` VARCHAR(128) NOT NULL COMMENT '登录账号',
    `userPassword` VARCHAR(255) NOT NULL COMMENT '带算法标识的密码散列',
    `userName` VARCHAR(128) NOT NULL COMMENT '昵称',
    `userRole` VARCHAR(16) NOT NULL DEFAULT 'user' COMMENT '角色：user / admin',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_account` (`userAccount`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
