-- V4 消费去重：inbox（V03-T02 Inbox 及持久接管）
-- 由 reports/V03-T02/<runId>/tools/gen_migration_v4.py 从 contracts/mysql-schema.sql 抽取；
-- 改列必须先改契约再重新生成。迁移只增不改。

CREATE TABLE inbox (
  consumer VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '逻辑消费者名称，用于消费去重命名空间',
  event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全局唯一事件ID；重投必须复用',
  job_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Java持久业务任务ID',
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化请求或事件参数SHA-256，用于防篡改与幂等冲突检测',
  received_at DATETIME(6) NOT NULL COMMENT '首次接收事件时间，UTC',
  accepted_at DATETIME(6) NOT NULL COMMENT '持久接管事务完成记录时间，不代表任务完成',
  PRIMARY KEY (consumer, event_id),
  -- 访问索引，服务对应筛选/扫描路径：job_id
  KEY ix_inbox_job (job_id),
  FOREIGN KEY (job_id) REFERENCES job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息消费去重与持久接管凭据；不代表业务已完成';
