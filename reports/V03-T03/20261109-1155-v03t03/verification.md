# V03-T03 验证报告：lease / reconcile / SSE

- runId：`20261109-1155-v03t03`（含 Python 侧后的完整验收）
- 结论：**TASK_LOCAL_PASS**——accept 六项全过（后端 64 + runner 19 + 租约端到端）；真实环境验证 SSE 快照/重连、租约过期对账、fencing 代次递增。
- 依赖：V03-T02；contractRev `5719da4c…` 未变。

## 1. 交付代码（Java 侧）

| 位置 | 内容 |
|---|---|
| `pipeline/lease/JobLeaseRow` + `JobLeaseMapper` | 续租、分配 runId、fencing 提交、过期查询/收回 SQL |
| `pipeline/lease/JobLeaseService` | renew、startRun（稳定 runId）、commitSuccess/commitFailure、progress |
| `pipeline/lease/JobProgress` | 进度快照（state/attempt/runId/epoch/seq/terminal） |
| `pipeline/lease/StaleLeaseException` | 旧 worker 被拒绝时抛出 |
| `pipeline/lease/JobEventsController` | `GET /api/jobs/{id}/events` SSE：先 snapshot，再推状态变化，支持 Last-Event-ID |
| `pipeline/reconcile/JobReconciler` | 过期 RUNNING → QUEUED（attempt+1、退避）或 FAILED（达到上限），并写 outbox 重新派发 |
| `pipeline/reconcile/ReconcileSchedulingConfig` | 定时对账，`larder.reconcile.enabled` 控制 |
| `runner/client/RunnerClient` | 最小 Runner 客户端（V05 预留）：runId 作幂等键，非 2xx 抛异常 |

### Python 侧（本轮补齐）

| 文件 | 作用 |
|---|---|
| `runner/src/larder_runner/runtime/cancellation.py` | 协作式取消令牌：幂等 cancel、父子传播、回调（已取消则立即执行） |
| `runner/src/larder_runner/runtime/scheduler.py` | 有界并发调度：FIFO、上限、排队/运行取消语义、finish 归还 |
| `runner/src/larder_runner/api/events.py` | 事件日志 + SSE：单调序号、snapshot 兜底、Last-Event-ID 增量、帧编码、路由 |
| `runner/tests/runtime/{scheduler,cancellation}/`、`runner/tests/api/events/` | 17 个 pytest 用例 |

## 2. 关键语义（对应任务卡验收）

| 验收点 | 实现 |
|---|---|
| 旧 worker 迟到被拒绝 | 提交条件 = `state='RUNNING' + lease_owner + lease_token + processing_epoch + run_id + 租约未过期`；任一不匹配更新 0 行 → `false` / `StaleLeaseException` |
| ack 后 HTTP 前崩溃对账可续 | `run_id` 稳定（`uk_job_run` 唯一）；对账把过期 RUNNING 收回并**重写 outbox 事件**，由同一条可靠通道重新派发 |
| SSE 重复/断流可还原状态 | 每次连接先发 `snapshot`（权威 DB 状态 + seq）；重复/乱序由客户端以 snapshot 覆盖；`Last-Event-ID` 回显在 `resumedFrom` |
| 最小持久 run 适配 | `startRun` 分配/复用 runId；`RunnerClient` 以 runId 作 `Idempotency-Key` |

## 3. 实测结果

```
accept V03-T03 --profile offline → RESULT PASS
  secrets PASS | backend_tests PASS(64 用例) | runner_tests PASS(19 用例)
  contracts PASS(29) | compose_config PASS | lease_e2e PASS
```

runner 测试 19 个 = 2 个健康检查 + 17 个新增（取消 5、调度 6、事件/SSE 6）。

真实端到端（`scripts/dev/verify-lease.ps1`，4 项）：

| # | 断言 | 实测 |
|---|---|---|
| 1 | api 就绪 | health=UP |
| 2 | 登录 | HTTP 200 |
| 3 | 收录后 job 被接管 | `job_… RUNNING` |
| 4 | SSE 首发 snapshot | `event:snapshot` + `"state":"RUNNING"` + jobId |
| 5 | SSE 断流重连 | `resumedFrom=0`，snapshot 再次下发 |
| 6 | 租约过期 → 对账 → 重新接管 | `lease_token 1 → 2`，`attempt=2`，`outbox=2` |

第 6 项同时证明了三件事：对账真的收回了过期任务、重新派发走了 outbox、重新接管时代次递增（旧 worker 的提交条件已不可能命中）。

## 4. 过程中修掉的问题

1. 单测 `commitFailure` 的 Mockito 参数个数少一个（`state` 与 `errorCode` 都是 NEEDS_DECISION）→ 编译失败；
2. SSE 验证里 `curl --max-time` 触发 exit 28，被 `ErrorActionPreference=Stop` 当失败 → 封装 `SseGet` 把主动断流视为预期；
3. Docker 守护进程中途停止 → 重启后重跑（不影响结论）。

## 4.1 环境问题：Smart App Control 拦截 uv 虚拟环境

本机 `Smart App Control = 1`，会拦截 uv 新建 venv 里的可执行文件（`os error 4551`），
`uv run pytest` 无法启动。处理方式：

- 不静默跳过：`scripts/accept.py` 在检测到该错误时，回退到**系统 Python** 执行同一套测试，
  并在日志里写明 `# FALLBACK: uv 被 Smart App Control 拦截…`；
- 系统 Python 3.13 与项目声明 `<3.13` 不一致（仅本机回退），CI 上仍用 uv + 3.12；
- 这是环境限制，不是代码问题，报告如实记录。

## 5. 超出 allowlist 的改动（CR）

| CR | 文件 | 原因 |
|---|---|---|
| CR-0001 | `backend/src/main/resources/application.yml` | `larder.lease.*`、`larder.reconcile.*`、`larder.runner.*` 配置 |
| CR-0002 | `compose.yaml` | api 打开对账、设置最大次数与 Runner 地址 |
| CR-0003 | `scripts/accept.py` | 新增 `lease_e2e` 与 V03-T03 任务 |
| CR-0004 | `README.md`、`docs/项目记忆.md` | 阶段表与记忆同步 |
| CR-0005 | `runner/pyproject.toml` | pytest `pythonpath = ["src"]`（src 布局需要） |
| CR-0006 | `scripts/accept.py` | runner 测试的 Smart App Control 回退分支 |

## 6. 未验证 / 已知限制（重要）

- **Python 模块尚未装配进 FastAPI app**：`build_events_router` 与 `BoundedScheduler` 已可用，但 `app/main.py` 未注册（不在本卡 allowlist），装配在 V05。
- **没有真实执行器在跑 job**：`renew` 与 `commitSuccess` 目前只有测试调用；接入 Runner 后才会真正心跳。
- **旧 worker 迟到只由单测证明**，没有多进程实跑。
- SSE 的 `seq` 用"DB seq + 内存自增"近似；没有持久化 job_event 表（V05 若需要严格事件序号再补）。
- 对账达到上限直接 FAILED，没有"人工重试"接口。
- RunnerClient 尚未接入派发链路（V05）。

## 7. 下一步

V02-T03（真实网页/粘贴链路）的前置（V02-T02 + V03-T03）已齐，下一步做 **V02-T03**：
Runner 侧 `stages/fetch|extract|chunk` + Java 侧 `runner/ingest`、`content/publication`，把正文落成 revision 并回传产物。
