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
