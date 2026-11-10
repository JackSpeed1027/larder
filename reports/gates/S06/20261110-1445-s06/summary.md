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
