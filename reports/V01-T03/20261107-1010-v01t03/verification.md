# V01-T03 验证报告：契约 / Mock / 类型生成

- runId：`20261107-1010-v01t03`
- 结论：**TASK_LOCAL_PASS**（29 项契约检查全过，0 失败）
- 冻结版本：`contractRev = 5719da4c…`（本次变更后）、`designRev = e4b36035…`（未改动）
- 依赖：V01-T02

## 1. 本任务补了什么

### 1.1 契约缺口（R1 要求"公共 API / 事件 / 错误 schema"）

| 文件 | 变化 |
|---|---|
| `contracts/error-codes.json` | **新增**：27 个稳定错误码（HTTP 状态、默认 retryable、owner、中文说明），来源 `docs/04` 错误码表 |
| `contracts/run-response.schema.json` | `usage` / `manifest` / `error` 原来是"任意 object"，现在收紧为 `$defs`：`RunUsage`、`ArtifactManifest`+`ArtifactRef`（含 sha256 校验位）、`RunError` |
| `contracts/run-event.schema.json` | 新增 `x-eventPayloadMap` 与 7 个 payload 定义（run.started、stage.started/committed、run.succeeded/failed/needs_decision/waiting_approval） |
| `contracts/examples/` | 新增 8 个样例（含 queued 快照、成功/失败事件、非法 seq、缺字段 payload、未知字段、错误码大小写），`cases.json` 扩到 **14 条** |

### 1.2 生成的三端类型

| 端 | 工具（版本固定） | 产物 |
|---|---|---|
| TS | openapi-typescript 7.13.0 | `frontend/src/generated/runner-api.ts`（端点类型，82 KB） |
| TS | json-schema-to-typescript 15.0.4 | `run-request.ts` / `run-response.ts` / `run-event.ts` |
| Python | datamodel-code-generator 0.26.5（Pydantic v2） | `runner/src/larder_runner/api/models/*.py`，`extra='forbid'` |
| Java | **未生成**（手写记录 + parity 校验） | `ApiResponse` / `ApiErrorResponse`，见 §4 说明 |

### 1.3 Mock 与检查脚本

- `scripts/contracts/mock_runner.py`：stdlib HTTP 服务，**只读** `contracts/examples`；`POST /v1/runs` 用 `run-request.schema.json` 严格校验，`GET` 返回快照，`/events` 发 SSE，`/api/ping` 返回公开壳。
- `scripts/contracts/generate.ps1`：一键重生成 + 写 `generated.sha256`（无 BOM）。
- `scripts/contracts/check_contracts.py`：29 项检查（见下）。

## 2. 实测结果

```
checks=29 failures=0
RESULT PASS
```

覆盖：

| 类别 | 数量 | 例子 |
|---|---:|---|
| 样例双向校验（含 payload 级） | 14 | `invalid-extra-field.json` 被拒、`valid-ingest.json` 通过 |
| 事件 payload 定向校验 | 2 | `run.succeeded` 缺 `durationMs` → 拒绝 |
| 错误码注册表 | 27 | 重复/格式/HTTP 状态；fixture 里的码必须在表内 |
| TS 字段 parity | 3 | RunRequest 8 / RunResponse 7 / RunEvent 7 |
| Python 字段 parity | 3 | 同上 |
| Java 字段 parity | 2 | ApiResponse 4 / ApiErrorResponse 5 |
| Mock 同源 | 2 | fixtures 与 contracts/examples 逐字节一致；mock 不内联数据 |
| 生成可重复 | 1 | 7 个文件 hash 与记录一致（连跑两次同 hash） |

Mock 冒烟（`logs/mock-smoke.log`）：

- 合法 POST → `202` + `{"status":"QUEUED"}`
- schema 非法（多 `apiKey` 字段）→ `400 VALIDATION_FAILED`
- `GET /v1/runs/run_demo` → `200` 快照（usage + manifest）
- SSE → `id: 7 / event: run.succeeded`、`id: 8 / event: run.failed`、`": keep-alive"`
- `/api/ping` → `{"code":"OK", ...}` 公开壳

## 3. 过程中修掉的真实问题

1. **Python 描述乱码**：Windows 默认 GBK，datamodel-codegen 把中文 description 读成乱码 → 生成脚本设 `PYTHONUTF8=1`。
2. **根模型叫 `Model`**：`allOf` 根节点没有名字 → 给 schema 补 `title` 并加 `--class-name`。
3. **`extra='allow'`**：原来传了 `--allow-extra-fields`，与"严格拒绝未知字段"的契约相反 → 去掉，生成 `extra='forbid'`。
4. **可重复性误报**：`generated.sha256` 被 PowerShell 写成带 BOM，第一行 hash 多了 `\ufeff` → 改无 BOM 写入 + `utf-8-sig` 读取。
5. **parity 解析器 bug**：TS 交叉类型里多个 interface 混在一起、Java `Map<String, Object>` 的逗号被切开 → 改成按花括号配对截块 / 按泛型深度切分。

这五个都是"看起来绿了但其实是假绿"的典型，修完才算真通过。

## 4. 为什么 Java 只做了响应壳

Java 目前**没有 Runner 客户端**（V01-T02 只有 /api/ping），如果现在生成 `RunRequest/RunResponse/RunEvent` DTO，它们没有任何消费者，也没有端点验证，等于摆着好看。因此本任务只做：

- `ApiResponse<T>` / `ApiErrorResponse`（V01-T04 认证与所有公开接口马上要用）；
- 两个壳的字段级 parity 检查（schema 一改，检查就红）。

Runner 客户端 DTO 在 V03 实现 run 调用时生成，并把 parity 检查扩到那三个类。这个取舍写进 `knownLimitations`，不假装"三端全部生成完毕"。

## 5. 未验证

- 前端 `tsc`/Vitest：`node_modules` 未安装（要等 F00/CI）。
- 真实 Runner 的 run 端点：Runner 目前只有 `/ping`、`/v1/health`，run 路由在 V03。
- `scripts/accept.py`：V01-T04 才实现。

## 6. 下一步

V01-T04（认证与验收基础）：最小 session 登录、CSRF、CI 检查、`scripts/accept.py`（把本任务的 generate/check/mock-smoke 纳入离线验收，并做到"无测试即失败"）。
