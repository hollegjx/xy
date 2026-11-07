-- 首次搭建时手动执行；仅在 xy 不存在时建库，不修改已有表或数据。
CREATE DATABASE IF NOT EXISTS xy
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;
