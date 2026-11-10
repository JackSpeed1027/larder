# S01 幂等与响应合同（slice S01.SHORT_TX）

- runId：s01-20261110
- 结论：**TASK_LOCAL_PASS（slice）**——登记与业务同事务、冲突后独立读取、三错误码落地；**同 key 并发场景尚未单独压测**。
- 依据：docs/21 §S01、contracts/error-codes.json v2

## 改动

| 文件 | 改动 |
|---|---|
| IdempotencyStore | 去掉 REQUIRES_NEW 抢占：幂等登记与业务写入/Outbox/快照同事务；冲突由调用方事务外独立读取 |
| ItemIngestService.resolveExisting | 去掉 sleep 轮询；COMPLETED+同 body → 200 重放；不同 body → IDEMPOTENCY_PAYLOAD_MISMATCH；PROCESSING → IDEMPOTENCY_IN_PROGRESS(retryable) |
| IngestRejectedException | 新增 payloadMismatch / inProgress 工厂 |
| ItemIngestServiceTest | 冲突码断言更新；新增 PROCESSING → IN_PROGRESS 用例 |

## 实测

- mvn test：**91 passed**（含新增 IN_PROGRESS 用例）；
- erify-content.ps1：**ALL CONTENT CHECKS PASSED**（见 logs/verify-content.log）
  - 首次 202、同 key 同 body 200 且 itemId 不变；
  - 同 key 不同 body → **IDEMPOTENCY_PAYLOAD_MISMATCH**；
  - 同 URL 不同 key → 200 同一 item；8 并发同 URL → 1 item；
  - Flyway V1–V5，重启不重复。

## 未验证

- **同 key 并发**（N 个线程同 key 同 body：应只有 1 个 202，其余 200；DB 只有 1 item）尚未单独压测；现有 8 并发是“不同 key 同 URL”。
- 幂等记录的过期清理未实现（S01 后续/运维项）。

## 备注

- verify-content 需先 mvn package 再重建 api 镜像，否则容器仍跑旧 jar（本轮踩到并已纠正）。
