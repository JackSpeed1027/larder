-- Larder MySQL 8.4 schema design baseline; NOT an applied Flyway migration.
-- Run only in a new isolated validation database after review. No DROP/TRUNCATE/DELETE.
-- Exact Flyway version allocation belongs to the Coordinator. UTC connection required.
-- IDs are opaque ASCII strings <=64 chars; application selects one generator in V01.
-- JSON fields have typed API/schema validation; they are not unvalidated business blobs.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

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

CREATE TABLE app_session (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会话token的SHA-256摘要；原始token不入库',
  csrf_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'CSRF秘密的摘要；原始值不入日志',
  expires_at DATETIME(6) NOT NULL COMMENT '到期时间，UTC；到期不自动代表未知副作用可重试',
  revoked_at DATETIME(6) NULL COMMENT '撤销时间，UTC；NULL表示未撤销',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：token_hash
  UNIQUE KEY uk_session_token (token_hash),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, expires_at
  KEY ix_session_owner (owner_id, expires_at),
  FOREIGN KEY (owner_id) REFERENCES owner(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话凭据摘要与有效期；不保存原始Cookie或CSRF秘密';

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

CREATE TABLE artifact (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  bucket VARCHAR(63) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受控S3 bucket名',
  object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务器生成的不可变对象key；禁止用户任意路径',
  object_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '对象存储返回的版本ID；未启用版本时可空',
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '真实内容SHA-256；不能以multipart ETag替代',
  bytes BIGINT NOT NULL COMMENT '对象实际字节数，非字符数',
  mime VARCHAR(128) CHARACTER SET ascii NOT NULL COMMENT '验证后的媒体类型',
  state VARCHAR(24) CHARACTER SET ascii NOT NULL COMMENT 'STAGED/VERIFIED/QUARANTINED对象校验状态',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：bucket, object_key
  UNIQUE KEY uk_artifact_object (bucket, object_key),
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_artifact_owner (owner_id, id),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, sha256
  KEY ix_artifact_hash (owner_id, sha256),
  FOREIGN KEY (owner_id) REFERENCES owner(id),
  CHECK (bytes >= 0),
  CHECK (state IN ('STAGED','VERIFIED','QUARANTINED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已接收对象元数据；内容不可变，hash校验后才可引用';

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

CREATE TABLE revision_artifact (
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定正文版本ID；不能自动切为最新版本',
  artifact_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已校验的不可变对象元数据ID',
  role VARCHAR(24) CHARACTER SET ascii NOT NULL COMMENT 'ORIGINAL/BODY/TRANSCRIPT等版本化对象角色',
  ordinal INT NOT NULL DEFAULT 0 COMMENT '版本或角色内从0开始的稳定序号',
  PRIMARY KEY (revision_id, role, ordinal),
  FOREIGN KEY (owner_id, revision_id) REFERENCES content_revision(owner_id, id),
  FOREIGN KEY (owner_id, artifact_id) REFERENCES artifact(owner_id, id),
  CHECK (ordinal >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正文版本与不可变对象的有序角色绑定';

CREATE TABLE chunk (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属条目ID；与owner及revision必须组成同一归属链',
  revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定正文版本ID；不能自动切为最新版本',
  ordinal INT NOT NULL COMMENT '版本或角色内从0开始的稳定序号',
  text_content MEDIUMTEXT NOT NULL COMMENT '该片段正文；受长度上限约束且不可变',
  text_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '片段文本SHA-256',
  page_no INT NULL COMMENT '来源PDF页码，从1开始；非PDF可空',
  start_ms BIGINT NULL COMMENT '来源媒体起始毫秒；无时间定位时可空',
  end_ms BIGINT NULL COMMENT '来源媒体结束毫秒，不小于start_ms',
  anchor_json JSON NOT NULL COMMENT '版本化定位锚点；按输入类型schema校验',
  -- 唯一约束，数据库拒绝重复组合：revision_id, ordinal
  UNIQUE KEY uk_chunk_ordinal (revision_id, ordinal),
  -- 唯一约束，数据库拒绝重复组合：owner_id, item_id, revision_id, id
  UNIQUE KEY uk_chunk_scope (owner_id, item_id, revision_id, id),
  FOREIGN KEY (owner_id, item_id, revision_id) REFERENCES content_revision(owner_id, item_id, id),
  CHECK (ordinal >= 0),
  CHECK (page_no IS NULL OR page_no > 0),
  CHECK ((start_ms IS NULL AND end_ms IS NULL) OR (start_ms IS NOT NULL AND end_ms IS NOT NULL AND start_ms >= 0 AND end_ms >= start_ms))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变版本内片段与页码时间锚点';

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

CREATE TABLE job_event (
  job_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Java持久业务任务ID',
  seq BIGINT NOT NULL COMMENT '所属job或run内单调递增事件序号，从1开始',
  event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全局唯一事件ID；重投必须复用',
  event_type VARCHAR(64) CHARACTER SET ascii NOT NULL COMMENT '版本化事件类型；未知类型按兼容协议处理',
  payload_json JSON NOT NULL COMMENT '版本化事件或动作载荷；严格schema与体积限制',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  PRIMARY KEY (job_id, seq),
  -- 唯一约束，数据库拒绝重复组合：event_id
  UNIQUE KEY uk_job_event_id (event_id),
  FOREIGN KEY (job_id) REFERENCES job(id),
  CHECK (seq > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务状态的有序公开事件；与状态变更同事务';

CREATE TABLE run_receipt (
  run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Runner稳定运行ID；同一次执行的接管和对账复用',
  seq BIGINT NOT NULL COMMENT '所属job或run内单调递增事件序号，从1开始',
  job_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Java持久业务任务ID',
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化请求或事件参数SHA-256，用于防篡改与幂等冲突检测',
  applied_at DATETIME(6) NOT NULL COMMENT '业务效果或事件已原子应用时间，UTC',
  PRIMARY KEY (run_id, seq),
  -- 访问索引，服务对应筛选/扫描路径：job_id, seq
  KEY ix_receipt_job (job_id, seq),
  FOREIGN KEY (job_id) REFERENCES job(id),
  CHECK (seq > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runner事件已应用记录；防重复副作用并检测同seq异hash';

CREATE TABLE index_generation (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属条目ID；与owner及revision必须组成同一归属链',
  revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定正文版本ID；不能自动切为最新版本',
  profile_id VARCHAR(64) CHARACTER SET ascii NOT NULL COMMENT '索引或向量配置版本身份，禁止混用不同模型空间',
  embedder_version VARCHAR(128) CHARACTER SET ascii NULL COMMENT '生成向量的模型与配置版本；纯关键词可空',
  vector_dimension INT NULL COMMENT '向量维度；纯关键词可空',
  schema_version VARCHAR(32) CHARACTER SET ascii NOT NULL COMMENT '索引/manifest等结构协议版本，不是内容revision',
  manifest_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化manifest的SHA-256',
  manifest_json JSON NOT NULL COMMENT '严格schema的不可变产物清单，包含hash大小和来源',
  expected_epoch BIGINT NOT NULL COMMENT '构建开始时条目processingEpoch，发布时复核',
  state VARCHAR(24) CHARACTER SET ascii NOT NULL COMMENT 'BUILDING/VALIDATING/READY/FAILED/SUPERSEDED；ACTIVE由item指针派生',
  expected_chunk_count INT NOT NULL COMMENT 'manifest期望片段数，发布前必须核验',
  validation_report_artifact_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '索引完整性验证报告的不可变对象引用',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  validated_at DATETIME(6) NULL COMMENT '候选索引完成校验时间，UTC',
  -- 唯一约束，数据库拒绝重复组合：owner_id, item_id, id
  UNIQUE KEY uk_generation_scope (owner_id, item_id, id),
  -- 访问索引，服务对应筛选/扫描路径：item_id, created_at, id
  KEY ix_generation_item (item_id, created_at, id),
  -- 访问索引，服务对应筛选/扫描路径：state, created_at, id
  KEY ix_generation_state (state, created_at, id),
  FOREIGN KEY (owner_id, item_id, revision_id) REFERENCES content_revision(owner_id, item_id, id),
  FOREIGN KEY (owner_id, validation_report_artifact_id) REFERENCES artifact(owner_id, id),
  CHECK (state IN ('BUILDING','VALIDATING','READY','FAILED','SUPERSEDED')),
  CHECK (expected_epoch >= 0 AND expected_chunk_count >= 0),
  CHECK (vector_dimension IS NULL OR vector_dimension > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='固定版本的索引构建记录；ACTIVE以item指针派生';

ALTER TABLE item ADD CONSTRAINT fk_item_current_revision
  FOREIGN KEY (owner_id, id, current_revision_id) REFERENCES content_revision(owner_id, item_id, id);
ALTER TABLE item ADD CONSTRAINT fk_item_active_generation
  FOREIGN KEY (owner_id, id, active_generation_id) REFERENCES index_generation(owner_id, item_id, id);

CREATE TABLE generation_task (
  generation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '条目索引构建版本ID',
  batch_no INT NOT NULL COMMENT 'generation内外部写入批次，从0开始',
  engine_task_id BIGINT NULL COMMENT '外部索引异步task ID；未收到时为NULL',
  state VARCHAR(24) CHARACTER SET ascii NOT NULL COMMENT '本表业务状态枚举；合法转移还须由应用层验证',
  batch_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '该批确定性输入SHA-256',
  last_checked_at DATETIME(6) NULL COMMENT '最近检查外部异步任务状态时间，UTC',
  PRIMARY KEY (generation_id, batch_no),
  -- 访问索引，服务对应筛选/扫描路径：state, last_checked_at
  KEY ix_generation_task_poll (state, last_checked_at),
  FOREIGN KEY (generation_id) REFERENCES index_generation(id),
  CHECK (batch_no >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='索引generation每批外部异步task与验证进度';

CREATE TABLE metadata_projection (
  item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属条目ID；与owner及revision必须组成同一归属链',
  profile_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '索引或向量配置版本身份，禁止混用不同模型空间',
  desired_version BIGINT NOT NULL COMMENT '期望写入外部索引的元数据版本',
  applied_version BIGINT NULL COMMENT '最近实际验证的外部版本；未验证为NULL',
  task_ids_json JSON NOT NULL COMMENT '版本化外部异步任务ID清单',
  next_check_at DATETIME(6) NOT NULL COMMENT '下次外部投影对账时间，UTC',
  last_error_code VARCHAR(64) CHARACTER SET ascii NULL COMMENT '最近对账失败的稳定错误码',
  PRIMARY KEY (item_id, profile_id),
  -- 访问索引，服务对应筛选/扫描路径：next_check_at, item_id
  KEY ix_projection_check (next_check_at, item_id),
  FOREIGN KEY (item_id) REFERENCES item(id),
  CHECK (desired_version >= 0 AND (applied_version IS NULL OR applied_version >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可重建索引元数据投影；期望/已观测版本对账';

CREATE TABLE tag (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  name VARCHAR(96) NOT NULL COMMENT '用户可见名称',
  normalized_name VARCHAR(96) COLLATE utf8mb4_bin NOT NULL COMMENT '按固定Unicode及空白大小写规则规范化名称',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：owner_id, normalized_name
  UNIQUE KEY uk_tag_name (owner_id, normalized_name),
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_tag_owner (owner_id, id),
  FOREIGN KEY (owner_id) REFERENCES owner(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户内标签定义与规范名唯一约束';

CREATE TABLE item_tag (
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属条目ID；与owner及revision必须组成同一归属链',
  tag_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同owner标签ID',
  PRIMARY KEY (item_id, tag_id),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, tag_id, item_id
  KEY ix_item_tag_reverse (owner_id, tag_id, item_id),
  FOREIGN KEY (owner_id, item_id) REFERENCES item(owner_id, id),
  FOREIGN KEY (owner_id, tag_id) REFERENCES tag(owner_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='条目与标签多对多关系，复合外键保证同owner';

CREATE TABLE collection (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  name VARCHAR(128) NOT NULL COMMENT '用户可见名称',
  normalized_name VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '按固定Unicode及空白大小写规则规范化名称',
  description VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '用户可见说明',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  archived_at DATETIME(6) NULL COMMENT '集合归档时间，UTC；不级联删除条目',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：owner_id, normalized_name
  UNIQUE KEY uk_collection_name (owner_id, normalized_name),
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_collection_owner (owner_id, id),
  FOREIGN KEY (owner_id) REFERENCES owner(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手动集合定义；条目可属于多个集合';

CREATE TABLE collection_item (
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  collection_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同owner手动集合ID',
  item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属条目ID；与owner及revision必须组成同一归属链',
  added_at DATETIME(6) NOT NULL COMMENT '关联建立时间，UTC',
  PRIMARY KEY (collection_id, item_id),
  -- 访问索引，服务对应筛选/扫描路径：item_id, collection_id
  KEY ix_collection_item_reverse (item_id, collection_id),
  FOREIGN KEY (owner_id, collection_id) REFERENCES collection(owner_id, id),
  FOREIGN KEY (owner_id, item_id) REFERENCES item(owner_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='集合与条目多对多关系';

CREATE TABLE note (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属条目ID；与owner及revision必须组成同一归属链',
  revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '固定正文版本ID；不能自动切为最新版本',
  chunk_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '固定revision内片段ID，须验证完整归属',
  anchor_json JSON NULL COMMENT '版本化定位锚点；按输入类型schema校验',
  markdown MEDIUMTEXT NOT NULL COMMENT '用户笔记Markdown；展示前安全渲染',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  updated_at DATETIME(6) NOT NULL COMMENT '最近修改时间，UTC微秒',
  -- 访问索引，服务对应筛选/扫描路径：owner_id, item_id, created_at, id
  KEY ix_note_item (owner_id, item_id, created_at, id),
  FOREIGN KEY (owner_id, item_id) REFERENCES item(owner_id, id),
  FOREIGN KEY (owner_id, item_id, revision_id) REFERENCES content_revision(owner_id, item_id, id),
  FOREIGN KEY (owner_id, item_id, revision_id, chunk_id) REFERENCES chunk(owner_id, item_id, revision_id, id),
  CHECK (chunk_id IS NULL OR revision_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户笔记与可选固定版本片段引用';

CREATE TABLE reading_position (
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属条目ID；与owner及revision必须组成同一归属链',
  revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定正文版本ID；不能自动切为最新版本',
  anchor_json JSON NOT NULL COMMENT '版本化定位锚点；按输入类型schema校验',
  progress DECIMAL(6,5) NOT NULL COMMENT '当前revision阅读进度比例，范围0至1',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  updated_at DATETIME(6) NOT NULL COMMENT '最近修改时间，UTC微秒',
  PRIMARY KEY (owner_id, item_id, revision_id),
  FOREIGN KEY (owner_id, item_id, revision_id) REFERENCES content_revision(owner_id, item_id, id),
  CHECK (progress >= 0 AND progress <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按不可变版本保存阅读位置，不静默迁移锚点';

CREATE TABLE card (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  type VARCHAR(24) CHARACTER SET ascii NOT NULL COMMENT '卡片类型：QA/CLOZE/RECITATION/TIMESTAMP',
  source_item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '来源条目，可空以支持手动无来源卡',
  source_revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '固定来源正文版本，可空；有值必须带source_item_id',
  source_chunk_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '固定来源片段，可空；有值必须带source_revision_id',
  front_text MEDIUMTEXT NOT NULL COMMENT '卡片正面问题或提示文本',
  back_text MEDIUMTEXT NOT NULL COMMENT '卡片答案或核对文本',
  type_payload_json JSON NOT NULL COMMENT '卡型专有字段，如cloze标记/背诵rubric/时间区间；严格schema',
  source_anchor_json JSON NULL COMMENT '来源定位锚点；来源失效不删除用户卡片',
  state VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'ACTIVE' COMMENT '本表业务状态枚举；合法转移还须由应用层验证',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  updated_at DATETIME(6) NOT NULL COMMENT '最近修改时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_card_owner (owner_id, id),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, state, type, created_at, id
  KEY ix_card_list (owner_id, state, type, created_at, id),
  FOREIGN KEY (owner_id) REFERENCES owner(id),
  FOREIGN KEY (owner_id, source_item_id) REFERENCES item(owner_id, id),
  FOREIGN KEY (owner_id, source_item_id, source_revision_id) REFERENCES content_revision(owner_id, item_id, id),
  FOREIGN KEY (owner_id, source_item_id, source_revision_id, source_chunk_id) REFERENCES chunk(owner_id, item_id, revision_id, id),
  CHECK (type IN ('QA','CLOZE','RECITATION','TIMESTAMP')),
  CHECK (state IN ('ACTIVE','SUSPENDED','ARCHIVED')),
  CHECK (source_revision_id IS NULL OR source_item_id IS NOT NULL),
  CHECK (source_chunk_id IS NULL OR source_revision_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习卡内容与来源；不承担复习状态权威';

CREATE TABLE review_state (
  card_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '学习卡ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  repetitions INT NOT NULL DEFAULT 0 COMMENT '当前连续成功复习次数，失败按算法重置',
  interval_days INT NOT NULL DEFAULT 0 COMMENT '当前调度间隔天数，由固定版本算法计算',
  ease_factor DECIMAL(5,3) NOT NULL DEFAULT 2.500 COMMENT '复习难度因子，最低1.3；不使用浮点存储',
  lapses INT NOT NULL DEFAULT 0 COMMENT '累计失败次数，用于leech判断',
  is_leech BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否达到本版leech规则；不自动删除卡片',
  due_at DATETIME(6) NOT NULL COMMENT '下次复习到期时间，UTC，由服务器计算',
  last_reviewed_at DATETIME(6) NULL COMMENT '上次成功提交评分时间，UTC',
  scheduler_version VARCHAR(64) CHARACTER SET ascii NOT NULL COMMENT '调度算法版本，历史结果必须可解释',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  -- 访问索引，服务对应筛选/扫描路径：owner_id, due_at, card_id
  KEY ix_review_due (owner_id, due_at, card_id),
  FOREIGN KEY (owner_id, card_id) REFERENCES card(owner_id, id),
  CHECK (repetitions >= 0 AND interval_days >= 0 AND lapses >= 0),
  CHECK (ease_factor >= 1.300)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡片调度权威；评分版本与到期时间';

CREATE TABLE card_tag (
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  card_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '学习卡ID',
  tag_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同owner标签ID',
  PRIMARY KEY (card_id, tag_id),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, tag_id, card_id
  KEY ix_card_tag_reverse (owner_id, tag_id, card_id),
  FOREIGN KEY (owner_id, card_id) REFERENCES card(owner_id, id),
  FOREIGN KEY (owner_id, tag_id) REFERENCES tag(owner_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卡片独立标签关联，不强制继承原文标签';

CREATE TABLE review_log (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  card_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '学习卡ID',
  request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端一次命令的稳定幂等ID；重发复用',
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整规范化命令SHA-256，同ID异内容409',
  grade TINYINT NOT NULL COMMENT '评分q值，仅0/3/4/5，对应重来/困难/良好/轻松',
  scheduler_version VARCHAR(64) CHARACTER SET ascii NOT NULL COMMENT '调度算法版本，历史结果必须可解释',
  before_state_json JSON NOT NULL COMMENT '评分前状态快照，严格schema',
  after_state_json JSON NOT NULL COMMENT '评分后状态快照，严格schema',
  response_json JSON NOT NULL COMMENT '原始业务响应快照，用于重复请求返回同一结果',
  reviewed_at DATETIME(6) NOT NULL COMMENT '评分生效UTC时间',
  local_review_date DATE NOT NULL COMMENT '按本次记录时区计算的本地统计日期，不随以后时区改变',
  timezone VARCHAR(64) CHARACTER SET ascii NOT NULL COMMENT '用户IANA时区，用于展示与复习日统计',
  -- 唯一约束，数据库拒绝重复组合：card_id, request_id
  UNIQUE KEY uk_review_request (card_id, request_id),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, local_review_date, card_id
  KEY ix_review_stats (owner_id, local_review_date, card_id),
  FOREIGN KEY (owner_id, card_id) REFERENCES card(owner_id, id),
  CHECK (grade IN (0,3,4,5))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变评分事件及请求结果快照，重复请求返回原结果';

CREATE TABLE assistant_session (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  title VARCHAR(256) NOT NULL COMMENT '用户可见标题，不作为唯一身份',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  updated_at DATETIME(6) NOT NULL COMMENT '最近修改时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_assistant_session_owner (owner_id, id),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, updated_at, id
  KEY ix_assistant_session_list (owner_id, updated_at, id),
  FOREIGN KEY (owner_id) REFERENCES owner(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户可见Agent会话元数据，不替代Runner日志';

CREATE TABLE run_scope (
  run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT 'Runner稳定运行ID；同一次执行的接管和对账复用',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  job_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Java持久业务任务ID',
  session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '同owner的Agent会话ID，不等于登录session',
  scope_kind VARCHAR(24) CHARACTER SET ascii NOT NULL COMMENT 'EXPLICIT_ITEMS/LIBRARY等已注册授权范围，仍逐次复核权限',
  allowed_tools_json JSON NOT NULL COMMENT '本run允许的注册工具及权限清单',
  policy_epoch BIGINT NOT NULL COMMENT '授权时owner权限/记忆策略代次快照',
  expires_at DATETIME(6) NOT NULL COMMENT '到期时间，UTC；到期不自动代表未知副作用可重试',
  revoked_at DATETIME(6) NULL COMMENT '撤销时间，UTC；NULL表示未撤销',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：owner_id, run_id
  UNIQUE KEY uk_run_scope_owner (owner_id, run_id),
  -- 唯一约束，数据库拒绝重复组合：owner_id, session_id, run_id
  UNIQUE KEY uk_run_scope_session_owner (owner_id, session_id, run_id),
  -- 访问索引，服务对应筛选/扫描路径：session_id, created_at, run_id
  KEY ix_run_scope_session (session_id, created_at, run_id),
  FOREIGN KEY (owner_id, job_id) REFERENCES job(owner_id, id),
  FOREIGN KEY (owner_id, session_id) REFERENCES assistant_session(owner_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Java持久run授权范围，所有工具和模型call均需复核';

CREATE TABLE run_scope_item (
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Runner稳定运行ID；同一次执行的接管和对账复用',
  item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属条目ID；与owner及revision必须组成同一归属链',
  revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '固定正文版本ID；不能自动切为最新版本',
  PRIMARY KEY (run_id, item_id),
  FOREIGN KEY (owner_id, run_id) REFERENCES run_scope(owner_id, run_id),
  FOREIGN KEY (owner_id, item_id) REFERENCES item(owner_id, id),
  FOREIGN KEY (owner_id, item_id, revision_id) REFERENCES content_revision(owner_id, item_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='run可访问条目及可选固定revision白名单';

CREATE TABLE assistant_message (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同owner的Agent会话ID，不等于登录session',
  run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Runner稳定运行ID；同一次执行的接管和对账复用',
  message_no BIGINT NOT NULL COMMENT '会话内可展示消息序号',
  role VARCHAR(16) CHARACTER SET ascii NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM_NOTICE，仅可公开展示消息角色',
  content_markdown MEDIUMTEXT NOT NULL COMMENT '用户消息或公开回答内容，不保存隐藏思维',
  citation_refs_json JSON NOT NULL COMMENT '固定revision/chunk的公开引用清单',
  source_seq BIGINT NULL COMMENT '由Runner提交事件生成投影时的原始seq；用户输入可空',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：session_id, message_no
  UNIQUE KEY uk_assistant_message_seq (session_id, message_no),
  -- 唯一约束，数据库拒绝重复组合：run_id, source_seq
  UNIQUE KEY uk_assistant_run_source (run_id, source_seq),
  FOREIGN KEY (owner_id, session_id) REFERENCES assistant_session(owner_id, id),
  FOREIGN KEY (owner_id, session_id, run_id) REFERENCES run_scope(owner_id, session_id, run_id),
  CHECK (role IN ('USER','ASSISTANT','SYSTEM_NOTICE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话展示投影；执行事实仍以Runner JSONL为准';

CREATE TABLE proposal (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Runner稳定运行ID；同一次执行的接管和对账复用',
  action_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一次业务效果的全局幂等ID，不等于tool callId',
  action_type VARCHAR(64) CHARACTER SET ascii NOT NULL COMMENT '已注册允许的业务写动作类型',
  payload_json JSON NOT NULL COMMENT '版本化事件或动作载荷；严格schema与体积限制',
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化请求或事件参数SHA-256，用于防篡改与幂等冲突检测',
  target_versions_json JSON NOT NULL COMMENT '拟修改目标的预期版本集合；批准及应用均复核',
  proposal_version BIGINT NOT NULL DEFAULT 1 COMMENT '不可变提议版本；修改参数需要新提议',
  expires_at DATETIME(6) NOT NULL COMMENT '到期时间，UTC；到期不自动代表未知副作用可重试',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：action_id
  UNIQUE KEY uk_proposal_action (action_id),
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_proposal_owner (owner_id, id),
  -- 唯一约束，数据库拒绝重复组合：owner_id, id, action_id, payload_hash
  UNIQUE KEY uk_proposal_bound_action (owner_id, id, action_id, payload_hash),
  FOREIGN KEY (owner_id, run_id) REFERENCES run_scope(owner_id, run_id),
  CHECK (proposal_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变待批准动作参数及目标版本';

CREATE TABLE approval (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  proposal_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定的不可变动作提议ID',
  state VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'PENDING' COMMENT '本表业务状态枚举；合法转移还须由应用层验证',
  approved_payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '用户批准时绑定的参数hash',
  approved_proposal_version BIGINT NULL COMMENT '用户批准时绑定的提议版本',
  decided_at DATETIME(6) NULL COMMENT '用户批准/拒绝时间，UTC',
  applied_at DATETIME(6) NULL COMMENT '业务效果或事件已原子应用时间，UTC',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  -- 唯一约束，数据库拒绝重复组合：proposal_id
  UNIQUE KEY uk_approval_proposal (proposal_id),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, state, id
  KEY ix_approval_owner_state (owner_id, state, id),
  FOREIGN KEY (owner_id, proposal_id) REFERENCES proposal(owner_id, id),
  CHECK (state IN ('PENDING','APPROVED','APPLIED','REJECTED','EXPIRED','STALE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户批准状态及参数绑定；与实际效果原子提交';

CREATE TABLE action_result (
  action_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '一次业务效果的全局幂等ID，不等于tool callId',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  proposal_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定的不可变动作提议ID',
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化请求或事件参数SHA-256，用于防篡改与幂等冲突检测',
  response_json JSON NOT NULL COMMENT '原始业务响应快照，用于重复请求返回同一结果',
  applied_at DATETIME(6) NOT NULL COMMENT '业务效果或事件已原子应用时间，UTC',
  -- 唯一约束，数据库拒绝重复组合：proposal_id
  UNIQUE KEY uk_action_proposal (proposal_id),
  FOREIGN KEY (owner_id, proposal_id, action_id, payload_hash) REFERENCES proposal(owner_id, id, action_id, payload_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='actionId唯一业务效果回执；响应丢失时可查原结果';

CREATE TABLE memory_slot (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  scope_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'GLOBAL/COLLECTION/SESSION等记忆范围类型，具体值由R3冻结',
  scope_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '范围资源ID；GLOBAL使用固定保留值，不使用NULL绕过唯一键',
  normalized_key VARCHAR(191) COLLATE utf8mb4_bin NOT NULL COMMENT '记忆语义槽位key，规范化后owner/scope内唯一',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：owner_id, scope_kind, scope_id, normalized_key
  UNIQUE KEY uk_memory_slot (owner_id, scope_kind, scope_id, normalized_key),
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_memory_slot_owner (owner_id, id),
  FOREIGN KEY (owner_id) REFERENCES owner(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同owner/scope/key记忆槽位；并发替代先锁槽位';

CREATE TABLE memory (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  slot_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '记忆语义槽位ID；并发更新先锁槽位',
  current_version BIGINT NOT NULL DEFAULT 0 COMMENT '当前记忆版本指针；初始化0不得作为有效记忆注入',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：slot_id
  UNIQUE KEY uk_memory_slot_identity (slot_id),
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_memory_owner (owner_id, id),
  FOREIGN KEY (owner_id, slot_id) REFERENCES memory_slot(owner_id, id),
  CHECK (current_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆稳定身份与当前版本指针';

CREATE TABLE memory_version (
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  memory_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定逻辑记忆ID',
  version BIGINT NOT NULL COMMENT '该记忆内容版本号，从1开始',
  state VARCHAR(16) CHARACTER SET ascii NOT NULL COMMENT '本表业务状态枚举；合法转移还须由应用层验证',
  value_text MEDIUMTEXT NOT NULL COMMENT '用户明确确认的结构化记忆内容',
  value_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '记忆内容SHA-256',
  source_json JSON NOT NULL COMMENT '记忆来源与用户确认依据，严格schema',
  supersedes_version BIGINT NULL COMMENT '本版本替代的旧版本号，可空',
  confirmed_at DATETIME(6) NOT NULL COMMENT '用户明确确认本记忆版本的UTC时间',
  revoked_at DATETIME(6) NULL COMMENT '撤销时间，UTC；NULL表示未撤销',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN state = 'ACTIVE' THEN 1 ELSE NULL END) STORED COMMENT '生成列：ACTIVE为1，其余NULL；唯一索引保证最多一个活动版本',
  PRIMARY KEY (memory_id, version),
  -- 唯一约束，数据库拒绝重复组合：memory_id, active_marker
  UNIQUE KEY uk_memory_active (memory_id, active_marker),
  -- 唯一约束，数据库拒绝重复组合：owner_id, memory_id, version
  UNIQUE KEY uk_memory_version_owner (owner_id, memory_id, version),
  FOREIGN KEY (owner_id, memory_id) REFERENCES memory(owner_id, id),
  CHECK (version > 0),
  CHECK (state IN ('ACTIVE','SUPERSEDED','TOMBSTONED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆内容版本与生命周期；每memory最多一个ACTIVE';

CREATE TABLE budget_day (
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  budget_date DATE NOT NULL COMMENT 'UTC预算周期日期；重试和结算保留原周期',
  currency CHAR(3) CHARACTER SET ascii NOT NULL DEFAULT 'USD' COMMENT '预算币种，一个周期内固定',
  limit_amount DECIMAL(18,8) NOT NULL COMMENT '本周期内部预算上限，DECIMAL金额',
  reserved_amount DECIMAL(18,8) NOT NULL DEFAULT 0 COMMENT '尚未结算的预留金额；UNKNOWN仍需保留/对账',
  used_amount DECIMAL(18,8) NOT NULL DEFAULT 0 COMMENT '已采纳累计用量金额，不重复累加同一usage版本',
  row_version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本；条件更新成功后递增',
  PRIMARY KEY (owner_id, budget_date),
  FOREIGN KEY (owner_id) REFERENCES owner(id),
  CHECK (limit_amount >= 0 AND reserved_amount >= 0 AND used_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='UTC预算日原子额度与累计用量';

CREATE TABLE reservation (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  budget_date DATE NOT NULL COMMENT 'UTC预算周期日期；重试和结算保留原周期',
  run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Runner稳定运行ID；同一次执行的接管和对账复用',
  call_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '单次模型请求身份；不同实际重试使用新callId',
  reserved_amount DECIMAL(18,8) NOT NULL COMMENT '尚未结算的预留金额；UNKNOWN仍需保留/对账',
  settled_amount DECIMAL(18,8) NULL COMMENT '当前已采纳的该call累计费用；未知可空',
  state VARCHAR(16) CHARACTER SET ascii NOT NULL COMMENT '本表业务状态枚举；合法转移还须由应用层验证',
  expires_at DATETIME(6) NOT NULL COMMENT '到期时间，UTC；到期不自动代表未知副作用可重试',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  updated_at DATETIME(6) NOT NULL COMMENT '最近修改时间，UTC微秒',
  -- 唯一约束，数据库拒绝重复组合：call_id
  UNIQUE KEY uk_reservation_call (call_id),
  -- 唯一约束，数据库拒绝重复组合：owner_id, id
  UNIQUE KEY uk_reservation_owner (owner_id, id),
  -- 唯一约束，数据库拒绝重复组合：owner_id, run_id, call_id, id
  UNIQUE KEY uk_reservation_bound_call (owner_id, run_id, call_id, id),
  -- 访问索引，服务对应筛选/扫描路径：state, expires_at, id
  KEY ix_reservation_reconcile (state, expires_at, id),
  FOREIGN KEY (owner_id, budget_date) REFERENCES budget_day(owner_id, budget_date),
  FOREIGN KEY (owner_id, run_id) REFERENCES run_scope(owner_id, run_id),
  CHECK (reserved_amount >= 0 AND (settled_amount IS NULL OR settled_amount >= 0)),
  CHECK (state IN ('RESERVED','SETTLED','RELEASED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单call预算预留及结算；未知外部结果不得自动释放';

CREATE TABLE model_call_grant (
  call_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '单次模型请求身份；不同实际重试使用新callId',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Runner稳定运行ID；同一次执行的接管和对账复用',
  reservation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本call绑定的唯一预算预留ID',
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整规范化命令SHA-256，同ID异内容409',
  policy_epoch BIGINT NOT NULL COMMENT '授权时owner权限/记忆策略代次快照',
  authorized_at DATETIME(6) NOT NULL COMMENT '模型请求授权事务生效时间，撤销竞争线性化点',
  expires_at DATETIME(6) NOT NULL COMMENT '到期时间，UTC；到期不自动代表未知副作用可重试',
  -- 唯一约束，数据库拒绝重复组合：owner_id, call_id
  UNIQUE KEY uk_grant_owner (owner_id, call_id),
  -- 唯一约束，数据库拒绝重复组合：reservation_id
  UNIQUE KEY uk_grant_reservation (reservation_id),
  FOREIGN KEY (owner_id, run_id) REFERENCES run_scope(owner_id, run_id),
  FOREIGN KEY (owner_id, run_id, call_id, reservation_id) REFERENCES reservation(owner_id, run_id, call_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单次模型请求的权威授权快照；绑定请求hash和预留';

CREATE TABLE grant_memory (
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  call_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '单次模型请求身份；不同实际重试使用新callId',
  memory_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定逻辑记忆ID',
  memory_version BIGINT NOT NULL COMMENT '本call授权时确认有效的记忆版本',
  PRIMARY KEY (call_id, memory_id),
  FOREIGN KEY (owner_id, call_id) REFERENCES model_call_grant(owner_id, call_id),
  FOREIGN KEY (owner_id, memory_id, memory_version) REFERENCES memory_version(owner_id, memory_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型call授权时确认有效的记忆版本清单';

CREATE TABLE grant_evidence (
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  call_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '单次模型请求身份；不同实际重试使用新callId',
  item_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属条目ID；与owner及revision必须组成同一归属链',
  revision_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定正文版本ID；不能自动切为最新版本',
  chunk_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定revision内片段ID，须验证完整归属',
  PRIMARY KEY (call_id, chunk_id),
  FOREIGN KEY (owner_id, call_id) REFERENCES model_call_grant(owner_id, call_id),
  FOREIGN KEY (owner_id, item_id, revision_id, chunk_id) REFERENCES chunk(owner_id, item_id, revision_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型call授权时确认可读的证据片段清单';

CREATE TABLE usage_ledger (
  call_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '单次模型请求身份；不同实际重试使用新callId',
  usage_version INT NOT NULL COMMENT '同call累计用量快照版本；不是追加扣费次数',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  provider VARCHAR(64) CHARACTER SET ascii NOT NULL COMMENT '模型供应商标识，不保存凭据',
  model VARCHAR(128) CHARACTER SET ascii NOT NULL COMMENT '本次请求使用的模型标识',
  input_tokens BIGINT NULL COMMENT '已报告或估算输入token；未知为NULL',
  output_tokens BIGINT NULL COMMENT '已报告或估算输出token；未知为NULL',
  amount DECIMAL(18,8) NULL COMMENT '累计费用金额；未知为NULL，不冒充0',
  measurement_kind VARCHAR(16) CHARACTER SET ascii NOT NULL COMMENT '用量来源REPORTED/ESTIMATED/UNKNOWN',
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化请求或事件参数SHA-256，用于防篡改与幂等冲突检测',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  PRIMARY KEY (call_id, usage_version),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, created_at, call_id
  KEY ix_usage_owner (owner_id, created_at, call_id),
  FOREIGN KEY (owner_id, call_id) REFERENCES model_call_grant(owner_id, call_id),
  CHECK (usage_version > 0),
  CHECK (measurement_kind IN ('REPORTED','ESTIMATED','UNKNOWN')),
  CHECK (input_tokens IS NULL OR input_tokens >= 0),
  CHECK (output_tokens IS NULL OR output_tokens >= 0),
  CHECK (amount IS NULL OR amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单call分版本累计用量快照；实报估算未知分开';

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

CREATE TABLE audit_event (
  id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '服务端生成的不透明记录ID',
  owner_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属账户ID；必须来自认证或持久授权，不能相信客户端身份字段',
  actor_type VARCHAR(24) CHARACTER SET ascii NOT NULL COMMENT '审计操作者类型：用户/服务/受限run等',
  actor_id VARCHAR(128) CHARACTER SET ascii NOT NULL COMMENT '审计操作者身份，不保存secret',
  action VARCHAR(96) CHARACTER SET ascii NOT NULL COMMENT '稳定业务审计动作名',
  target_type VARCHAR(32) CHARACTER SET ascii NOT NULL COMMENT '被操作资源类型',
  target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '被操作资源ID',
  trace_id VARCHAR(128) CHARACTER SET ascii NOT NULL COMMENT '请求链路关联标识，不作为授权凭证',
  safe_metadata_json JSON NOT NULL COMMENT '白名单审计元信息，禁止凭据和私密正文',
  created_at DATETIME(6) NOT NULL COMMENT '创建时间，UTC微秒',
  -- 访问索引，服务对应筛选/扫描路径：owner_id, created_at, id
  KEY ix_audit_owner_time (owner_id, created_at, id),
  -- 访问索引，服务对应筛选/扫描路径：owner_id, target_type, target_id, created_at
  KEY ix_audit_target (owner_id, target_type, target_id, created_at),
  FOREIGN KEY (owner_id) REFERENCES owner(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变安全审计；仅保存白名单元信息，不存秘密正文';
