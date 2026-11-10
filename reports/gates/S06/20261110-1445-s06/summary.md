# S06 集成门禁（部分）— 20261110-1445-s06

## 结果

- **9/9 端到端脚本 PASS**
- **14/14 任务验收 PASS**

## 脚本

| 脚本 | 结果 |
|---|---|
| verify-content | PASS |
| verify-artifacts | PASS |
| verify-outbox | PASS |
| verify-inbox | PASS |
| verify-lease | PASS |
| verify-publication | PASS |
| verify-control | PASS |
| verify-organize | PASS |
| verify-run-exec | PASS（公开收录→Runner→发布→可读全链路） |

## 任务验收

V01-T04、V02-T01～T04、V03-T01～T04、F01～F04、F15 —— 全部 PASS。

## 尚未执行（因此 S06 不能标 DONE）

1. **隔离环境故障注入**：ack 后进程崩溃 / 通知长期丢失 / MQ 断开恢复；
2. 取消与预算归属写入任务状态的专项测试（A02–A07 已有部分覆盖）；
3. 全部证据仍来自同一个 compose 环境（U02 的 V6 已合入，Flyway=7）。

## 证据

- 脚本日志：`reports/gates/S06/20261110-1445-s06/verify-*.log`
- 验收日志：`accept-*.log` 与 `reports/<task>/20261110-1445-s06-*/`

## 故障注入：ack 后崩溃（verify-fault-ack-crash.ps1）

场景（确定性构造，仅测试数据）：

1. 公开收录 → 等 inbox 有记录（说明消费者已 ack）；
2. 把 job 伪造成“崩溃现场”：`RUNNING` + `lease_until` 已过期 + `run_id=NULL`；
3. `docker compose restart api`；
4. 断言：租约代次递增 → job 重新派发 → Runner 完成 → `SUCCEEDED`，`attempt=2`，`item.current_revision_id=rev_*`。

实测：

```
[PASS] 收录并确认已 ack（inbox 有记录）: inbox=1
[PASS] 构造“ack 后崩溃”现场
[PASS] 重启 API：对账收回 → 重新派发 → 最终 SUCCEEDED: attempt=2，revision=rev_5ed57fe8…，inbox=2
ALL FAULT CHECKS PASSED
```

`inbox=2` 是**预期**：对账为新一次尝试生成了新的 outbox 事件，inbox 按 `(consumer,event_id)` 合法新增；关键断言是 `attempt=2`（没有重复执行同一尝试）。

## 隔离性说明（偏差，必须记录）

- 本故障注入运行在**当前开发 compose**，不是独立 `larder-it` project；
- 只创建了 `example.com/?fault=*` 测试数据，未删卷、未清库、未触碰非测试数据；
- 严格按 docs/21 应使用独立 project/DB/vhost/端口；这一点列为**未满足**，等协调者决定是否重跑隔离版。
