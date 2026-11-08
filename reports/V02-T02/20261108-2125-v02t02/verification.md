# V02-T02 验证报告：MinIO 不可变产物

- runId：`20261108-2125-v02t02`
- 结论：**TASK_LOCAL_PASS**——accept 五项全过；真实 MinIO 上用真实 S3 SDK 验证了写入/读取/幂等/拒绝覆盖；V3 迁移在真实 MySQL 上 success=1。
- 依赖：V02-T01；contractRev `5719da4c…` 未变（本任务未改契约）。

## 1. 交付代码

| 位置 | 内容 |
|---|---|
| `V3__artifacts.sql` | 从契约 DDL 抽取 `artifact` / `revision_artifact` 两表 |
| `content/storage/ObjectStore` | 对象存储端口：`putImmutable` / `exists` / `get` |
| `content/storage/S3ObjectStore` | AWS SDK v2 实现（path-style，可指 MinIO）；bucket 不存在时自动创建 |
| `content/storage/ArtifactKeys` | key 派生：`artifacts/{ownerId}/{sha 前两位}/{sha256}`；服务端算 sha256 |
| `content/storage/ArtifactService` | 校验 → 写不可变对象 → 再发布 DB 引用；同内容幂等复用 |
| `content/storage/OrphanRecorder` | 对象已写、DB 落库失败时写 JSONL 孤立对象清单 |
| `runner/artifacts/ArtifactManifestValidator` | Runner 上报清单的结构校验（ID/hash/bytes/mime/条数/重复） |
| `scripts/dev/verify-artifacts.ps1` | 起 MinIO + 跑真实 S3 集成测试 |

## 2. 关键语义（对应任务卡验收）

| 验收点 | 做法 |
|---|---|
| 对象写失败无有效正文引用 | 先写对象、后写 DB；对象写失败直接抛异常，DB 不插入 |
| 不把 multipart ETag 当 hash | 服务端对字节算 SHA-256；ETag 从不读取、不落库（单段 ETag 是 md5，多段是 `md5-N`，都不是 sha256） |
| 重传不覆写不同内容 | key 由内容 hash 派生；写入前 HEAD 比对 metadata.sha256，不一致抛 `ArtifactConflictException`，绝不覆盖 |
| 同内容重复接收 | 先按 `(owner, sha256, bytes, mime)` 查 DB，命中直接返回原记录，不重复写对象 |
| 落库失败 | 记录孤立对象（key/sha/bytes/mime/时间）到 JSONL，保留对象不删（可能已被并发复用） |

## 3. 实测结果

```
accept V02-T02 --profile offline → RESULT PASS
  secrets PASS | backend_tests PASS(33 用例) | contracts PASS(29)
  compose_config PASS | artifact_e2e PASS
```

真实 MinIO（`scripts/dev/verify-artifacts.ps1`）：

| 检查 | 结果 |
|---|---|
| MinIO `/minio/health/live` | 200 |
| `S3ObjectStoreIT`（真实 S3 SDK） | **Tests run: 1, Failures: 0, Errors: 0, Skipped: 0** |
| V3 迁移（真实 MySQL） | `select version,success ... where version='3'` → `3 1` |

`S3ObjectStoreIT` 做了四件事：写入后能读回、同内容重复写幂等、同 key 不同内容抛冲突、原对象未被覆盖。
V3 检查会重建 api 镜像、重启 api，再查 `flyway_schema_history`——不是只看迁移文件存在。

单元测试 12 个新增用例覆盖：hash 不符拒绝、超限拒绝、mime 拒绝、幂等复用不写对象、写对象失败不落库、落库失败记录孤立对象、清单结构校验（坏 sha/负数/重复/超 200 条）。

## 4. 超出 allowlist 的改动（CR，需协调者追认）

| CR | 文件 | 原因 |
|---|---|---|
| CR-0001 | `backend/src/main/resources/db/migration/V3__artifacts.sql` | 迁移属共享资源，从契约 DDL 生成 |
| CR-0002 | `backend/pom.xml` | 加 AWS SDK v2 `s3` 依赖与 BOM 2.57.10（ADR-0004 选型） |
| CR-0003 | `backend/src/main/resources/application.yml` | `larder.storage.*` 配置（endpoint/bucket/上限/孤立清单路径） |
| CR-0004 | `compose.yaml` | api 注入 S3 环境变量 + `api-orphans` 卷 |
| CR-0005 | `scripts/accept.py` | 新增 `artifact_e2e` 检查与 V02-T02 任务 |
| CR-0006 | `README.md`、`docs/项目记忆.md` | 阶段表与跨窗口记忆同步 |

## 5. 未验证 / 已知限制

- **还没有产物接收的 HTTP 端点**：`ArtifactService` 已可被调用，但"Runner 上传 → Java 接收"的协议与路由属 V03-T02/T03；本任务只验证"接收之后的存储链路"。
- `revision_artifact` 表已建，但**没有写入逻辑**——正文版本与产物绑定要等 V02-T03 的抓取/抽取落地。
- 不可变靠"内容寻址 + 不覆盖策略"，**没有启用 S3 Object Lock/WORM**；V12 若需要合规级不可变再评估。
- bucket 自动创建是单机便利；多实例/生产应改 init 容器（V12）。
- 孤立对象只有清单，**没有自动清理**：清理必须先确认未被任何 artifact 引用（V03 对账任务）。
- 真实 MinIO 测试用的是 root 凭据；生产应按 docs/06 发受限账号（V12）。

## 6. 下一步

V02-T03（真实网页/粘贴链路）仍缺 **V03-T03（lease/reconcile/SSE）** 这个前置，所以按 DAG 下一步是 **V03-T01（Outbox 投递与 confirm）**：
把现在 `PENDING` 的 outbox 事件真正投递到 RabbitMQ，做 confirm/return/manual ack 与失败退避。
