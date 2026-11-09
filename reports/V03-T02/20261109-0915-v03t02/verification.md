# V03-T02 验证报告：Inbox 及持久接管

- runId：`20261109-0915-v03t02`
- 结论：**TASK_LOCAL_PASS**——accept 五项全过；真实 RabbitMQ 验证重复投递不重复接管、坏消息进死信、inbox 与 job 接管同事务。
- 依赖：V03-T01；contractRev `5719da4c…` 未变。

## 1. 交付代码（`pipeline/inbox`）

| 文件 | 作用 |
|---|---|
| `InboxRecord` / `InboxMapper` | inbox 表映射 + 消费去重/接管 SQL（含条件更新 `claimJob`） |
| `InboxHandler` | 一个事务内：写 inbox（唯一）→ 接管 job（QUEUED→RUNNING + 租约） |
| `InboxListener` | 消费事件：解析 → 调用 handler → **提交后** manual ack；坏消息 nack→DLQ |
| `InboxRabbitConfig` | 手动 ack、有界 prefetch、`defaultRequeueRejected=false` |
| `InboxOutcome` | ACCEPTED / ALREADY_RUNNING / ALREADY_DONE / CONFLICT / UNKNOWN_JOB |
| `V4__inbox.sql` | 从契约 DDL 生成 inbox 表 |
| `RabbitTopology`（修改） | 新增 DLX `larder.events.dlx`、DLQ `larder.events.ingest.dlq`，ingest 队列挂死信交换机 |

## 2. 关键语义（对应任务卡验收）

| 验收点 | 实现 |
|---|---|
| 接管前后/ack 前后崩溃可恢复 | inbox 插入与 job 接管**同事务**：崩溃在提交前 → 全部回滚，消息重投可重来；提交后崩溃 → 消息重投时 job 已 RUNNING，仅 ack，不重复执行 |
| Inbox 不能掩盖未执行 job | inbox 只代表"事件被接收 + 接管尝试"；若 job 仍 QUEUED/RETRY_WAIT 则抛错重投，绝不因 inbox 有记录就静默 ack |
| 重复消息不重复发布/执行 | 主键 `(consumer,event_id)` 去重；`claimJob` 条件更新只有 1 行成功；重复投递 lease_token 不变 |
| 有界 prefetch | `larder.inbox.prefetch`（默认 5）+ 手动 ack |
| 死信可诊断 | 坏消息（缺 jobId、job 不存在、payload 冲突）→ `basicNack(requeue=false)` → DLQ；其它异常 → `requeue=true` 重投 |

## 3. 实测结果

```
accept V03-T02 --profile offline → RESULT PASS
  secrets PASS | backend_tests PASS(51 用例) | contracts PASS(29)
  compose_config PASS | inbox_e2e PASS
```

真实端到端（`scripts/dev/verify-inbox.ps1`，6 项）：

| # | 断言 | 实测 |
|---|---|---|
| 1 | ingest + dlq 队列已声明 | 均存在 |
| 2 | 登录 | HTTP 200 |
| 3 | 收录产生事件 | `job_… → out_…` |
| 4 | 消费者接管 job 并写 inbox | `RUNNING / worker-local / lease_token=1 / lease_until 非空`，inbox 1 行且 accepted_at 非空 |
| 5 | 同 event_id 重发（模拟 confirm 丢失后的重投） | **lease_token 仍为 1**，inbox 仍 1 行 |
| 6 | 坏消息（缺 jobId） | DLQ 收到 1 条，inbox 0 行 |

第 5 项是核心：把 outbox 状态改回 PENDING 让 relay 用同一个 `event_id` 再发一次，
消费者没有再次接管 job，证明去重键真的生效；第 6 项证明坏消息不会污染 inbox。

## 4. 过程中修掉的问题

1. `rabbitmqctl rabbitmq-diagnostics ping` 是错的（`rabbitmq-diagnostics` 是独立命令）→ 直接调用；
2. 脚本函数参数名为 `$args`（PowerShell 自动变量）导致命令为空 → 改名 `$cmd`；
3. 旧队列没有 DLX 参数，重新声明会 `PRECONDITION_FAILED` → 脚本在启动前删除开发队列（记录在脚本注释）；
4. 原生命令 stderr 在 `ErrorActionPreference=Stop` 下被当成异常终止脚本 → 函数内局部改 `Continue` 并合并输出；
5. `PoisonEventException` 定义后未使用 → 删除，避免死代码。

## 5. 超出 allowlist 的改动（CR）

| CR | 文件 | 原因 |
|---|---|---|
| CR-0001 | `backend/src/main/resources/db/migration/V4__inbox.sql` | 迁移属共享资源 |
| CR-0002 | `backend/src/main/java/com/larder/pipeline/outbox/RabbitTopology.java` | 队列加 DLX/DLQ（共享包 V03-T01 的文件） |
| CR-0003 | `backend/src/main/resources/application.yml` | `larder.inbox.*` 配置 |
| CR-0004 | `scripts/accept.py` | 新增 `inbox_e2e` 与 V03-T02 任务 |
| CR-0005 | `README.md`、`docs/项目记忆.md` | 阶段表与记忆同步 |

## 6. 未验证 / 已知限制

- **job 只被接管（RUNNING），还没有真正执行**：执行器/心跳/租约续期/对账是 V03-T03；
  当前 RUNNING 的 job 若进程崩溃，要等 V03-T03 的租约过期收回。
- **DLQ 没有自动重放**：只能人工诊断后重投，V12 再考虑工具化。
- **inbox 没有清理策略**：行会一直保留（去重需要历史），清理要单独设计。
- 端到端脚本会删除开发环境的队列（清空测试消息），不适用于生产。
- 没有消费指标（堆积、失败率），只有日志。

## 7. 下一步

**V03-T03（lease/reconcile/SSE）**：job 心跳续租、过期接管、旧结果拒绝（processing_epoch）、
状态对账与 SSE 进度推送；完成后 V02-T03 的抓取链路才解锁。
