-- V5 组织与阅读：chunk / tag / item_tag / collection / collection_item / note / reading_position
-- 由 reports/V02-T04/<runId>/tools/gen_migration_v5.py 从 contracts/mysql-schema.sql 抽取。
-- chunk 先建：note/reading_position 的外键依赖它（数据由 V04 索引构建写入）。

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
