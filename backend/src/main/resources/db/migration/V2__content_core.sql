-- V2 内容核心：owner / item / content_revision / job / outbox / idempotency_request
-- 由 reports/V02-T01/<runId>/tools/gen_migration.py 从 contracts/mysql-schema.sql 抽取，
-- 与 docs/16 的 DDL 基线保持逐字一致；修改列必须先改契约再重新生成。
-- 迁移只增不改：V1 已应用，本文件后续只能通过 V3+ 前滚修复。

CREATE TABLE owner (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '账户不透明ID；首版单管理员仍必须做归属校验',
  username VARCHAR(64) NOT NULL COMMENT '规范化账户名；唯一用于登录',
  password_hash VARCHAR(255) CHARACTER SET ascii NOT NULL COMMENT '密码安全哈希；禁止存明文密码',
  timezone VARCHAR(64) CHARACTER SET ascii NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '用户IANA时区，用于展示与复习日统计',
  ai_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否启用外部模型能力；关闭不影响基础内容和手动学习',
  daily_budget_limit DECIMAL(18,8) NOT NULL DEFAULT 0 COMMENT '每日内部模型预算上限，金额单位由budget_currency确定',
  budget_currency CHAR(3) CHARACTER SET ascii NOT NULL DEFAULT 'USD' COMMENT '预算金额币种；一个预算日不可混用币种',
  model_profile_ref VARCHAR(128) CHARACTER SET ascii NULL COMMENT '受控模型配置引用；不存供应商明文Key',
  memory_policy_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '记忆或授权变化代次；与call授权串行化检查',
  settings_version BIGINT NOT NULL DEFAULT 0 COMMENT '设置乐观并发版本，防止旧配置覆盖',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  updated_at DATETIME(6) NOT NULL COMMENT '最近修改时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：username
  UNIQUE KEY uk_owner_username (username),
  CHECK (memory_policy_epoch >= 0 AND settings_version >= 0),
  CHECK (daily_budget_limit >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账户与设置权威；首版单管理员，保存记忆授权代次';

CREATE TABLE item (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  kind VARCHAR(24) CHARACTER SET ascii NOT NULL COMMENT '业务类型枚举；具体允许值由本表契约校验',
  title VARCHAR(512) NOT NULL COMMENT '用户可见标题，不作为唯一身份',
  original_url TEXT NULL COMMENT '原始来源URL；保留原值便于追溯',
  normalized_url TEXT NULL COMMENT '按保守规则规范化后的URL，hash碰撞时比较原值',
  url_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '规范URL的SHA-256；无URL为NULL，owner内唯一',
  current_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最新可读正文版本指针；可领先于可搜索版本',
  active_generation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '唯一已发布索引generation指针；发布事务CAS切换',
  processing_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '用户处理请求代次；新重抓递增，旧请求结果不得发布',
  metadata_version BIGINT NOT NULL DEFAULT 0 COMMENT '标签等可变元数据投影版本；用于索引对账',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  read_status VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'UNREAD' COMMENT 'UNREAD/READING/READ阅读状态',
  deleted_at DATETIME(6) NULL COMMENT '软删除时间，UTC；不自动物理删除原件或解除唯一约束',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  updated_at DATETIME(6) NOT NULL COMMENT '最近修改时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_item_owner_id (owner_id, id),
  -- 唯一约束，数据库拒绝重复组合：owner_id, url_hash
  UNIQUE KEY uk_item_url (owner_id, url_hash),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, deleted_at, created_at, id
  KEY ix_item_library (owner_id, deleted_at, created_at, id),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, kind, deleted_at, created_at, id
  KEY ix_item_kind (owner_id, kind, deleted_at, created_at, id),
  FOREIGN KEY (owner_id) REFERENCES owner(id),
  CHECK (kind IN ('WEB','TEXT','PDF','VIDEO','AUDIO','TRANSCRIPT')),
  CHECK (read_status IN ('UNREAD','READING','READ')),
  CHECK (processing_epoch >= 0 AND metadata_version >= 0 AND row_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可变内容入口；最新正文与已发布索引指针分离';

CREATE TABLE content_revision (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属条目ID；与owner及revision必须组成同一归属链',
  revision_no BIGINT NOT NULL COMMENT '条目内单调增长正文版本号；从1开始',
  source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本次输入内容SHA-256，用于追溯与去重',
  extractor_version VARCHAR(64) CHARACTER SET ascii NOT NULL COMMENT '提取器实现版本，影响正文结果',
  chunker_version VARCHAR(64) CHARACTER SET ascii NOT NULL COMMENT '分段器及规则版本，影响片段身份',
  manifest_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化manifest的SHA-256',
  manifest_json JSON NOT NULL COMMENT '严格schema的不可变产物清单，包含hash大小和来源',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：item_id, revision_no
  UNIQUE KEY uk_revision_number (item_id, revision_no),
  -- 唯一约束，数据库拒绝重复组合：owner_id, item_id, id
  UNIQUE KEY uk_revision_scope (owner_id, item_id, id),
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_revision_owner (owner_id, id),
  FOREIGN KEY (owner_id, item_id) REFERENCES item(owner_id, id),
  CHECK (revision_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变正文版本；同条目revision_no唯一';

CREATE TABLE job (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '所属条目ID；与owner及revision必须组成同一归属链',
  input_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '任务读取的固定正文版本；无正文输入时可空',
  kind VARCHAR(24) CHARACTER SET ascii NOT NULL COMMENT 'INGEST/ENRICH/INDEX/ASSIST/EMBED/EXPORT等注册任务类型',
  state VARCHAR(24) CHARACTER SET ascii NOT NULL COMMENT '本表业务状态枚举；合法转移还须由应用层验证',
  processing_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '用户处理请求代次；新重抓递增，旧请求结果不得发布',
  attempt INT NOT NULL DEFAULT 1 COMMENT '实际执行尝试序号；租约接管不递增',
  run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Runner稳定运行ID；同一次执行的接管和对账复用',
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化请求或事件参数SHA-256，用于防篡改与幂等冲突检测',
  input_json JSON NOT NULL COMMENT '严格schema的任务输入引用；禁止存凭据',
  lease_owner VARCHAR(128) CHARACTER SET ascii NULL COMMENT '当前执行租约持有者标识，不等于用户身份',
  lease_token BIGINT NOT NULL DEFAULT 0 COMMENT '执行租约代次；接管递增，旧worker提交必须被拒绝',
  lease_until DATETIME(6) NULL COMMENT '执行租约期限，UTC；提交时仍须检查是否有效',
  next_attempt_at DATETIME(6) NULL COMMENT '允许下一次确定性重试的最早UTC时间',
  next_reconcile_at DATETIME(6) NULL COMMENT '下次持久任务派发或状态对账UTC时间',
  cancel_requested_at DATETIME(6) NULL COMMENT '用户请求取消时间，UTC；与完成竞争需事务校验',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  last_event_seq BIGINT NOT NULL DEFAULT 0 COMMENT '已持久分配的job事件最大序号',
  error_code VARCHAR(64) CHARACTER SET ascii NULL COMMENT '稳定错误码，不用中文message参与程序判断',
  error_message VARCHAR(512) NULL COMMENT '可安全显示的错误摘要；不含堆栈SQL或秘密',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  updated_at DATETIME(6) NOT NULL COMMENT '最近修改时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：run_id
  UNIQUE KEY uk_job_run (run_id),
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_job_owner (owner_id, id),
  -- 访问索引，服务对应筛选/扫描路径：state, next_attempt_at, id
  KEY ix_job_retry (state, next_attempt_at, id),
  -- 访问索引，服务对应筛选/扫描路径：state, lease_until, id
  KEY ix_job_lease (state, lease_until, id),
  -- 访问索引，服务对应筛选/扫描路径：state, next_reconcile_at, id
  KEY ix_job_reconcile (state, next_reconcile_at, id),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, item_id, created_at, id
  KEY ix_job_item (owner_id, item_id, created_at, id),
  FOREIGN KEY (owner_id) REFERENCES owner(id),
  FOREIGN KEY (owner_id, item_id) REFERENCES item(owner_id, id),
  FOREIGN KEY (owner_id, item_id, input_revision_id) REFERENCES content_revision(owner_id, item_id, id),
  CHECK (input_revision_id IS NULL OR item_id IS NOT NULL),
  CHECK (attempt > 0 AND lease_token >= 0 AND processing_epoch >= 0),
  CHECK (state IN ('QUEUED','RUNNING','RETRY_WAIT','NEEDS_DECISION','SUCCEEDED','FAILED','CANCELLED','SUPERSEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Java业务任务权威；持久接管、重试与租约执行';

CREATE TABLE outbox (
  event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '全局唯一事件ID；重投必须复用',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  job_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Java持久业务任务ID',
  event_type VARCHAR(64) CHARACTER SET ascii NOT NULL COMMENT '版本化事件类型；未知类型按兼容协议处理',
  wake_generation BIGINT NOT NULL DEFAULT 0 COMMENT '同任务唤醒通知代次，不等于worker leaseToken',
  payload_json JSON NOT NULL COMMENT '版本化事件或动作载荷；严格schema与体积限制',
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化请求或事件参数SHA-256，用于防篡改与幂等冲突检测',
  state VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'PENDING' COMMENT '本表业务状态枚举；合法转移还须由应用层验证',
  available_at DATETIME(6) NOT NULL COMMENT '消息允许投递的最早UTC时间',
  relay_owner VARCHAR(128) CHARACTER SET ascii NULL COMMENT 'Outbox投递器领取者身份，与业务worker分离',
  relay_token BIGINT NOT NULL DEFAULT 0 COMMENT '投递器租约代次，防过期relay覆盖发送状态',
  relay_lease_until DATETIME(6) NULL COMMENT '投递器领取租约期限，UTC',
  attempt INT NOT NULL DEFAULT 0 COMMENT '消息投递尝试次数，从0开始；不等于job执行attempt',
  confirmed_at DATETIME(6) NULL COMMENT '投递收到ack且无unroutable return后的确认UTC时间',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 访问索引，服务对应筛选/扫描路径：state, available_at, event_id
  KEY ix_outbox_ready (state, available_at, event_id),
  -- 访问索引，服务对应筛选/扫描路径：state, relay_lease_until, event_id
  KEY ix_outbox_lease (state, relay_lease_until, event_id),
  FOREIGN KEY (owner_id, job_id) REFERENCES job(owner_id, id),
  CHECK (state IN ('PENDING','PUBLISHING','SENT','DEAD')),
  CHECK (attempt >= 0 AND relay_token >= 0 AND wake_generation >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='与业务同事务的待投递通知；至少一次投递';

CREATE TABLE idempotency_request (
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  route_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化命令路由身份，不包含用户任意URL',
  request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Idempotency-Key原始受限值；owner/route内唯一',
  body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化命令体SHA-256，用于同key异内容冲突',
  state VARCHAR(16) CHARACTER SET ascii NOT NULL COMMENT '本表业务状态枚举；合法转移还须由应用层验证',
  response_status INT NULL COMMENT '原始HTTP状态；处理中可空',
  response_json JSON NULL COMMENT '原始业务响应快照，用于重复请求返回同一结果',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  expires_at DATETIME(6) NULL COMMENT '到期时间，UTC；到期不自动代表未知副作用可重试',
  PRIMARY KEY (owner_id, route_key, request_key),
  FOREIGN KEY (owner_id) REFERENCES owner(id),
  CHECK (state IN ('PROCESSING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公开命令幂等键与原始结果快照；与业务同事务';
