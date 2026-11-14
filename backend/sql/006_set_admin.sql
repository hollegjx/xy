-- 可选：在 002_create_user.sql 已执行且目标账号已注册后，手动设置管理员。
-- 将下面的 NULL 改为自己的用户 ID，先运行 SELECT 核对账号，再执行 UPDATE。
-- 只更新指定的未删除账号角色；不改变表结构、密码或其他账号。
USE xy;
SET @xyAdminUserId = NULL;

SELECT id, userAccount, userName, userRole
FROM `user` WHERE id = @xyAdminUserId AND isDelete = 0;

UPDATE `user` SET userRole = 'admin'
WHERE id = @xyAdminUserId AND isDelete = 0;

SELECT id, userAccount, userName, userRole
FROM `user` WHERE id = @xyAdminUserId AND isDelete = 0;
