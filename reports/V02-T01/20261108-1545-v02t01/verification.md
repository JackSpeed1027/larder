# V02-T01 验证报告：内容模型与幂等

- runId：`20261108-1545-v02t01`
- 结论：**TASK_LOCAL_PASS**——accept 五项检查全过（后端 21 用例、契约 29 项、compose 配置、内容端到端 9 项）。
- 依赖：V01-T04；contractRev `5719da4c…` 未变（本任务未改契约）。

## 1. 交付代码

### 1.1 数据模型（迁移 + 实体）

| 内容 | 说明 |
|---|---|
| `V2__content_core.sql` | 从 `contracts/mysql-schema.sql` **抽取** owner / item / content_revision / job / outbox / idempotency_request 六张表，与 docs/16 逐字一致 |
| `content/model` | `Item`、`ContentRevision`、`Owner` + Mapper、`ItemKind` |
| `pipeline/model` | `Job`、`OutboxEvent`、`IdempotencyRequest` + Mapper、`JobKind/JobState/OutboxState/IdempotencyState` |

### 1.2 收录服务（`ingest`）

| 文件 | 作用 |
|---|---|
| `UrlNormalizer` | 保守规范化（小写 host、去默认端口、去 fragment、保留 query 顺序、拒绝 userinfo）→ SHA-256 |
| `Json` / `JsonValue` | 固定字段顺序的规范化 JSON、SHA-256、用户内容转义、快照字段读取 |
| `OwnerService` | username → owner_id；按需创建，`REQUIRES_NEW` 先提交（原因见 §4） |
| `IdempotencyStore` | `tryBegin` 用 `REQUIRES_NEW` 抢占幂等键，主键 `(owner, route, key)` 保证并发唯一 |
| `ItemIngestService` | 一次事务写 item + job(QUEUED) + outbox + 幂等完成态；`READ_COMMITTED`（原因见 §4） |
| `ItemController` | `POST /api/items`，强制 `Idempotency-Key`，owner 取自认证主体 |

### 1.3 语义（与 docs/04 对齐）

| 场景 | 结果 |
|---|---|
| 首次收录 | **202** + itemId/jobId |
| 同 key 同 body 重放 | **200**，返回首次的 itemId/jobId（dedupe=REPLAYED） |
| 同 key 不同 body | **409 IDEMPOTENCY_CONFLICT**，不产生任何写入 |
| 不同 key 同规范化 URL | **200**，返回已存在 item |
| 8 并发同 URL | 全部 200，**数据库只有 1 条 item** |
| 未登录 / 缺 key / 未知 kind | 401 / 400 / 400 |

## 2. 实测结果

```
== secrets ==        PASS
== backend_tests ==  PASS（21 用例：6 identity + 5 ingest 服务 + 5 URL/JSON + 5 controller）
== contracts ==      PASS（29 项）
== compose_config == PASS
== content_e2e ==    PASS（9 项）
RESULT PASS
```

端到端（真实 MySQL 8.4.13 容器 + 真实 HTTP）：

| # | 断言 | 证据 |
|---|---|---|
| 1 | 未登录 GET 返回 401 且下发 CSRF cookie | logs/content_e2e.log |
| 2 | 登录 200 | 同上 |
| 3 | 首次收录 202 + `itm_*`/`job_*` | 同上 |
| 4 | 同 key 同 body → 200，itemId 不变 | 同上 |
| 5 | 同 key 不同 body → 409 | 同上 |
| 6 | 不同 key 同 URL → 200 同一 item | 同上 |
| 7 | 8 并发 → 全部 200，单一 itemId | 同上 |
| 8 | MySQL：item/job/outbox = 1/1/1 | 同上 |
| 9 | Flyway `V1,V2` 均 success=1；重启 api 后迁移数不变 | 同上 |

## 3. 迁移可重复部署

V2 由脚本从契约 DDL 生成（`reports/V02-T01/<runId>/tools/gen_migration.py`），避免手抄漂移；
端到端脚本重启 api 前后 `flyway_schema_history` 行数一致（2），说明重复启动不会重复执行。

## 4. 过程中修掉的三个真实问题

1. **504：owner 外键锁等待**。幂等记录插入用 `REQUIRES_NEW`，但 owner 行还在主事务未提交，
   内层事务校验 `idempotency_request → owner` 外键时等待外层 → 锁超时。修法：owner 创建也 `REQUIRES_NEW` 先提交。
2. **500：REPEATABLE_READ 快照看不到并发赢家**。`uk_item_url` 冲突后回读，默认隔离级别读旧快照返回 null，
   把冲突当异常抛出。修法：`ingest` 用 `READ_COMMITTED`。
3. **迁移生成器把目录分隔符写进 `package`**（`com.larder.content/model`），编译失败；已修正并同步暂存模板。

另外修了脚本层的两个坑：`"$name:"` 被 PowerShell 当成驱动器引用；脚本无 BOM 时中文注释破坏语法。

## 5. 超出 allowlist 的改动（CR，需协调者追认）

| CR | 文件 | 原因 |
|---|---|---|
| CR-0001 | `backend/src/main/resources/db/migration/V2__content_core.sql` | 迁移属共享资源，本任务从契约生成六张表 |
| CR-0002 | `scripts/accept.py`、`scripts/dev/verify-content.ps1` | 把内容端到端纳入验收入口（accept.py 在 V01-T04 的 allowlist 内，本任务新增检查项） |
| CR-0003 | `README.md` | 阶段表与结论同步 |

## 6. 未验证 / 已知限制

- `content_revision`/`chunk` 只有表和实体，**没有写入逻辑**——正文由 V02-T03 的抓取/抽取流水线产生。
- 只支持 WEB/TEXT 收录；PDF/视频/音频在 V06。
- TEXT 的正文只存在 `job.input_json` 快照里，还没有 revision；V02-T03 落 revision 后再迁。
- URL 去重只对 WEB 生效；TEXT 目前不去重（内容级去重需要正文 hash，归 V02-T03）。
- 幂等记录未实现过期清理（schema 有 `expires_at`，本任务不扫表；V03 的补偿任务处理）。
- 未做跨 owner 测试：单管理员，owner 由 username 派生，V02 后续多 owner 时补。

## 7. 下一步

V02 的下一张卡是 **V02-T02（MinIO 不可变产物）** 或 **V02-T03（真实网页/粘贴链路）**。
按 DAG，T02 与 T03 可在 T01 之后并行；建议先做 T03 的抓取链路（能立刻看到真实内容进库），
T02 紧随其后把原文件与正文产物落到 MinIO。
