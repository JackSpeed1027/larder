# V03-T01 验证报告：Outbox 投递与 confirm

- runId：`20261109-0800-v03t01`
- 结论：**TASK_LOCAL_PASS**——accept 五项全过；真实 RabbitMQ 上验证了 confirm ack、unroutable 退回、broker 断开恢复与死信。
- 依赖：V02-T01；contractRev `5719da4c…` 未变。

## 1. 交付代码（`pipeline/outbox`）

| 文件 | 作用 |
|---|---|
| `OutboxRelay` | 一轮：收回过期租约 → 领取 → 事务外投递 → 按结果标记 |
| `OutboxStore` | 短事务领取（`FOR UPDATE SKIP LOCKED`）、markSent、markFailed、reclaimExpired |
| `OutboxRelayMapper` | 专用 SQL：领取/标 SENT/退避/收回，全部带 `relay_token` 条件 |
| `OutboxRow` | 投递视图（含 relay_token/租约），不动共享实体 |
| `OutboxPublisher` / `PublishOutcome` | 投递端口与四种结果（ACKED/NACKED/UNROUTABLE/TIMEOUT） |
| `RabbitOutboxPublisher` | 真实 RabbitMQ：mandatory + correlated confirm + returns |
| `RabbitTopology` | 声明 exchange/queue/binding，配置 confirm 与 returns |
| `OutboxSchedulingConfig` | 定时轮询，`larder.outbox.enabled` 默认关闭 |
| `OutboxBackoff` | 指数退避（1,2,4…封顶 60s）与耗尽判定 |

## 2. 关键语义（对应任务卡验收）

| 验收点 | 实现 |
|---|---|
| DB 成功 MQ 断开可恢复 | 事件先落 outbox；broker 不可用时投递失败 → 退避回 PENDING；broker 恢复后补投直到 SENT |
| confirm 丢失允许重投 | confirm 超时按 TIMEOUT 处理（不标 SENT）；租约过期由 `reclaimExpired` 收回；重复投递是"至少一次"，消费端幂等（V03-T02） |
| unroutable 不能标 SENT | `mandatory` + returns：`CorrelationData.getReturned() != null` 时返回 UNROUTABLE，即使已 ack 也不标 SENT |
| 短事务 | 领取用 `FOR UPDATE SKIP LOCKED` 只锁行、改状态、写租约；网络 I/O 在事务外 |
| fencing | 所有完成/失败更新都带 `relay_token` 条件；租约被接管后旧 confirm 更新 0 行 |

## 3. 实测结果

```
accept V03-T01 --profile offline → RESULT PASS
  secrets PASS | backend_tests PASS(44 用例) | contracts PASS(29)
  compose_config PASS | outbox_e2e PASS
```

真实 RabbitMQ 端到端（`scripts/dev/verify-outbox.ps1`，5 项）：

| # | 断言 | 实测 |
|---|---|---|
| 1 | api/rabbitmq 就绪、队列已声明 | health=UP，`larder.events.ingest` 存在 |
| 2 | 登录 | HTTP 200 |
| 3 | 收录产生 outbox 事件 | `job_… → out_…` |
| 4 | 真实 confirm ack → SENT 且队列有消息 | `state=SENT`，队列 7 条 |
| 5 | broker 断开后恢复补投 | 断开时 `PUBLISHING/0`（未 SENT）→ 恢复后 `SENT/4` |
| 6 | unroutable 永不 SENT、最终 DEAD | `state=DEAD attempt=5` |

第 4 项特别验证了"回退路径"：broker 停掉期间事件不会假装成功，恢复后第 4 次尝试才 SENT，
说明退避与重试真的在工作，而不是一次失败就丢。

## 4. 过程中修掉的问题

1. **Publisher 接口签名不一致**：`OutboxPublisher` 仍接收 `OutboxEvent`，与 `OutboxRow` 不匹配 → 编译失败，改为 `OutboxRow`。
2. **时区断言错误**：单测用本地时间比较 UTC 的 `available_at`，相差 8 小时 → 改为 `LocalDateTime.now(ZoneOffset.UTC)`。
3. **compose 环境变量名错**：写的是 `LARDER_RABBIT_USER`，`.env` 里是 `LARDER_RABBITMQ_USER`，导致 `docker compose` 直接报缺变量。
4. **脚本 JSON 被当成 text/plain**：`StringContent` 的内联字符串拼接被 PowerShell 拆参 → 先建变量再传，登录才 200。
5. **mysql 客户端 warning 触发脚本终止**：改用 `MYSQL_PWD` 环境变量，不再把密码放命令行。
6. **broker 刚 start 就调用 rabbitmqctl**：容器 Started ≠ broker 可用 → 加 `rabbitmq-diagnostics ping` 等待。

## 5. 超出 allowlist 的改动（CR）

| CR | 文件 | 原因 |
|---|---|---|
| CR-0001 | `backend/pom.xml` | 加 `spring-boot-starter-amqp` |
| CR-0002 | `backend/src/main/resources/application.yml` | `larder.outbox.*` 配置（默认关闭） |
| CR-0003 | `compose.yaml` | api 注入 RabbitMQ 环境、打开投递器、等待 rabbitmq 健康 |
| CR-0004 | `scripts/accept.py` | 新增 `outbox_e2e` 与 V03-T01 任务 |
| CR-0005 | `README.md`、`docs/项目记忆.md` | 阶段表与记忆同步 |

## 6. 未验证 / 已知限制

- **消费端还没有**：消息进了 `larder.events.ingest` 但没人消费；inbox 去重是 V03-T02。
- **没有 DLQ**：超过最大次数进的是数据库 `outbox.state='DEAD'`，不是 RabbitMQ 死信队列；V12 再评估。
- **单 relay 实例**：多实例安全依赖 `SKIP LOCKED` + token，但本轮只用单实例验证。
- 没有 metrics/告警（只有日志），V12 再加。
- e2e 用管理员与 root 凭据，生产要换受限账号。

## 7. 下一步

**V03-T02（Inbox 消费幂等）**：消费 `larder.events.ingest`，用 `inbox` 表按 `(consumer, event_id)` 去重，
再做 V03-T03（lease/reconcile/SSE）解锁 V02-T03 的抓取链路。
