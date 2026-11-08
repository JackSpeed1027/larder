-- V3 产物：artifact / revision_artifact（V02-T02 MinIO 不可变产物）
-- 由 reports/V02-T02/<runId>/tools/gen_migration_v3.py 从 contracts/mysql-schema.sql 抽取；
-- 改列必须先改契约再重新生成。迁移只增不改。

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
