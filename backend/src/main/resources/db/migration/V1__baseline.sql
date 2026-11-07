-- V1 基线：应用元数据表。
-- 用途：证明真实 MySQL 上 Flyway 迁移能执行，并给健康检查一个真实可读的表。
-- 注意：这是 V01-T02 的最小基线，不是 docs/16 的 43 张业务表；业务表从 V02 起按任务逐个加迁移。
CREATE TABLE IF NOT EXISTS app_meta (
    meta_key   VARCHAR(64)  NOT NULL COMMENT '元数据键，如 schema_version',
    meta_value VARCHAR(255) NOT NULL COMMENT '元数据值',
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间（UTC）',
    PRIMARY KEY (meta_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '应用元数据表：迁移版本与启动探针';

INSERT INTO app_meta (meta_key, meta_value)
VALUES ('schema_version', 'V1')
ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value);
