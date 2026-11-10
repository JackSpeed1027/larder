-- S05：任务事件日志（SSE 可恢复游标的事实源）。
-- 只增不改：V1–V6 已应用。
CREATE TABLE job_event (
  owner_id     VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属 owner',
  job_id       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务 ID',
  seq          BIGINT       NOT NULL COMMENT '任务内单调序号（来自 job.last_event_seq）',
  event_type   VARCHAR(32)  NOT NULL COMMENT 'CREATED/RUNNING/SUCCEEDED/FAILED/CANCELLED 等',
  payload_json JSON         NOT NULL COMMENT '事件快照（state/runId/epoch/errorCode）',
  created_at   DATETIME(6)  NOT NULL COMMENT '写入时间（UTC）',
  PRIMARY KEY (owner_id, job_id, seq),
  KEY idx_job_event_created (created_at),
  CONSTRAINT fk_job_event_job FOREIGN KEY (job_id) REFERENCES job (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务事件日志：SSE 断线续传与审计';
