# S03 验证：产物与 fenced 发布边界（slice S03.STAGE_THEN_BIND）

- runId：s03-20261110
- 结论：**TASK_LOCAL_PASS（slice）**——MinIO 上传已移出数据库事务，发布改为“事务外上传 → 短事务绑定”；**lease_token 尚未进入提交条件**。
- 依据：docs/21 §S03、docs/24 §3

## 结构

| 类 | 职责 |
|---|---|
| `RevisionPublisher` | 无事务：预检（job/run/epoch/cancel/sha）→ `artifactService.store`（外部 I/O）→ 调 binder；绑定失败登记孤立对象 |
| `RevisionBinder` | `@Transactional` 短事务：lockJob → 复核 run/epoch/state/cancel → 写 revision → 绑 artifact → 移 item 指针（`deleted_at IS NULL`）→ 提交 job（要求 `lease_until > UTC_TIMESTAMP(6)`） |
| `PublicationMapper.findJob` | 预检无锁读，避免在外部 I/O 前持锁 |

## 实测

- `mvn test`：**108 passed**（新增 RevisionBinderTest 5、重写 RevisionPublisherTest 7）；
- `verify-run-exec.ps1`：**ALL EXEC CHECKS PASSED**（真实 example.com 全链路，发布走新结构）；
- `verify-publication.ps1`：**ALL PUBLICATION CHECKS PASSED**（回传 200、重放 REPLAYED、旧 epoch 409 EPOCH_SUPERSEDED 且不写 revision）。

## 仍未完成

- **lease_token 未加入 commitJob 条件**：目前靠 run_id + epoch + state + lease_until；租约被接管但 run_id 未变时，旧 worker 仍可能提交（需把 token 传入 binder 并加条件）；
- 孤立对象只有 `OrphanRecorder`（jsonl 记录），没有对账/回收任务；
- 对象复用校验（HEAD 后 PUT 不是 CAS）未实现，当前依赖 content-addressed key + DB 唯一引用；
- S3 慢/失败的重试与暂存记录状态机未做。

## 追加：lease_token 已进入提交条件（S03 收尾）

- `PublicationMapper.lockJob/findJob` 读取 `lease_token`；`JobPublishRow.leaseToken`；
- `commitJob` 增加 `AND lease_token = #{token}`：租约被接管（token 变化）后旧 worker 的提交影响 0 行 → 回滚并报 stale/cancel；
- 单测 `RevisionBinderTest` 覆盖 token 传入与提交失败路径；
- `verify-publication.ps1` 全过：`ALL PUBLICATION CHECKS PASSED`（含 epoch 拒绝、重放）。
