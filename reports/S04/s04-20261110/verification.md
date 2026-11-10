# S04 验证：租约回收与 Outbox 同事务（slice S04.REAP_TX）

- runId：s04-20261110
- 结论：**TASK_LOCAL_PASS（slice）**——对账整轮在一个短数据库事务内完成（回收 + 补发 Outbox 原子提交）；**QUEUED 补偿扫描、`next_attempt_at` 退避复核、lease_token 入提交条件仍未做**。
- 依据：docs/21 §S04、docs/24 §3

## 改动

| 位置 | 内容 |
|---|---|
| `JobReconciler.runOnce` | 加 `@Transactional`：回收 job 状态（reclaim）与插入 outbox 事件同事务；中途崩溃不会留下“QUEUED 但无通知” |
| 注释 | 说明不变量：事务内只做 DB，不调用 Runner/对象存储，保持短事务 |

## 实测

- `mvn test`：**108 passed**；
- `verify-lease.ps1`：**ALL LEASE CHECKS PASSED**
  - SSE snapshot 首发、断流重连还原；
  - 租约过期 → 对账收回 → 重新派发并接管：token 1→2、attempt=2、outbox=2。

## 仍未完成（S04 后续）

1. **QUEUED 补偿扫描**：`state='QUEUED' AND next_attempt_at <= now AND next_reconcile_at <= now` 且无 PENDING outbox 时补发，避免“QUEUED 永久无通知”；
2. **退避写入校对**：`reclaim` 已写 `next_attempt_at`，但没有独立测试覆盖指数退避 + jitter 的上界；
3. **lease_token 未进入 `commitJob`**（S03 遗留）：当前靠 run_id + epoch + lease_until；
4. **背压**：prefetch/派发槽位/429 已有部分（Runner 容量），但 Java 侧无“最大等待任务数/Retry-After”配置；
5. 对账未做“通知长期丢失”模拟（需隔离环境故障注入）。
## 追加：S04.QUEUED_SWEEP（补偿扫描 + 退避 jitter）

- `JobLeaseMapper.selectQueuedDue`：QUEUED 且 `next_attempt_at` 到期、`next_reconcile_at` 未到期的任务；
- `countPendingOutbox`：已有 PENDING 事件则跳过（relay 会投递），避免重复补发；
- `markReconcile`：补发后写 `next_reconcile_at = now + 30s` 防抖；
- `OutboxBackoff.delaySecondsWithJitter`：在 `[base, base*1.25]` 内抖动并封顶 60s；对账使用 `ThreadLocalRandom`；
- 单测：`QueuedSweepTest`（补发 / 已有 PENDING 跳过）、`OutboxBackoffTest`（单调、封顶、jitter 边界、exhausted）。

### 实测

- `mvn test`：**111 passed**；
- `verify-lease.ps1`：**ALL LEASE CHECKS PASSED**（token 1→2、attempt=2、outbox=3）；
- `verify-inbox.ps1`：**ALL INBOX CHECKS PASSED**（无回归）。

### 过程中抓到的问题（真实）

补偿扫描的 SQL 写成了 `outbox_event`，实际表名是 **`outbox`** → 对账每次抛 `SQLSyntaxErrorException`，整轮事务回滚，租约过期任务无法收回（`verify-lease` 超时）。单元测试用 mock 完全掩盖了这个错误——**SQL 表名必须靠真实 DB 的 e2e 才能发现**。

### 仍未完成

- `lease_token` 尚未进入 `commitJob` 条件（S03 遗留）；
- Java 侧背压（最大等待任务数 / 429 + Retry-After）未做；
- 通知长期丢失的故障注入未做。
