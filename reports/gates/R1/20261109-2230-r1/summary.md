# R1 集成验证汇总

- 运行：8 个端到端脚本 + 14 个任务验收
- 结果：**8/8 脚本 PASS，14/14 任务 accept PASS**（在修复 1 个测试断言后重跑）

## 脚本

| 脚本 | 结果 |
|---|---|
| verify-content | PASS |
| verify-artifacts | PASS |
| verify-outbox | PASS（修复后重跑；见下） |
| verify-inbox | PASS |
| verify-lease | PASS |
| verify-publication | PASS |
| verify-control | PASS |
| verify-organize | PASS |

## 任务验收

V01-T04、V02-T01、V02-T02、V02-T03、V02-T04、V03-T01（修复后重跑）、V03-T02、V03-T03、V03-T04、F01、F02、F03、F04、F15 —— 全部 PASS。

## 本次发现并修掉的问题

`verify-outbox.ps1` 的“SENT”检查原先断言 **RabbitMQ 队列深度 > 0**。消费者（inbox listener）会在检查前把消息取走，导致断言随机失败（首轮 R1 运行即失败：`队列里没有消息: larder.events.ingest 0`）。
改为：**state=SENT（confirm ack 成功）+ inbox 存在该 event_id 的行（已被消费）**，两个断言都稳定且更贴近语义。

## 证据

- 各脚本原始输出：`reports/gates/R1/20261109-2230-r1/verify-*.log`
- 各任务 accept 输出：`reports/gates/R1/20261109-2230-r1/accept-*.log` 与 `reports/<task>/20261109-2230-r1-*/`
