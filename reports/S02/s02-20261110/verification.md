# S02 slice 验证：Runner 运行入口（S02.RUN_ENTRY）

- runId：s02-20261110
- 结论：**TASK_LOCAL_PASS（slice）**——Runner 具备 `/v1/runs` 接纳、runId 幂等、payload 冲突、心跳与容量上限；**尚未接入 Java 派发链路**。
- 依据：docs/21 §S02、contracts/runner.openapi.json、docs/24 §3

## 改动

| 文件 | 内容 |
|---|---|
| runner/src/larder_runner/api/runs.py | POST /v1/runs、GET /v1/runs/{id}、POST /v1/runs/{id}/heartbeat；protocolVersion 校验；runId+payload 幂等；409 RUN_PAYLOAD_CONFLICT；429 RUN_CAPACITY_EXCEEDED |
| runner/app/main.py | 装配 build_runs_router(RunRegistry(max_concurrent_runs)) |
| runner/tests/api/runs/test_runs.py | 6 个用例（含容量 429、未知 run 404、心跳） |

## 实测

- `python -m pytest tests -m "not live"`：**40 passed**（原 34 + 新增 6）。

## 未完成（S02 后续）

- Java 侧派发装配：`RunnerClient.startRun` 尚未接入 Inbox/worker（仍靠人工 SQL 补 runId）；
- Runner 执行阶段（fetch/extract/chunk）未接入 /v1/runs；
- run 状态未持久化（进程内 registry），重启丢失——S02 后续 slice 或 S04 持久账本；
- 服务身份/租约校验只在 Java 侧，Runner 目前只记录 lease 字段。

## 手测方法

```powershell
# 直接打容器里的 runner
docker compose up -d --build runner
curl.exe -sS -X POST http://127.0.0.1:8000/v1/runs -H "Content-Type: application/json" -d "{\"protocolVersion\":\"v1\",\"runId\":\"run_manual_1\",\"jobId\":\"job_manual_1\",\"mode\":\"INGEST\",\"scope\":{\"ownerId\":\"own_1\"},\"input\":{}}"
# 同 body 再来一次 → dedupe=REPLAYED；改 input → 409 RUN_PAYLOAD_CONFLICT
curl.exe -sS -X POST http://127.0.0.1:8000/v1/runs/run_manual_1/heartbeat
```
