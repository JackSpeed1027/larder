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
## 追加：S02.DISPATCH 已打通（s02-20261110）

- Java 在 Inbox 接管事务提交后调用 `RunnerClient.startRun`，请求体带冻结字段 `protocolVersion=v1 / mode=INGEST / scope{ownerId,itemId} / traceId=runId`；
- runId 由 `JobLeaseService.startRun` 分配（稳定、唯一），失败时 `releaseForRetry` 把 job 放回 QUEUED 并递增 attempt，由 MQ 重投；
- 实测：`verify-inbox.ps1` → **ALL INBOX CHECKS PASSED**（job RUNNING、lease_token=1、inbox 1 行、重复投递不重跑、坏消息进 DLQ）；
- Runner 侧收到请求：api 日志 `已派发 Runner：jobId=… runId=…`，证据 `dispatch.log`。

### 本轮修掉的两个真实问题

1. **Runner 镜像缺 `src/`**：`larder_runner.api.runs` 导入失败（容器启动即挂），Dockerfile 增加 `COPY src /app/src` + `PYTHONPATH`；
2. **Java 派发缺协议字段**：Runner 返回 **422**（`protocolVersion/scope` 缺失），补全后 202。
   失败时 lease_token 会快速增长（每轮重投 +1），这也是“重试风暴”的可观测信号。

### 仍未完成

- `/v1/runs` 只接纳，不执行 fetch/extract/chunk（S02.EXEC）；
- run 状态在进程内，重启丢失；
- 未校验 scope 与 job 的一致性（Java 侧复核）；
- 未接预算/审批。
## 追加：S02.EXEC 执行器模块（未接线）

- 新增 `runner/src/larder_runner/runtime/executor.py`：`RunExecutor.execute(runId, jobId, epoch, url)`
  编排 fetch → extract → chunk → 回传 Java `/internal/runs/{runId}/result`（`X-Internal-Token`）；
- 依赖全部注入（fetch/extract/chunk/publish），单测不联网；
- 失败语义：抓取/抽取领域异常 → `FAILED` + 原错误码（如 `EXTRACT_EMPTY`）；
  发布被拒（409 等）→ `FAILED` + `PUBLISH_REJECTED`；Java 不可达 → `JAVA_UNAVAILABLE`；
- 单测：`runner/tests/runtime/executor/test_executor.py` 3 个用例（成功发布、抽取失败、发布被拒）。

### 实测

`python -m pytest tests -m "not live"` → **43 passed**（40 + 3）。

### 尚未完成（下一 slice）

- **未接入 `/v1/runs`**：当前入口只登记 + 心跳，不会真正执行；接线需要：
  1. `RunStartRequest` 增加 `processingEpoch`（Java 派发时携带 job 的 epoch）；
  2. 背景执行（线程/调度器）+ 状态回写 + 失败码落状态；
  3. compose 给 runner 注入 `LARDER_INTERNAL_TOKEN` / Java base URL；
- Java 侧 `RunnerClient` 需把 `processingEpoch` 带上（当前只发 protocolVersion/scope/input）。
