# S05 验证：SSE 有界连接（slice S05.BOUNDED）

- runId：s05-20261110
- 结论：**TASK_LOCAL_PASS（slice）**——连接与线程有界、超限 503、许可在完成/超时/出错时归还；**持久事件日志与游标续传仍待做**。
- 依据：docs/21 §S05、docs/24 §3

## 改动

| 位置 | 内容 |
|---|---|
| `JobEventsController` | `cachedThreadPool` → 固定池（`larder.sse.pool-size`，默认 8）；连接信号量（`larder.sse.max-connections`，默认 32），超限 503；`onCompletion/onTimeout/onError` 归还许可；`@PreDestroy shutdownNow` |
| 轮询等待 | 可中断 sleep；线程中断即退出，不继续占连接 |
| 修复 | `ownerId == null` 时不再 NPE，统一 404 |
| 测试 | `SseBoundedTest`：连接耗尽 503、越权 job 404 |

## 实测

- `mvn test`：**114 passed**（新增 2）；
- `verify-lease.ps1`：**ALL LEASE CHECKS PASSED**（snapshot 首发、Last-Event-ID 重连、租约回收重派）。

## 仍未完成（S05 剩余）

1. **事件序号仍是近似**：`seq = max(seq+1, next.seq())`，不是持久事件表；跨实例/历史过期无法真正回放；
2. `Last-Event-ID` 只回显为 `resumedFrom`，没有“历史已过期 → 明确 reset”语义；
3. 前端轮询降级、隐藏页降频未接；
4. snapshot→events 竞态无专门测试。

## 追加：S05.EVENT_LOG（持久事件 + reset 语义）

- 迁移 `V7__job_event.sql`：`job_event(owner_id, job_id, seq, event_type, payload_json, created_at)`，PK `(owner_id, job_id, seq)`，FK→`job(id)`；
- `JobEventLog`：seq 取 `job.last_event_seq`（与业务更新同事务），没有序号就不写；
- `RevisionBinder` 成功提交后追加 `SUCCEEDED` 事件（同事务）；
- SSE：带 `Last-Event-ID` 时从日志回放；**日志无记录但 job 序号已前进 → 发 `reset` + 权威 snapshot**，不假装续传；
- 单测：`JobEventLogTest`（正常追加 / 无序号不编造）。

### 实测

- `mvn test`：**116 passed**；
- `verify-publication.ps1` / `verify-lease.ps1`：**ALL … CHECKS PASSED**；
- `job_event` 实际有一行：`job_7372… seq=1 SUCCEEDED`。

### 迁移过程（必须记录）

V7 首次执行失败：`job_event.owner_id/job_id` 用 utf8mb4，而 `job.id` 是 ascii，外键字符集不一致。
修正为 `CHARACTER SET ascii COLLATE ascii_bin` 后仍被 Flyway 的失败记录挡住（`Detected failed migration to version 7`）。
处理：**仅测试库**删除 `flyway_schema_history` 中 version=7 的失败行后重新应用（V7 当时未落任何表/数据，无用户数据风险）。此后 V7 success=1。

### 仍未完成

- 事件只在发布成功路径写入；RUNNING/FAILED/CANCELLED 事件尚未接入；
- 前端轮询降级、隐藏页降频未做；
- snapshot→events 竞态未专门测试。

## 追加：状态转移统一写入事件（S05.TRANSITIONS）

| 转移 | 写入点 | 事件 |
|---|---|---|
| QUEUED/RETRY_WAIT → RUNNING（接管） | `InboxHandler.claim` | `RUNNING` |
| 租约回收 / attempt 耗尽 | `JobReconciler.runOnce` | `QUEUED` / `FAILED` |
| 用户取消 | `JobControlService.cancel` | `CANCEL_REQUESTED`（payload 带实际 state） |
| 用户重试 | `JobControlService.retry` | `QUEUED` |
| 发布成功 | `RevisionBinder.bind` | `SUCCEEDED` |

关键修复：`last_event_seq` 原先只在发布提交时 +1，接管/取消/重试/回收都不递增，导致事件 seq 冲突或为 0 被跳过。
现在 `claimJob` / `requestCancel` / `requeue` / `reclaim` 的 SQL 都 `last_event_seq = last_event_seq + 1`，事件与 SSE 游标共用同一序号。

### 实测

- `mvn test`：**116 passed**；
- `verify-lease.ps1` / `verify-control.ps1` / `verify-publication.ps1`：三个 **ALL … CHECKS PASSED**；
- `job_event` 实际分布：`RUNNING=6, SUCCEEDED=4, QUEUED=1, FAILED=1`（`CANCEL_REQUESTED` 本轮未出现，见下）。

### 仍未完成

- `CANCEL_REQUESTED` 在 e2e 中未观察到（取消路径需再核验：可能 job 已终态或 SQL 条件未命中）；
- 前端轮询降级、隐藏页降频未做；
- snapshot→events 竞态无专门测试；
- 事件表无保留期/清理策略。
