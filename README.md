# Larder 拾藏

自托管的个人内容与学习工作台：收录网页、粘贴、PDF、视频链接及合法字幕/音频；版本化阅读、关键词与语义检索、集合标签笔记；可恢复的工具 Agent、人工审批、显式长期记忆与安全压缩；四卡型复习、翻卡、备份恢复和可复现评测。

关闭 AI 后基础内容库与手动学习仍可用。**当前状态：V01 的地基已通**——版本冻结、八服务拓扑、三端骨架、真实 MySQL 迁移与端到端健康检查都已在真实容器里跑通；业务功能（收录/检索/Agent/复习）尚未开始，任何"已实现/已评测"的说法以 `reports/` 里的真实证据为准。

## 目录结构

```text
D:\Work\Project\larder\
  AGENTS.md       开发约束（授权、数据保护、架构硬边界）
  README.md       本文件
  backend\        Java 21 / Spring Boot 4.1.2 模块化单体（V01-T02 骨架）
  runner\         Python 3.12 / FastAPI Runner（V01-T02 骨架）
  frontend\       React 19 / Vite 8 / TS 7 / Tailwind 4（V01-T02 骨架）
  contracts\      跨端契约：OpenAPI、JSON Schema、MySQL DDL、样例
  docs\           规范 + 交接资料导航 + 版本锁 + ADR
  tasks\          机器可读任务清单与门禁
  prompts\        72 张任务/页面 Prompt
  design\         页面合同、tokens、线框
  scripts\        设计包校验 + scripts/dev（up/down/verify/build-minio）
  infra\          mysql/my.cnf（Linux 部署用）、minio/Dockerfile
  reports\        实际执行产生的报告（<taskId>\<runId>\）
  handoffs\       任务交接：handoff.json + file-manifest.json
  compose.yaml    八服务发布拓扑（只有 web 绑 127.0.0.1:8080）
  compose.dev.yaml 开发端口覆盖（显式 -f 才加载）
  .env.example    LARDER_ 环境变量模板（.env 不入库）
```

依赖缓存、构建产物、运行数据、`.env` 与任何秘密一律不入库（见 `.gitignore`）。

## 当前实施阶段

| 任务 | 状态 | 产物 |
|---|---|---|
| V01-T01 版本与兼容性冻结 | **TASK_LOCAL_PASS**（R2：Boot 4.1.2 + MinIO） | `docs/versions.lock.json`、`docs/adr/versions/`、`reports/V01-T01/` |
| V01-T02 八服务与工程骨架 | **TASK_LOCAL_PASS** | `backend/`、`runner/`、`frontend/`、`compose.yaml`、`infra/`、`scripts/dev/`、`reports/V01-T02/` |
| V01-T03 契约/Mock/类型生成 | **TASK_LOCAL_PASS** | `contracts/`（27 个错误码、事件 payload 收紧）、三端生成类型、`scripts/contracts/` Mock 与 29 项检查、`reports/V01-T03/` |
| V01-T04 认证与验收基础 | **TASK_LOCAL_PASS** | `identity/` 认证、`scripts/accept.py`、`.github/workflows/ci.yml`、`reports/V01-T04/` |
| V02-T01 内容模型与幂等 | **TASK_LOCAL_PASS** | `content/model`、`pipeline/model`、`ingest`、`POST /api/items`、`V2__content_core.sql`、`reports/V02-T01/` |
| V02-T02 MinIO 不可变产物 | **TASK_LOCAL_PASS** | `content/storage`、`runner/artifacts`、`V3__artifacts.sql`、`reports/V02-T02/` |
| V03-T01 Outbox 投递与 confirm | **TASK_LOCAL_PASS** | `pipeline/outbox`、`scripts/dev/verify-outbox.ps1`、`reports/V03-T01/` |
| V03-T02 Inbox 及持久接管 | **TASK_LOCAL_PASS** | `pipeline/inbox`、`V4__inbox.sql`、`scripts/dev/verify-inbox.ps1`、`reports/V03-T02/` |
| V03-T03 lease/reconcile/SSE | **TASK_LOCAL_PASS**（Java slice） | `pipeline/lease`、`pipeline/reconcile`、`runner/client`、`scripts/dev/verify-lease.ps1`、`reports/V03-T03/` |
| V02-T03 真实网页/粘贴链路 | **TASK_LOCAL_PASS** | `runner/stages`、`content/publication`、`runner/ingest`、`scripts/dev/verify-publication.ps1`、`reports/V02-T03/` |
| V02-T04 阅读/标签/笔记基础 | **TASK_LOCAL_PASS**（后端 slice） | `organize`、`V5__organize.sql`、`scripts/dev/verify-organize.ps1`、`reports/V02-T04/` |

V01-T02 的可信结论（`reports/V01-T02/20261107-0010-v01t02/`）：

- `docker compose up -d --wait` → **八服务全部 healthy**（web/api/runner/mysql/redis/rabbitmq/meili/minio）；
- Web→API→MySQL 真实链路：`GET /api/ping` 经 nginx 反代到 Spring Boot，读到 Flyway 写入的 `app_meta.schema_version=V1`；
- `flyway_schema_history` 在真实 MySQL 8.4.13 上是 `1 1`（迁移成功）；
- `scripts/dev/verify.ps1` 10 项检查全过、exit 0；
- 前端真实 `tsc --noEmit && vite build`，由 nginx 提供静态文件。

V01-T03 的可信结论（`reports/V01-T03/20261107-1010-v01t03/`）：

- 契约补齐：27 个错误码注册表、run-response 的 usage/manifest/error 收紧、run-event 7 类 payload 定向校验；
- 三端类型由固定版本工具生成（TS/Python），Java 公开响应壳手写并做字段 parity；
- 29 项契约检查全过：14 条正反样例、错误码、三端字段一致、Mock 与 `contracts/examples` 逐字节同源、生成可重复；
- Mock 严格校验 `run-request`（未知字段 400），SSE 逐事件发送，contractRev 冻结为 `5719da4c…`。

V01-T04 的可信结论（`reports/V01-T04/20261108-0930-v01t04/`）：

- 认证：单管理员 + Cookie session（HttpOnly/SameSite）+ CSRF 双提交；未登录 401、缺 CSRF 403、登出 invalidate session，**6 个后端用例全过**；
- 验收入口 `scripts/accept.py`：六项检查（秘密扫描/后端/runner/前端/契约/compose），**零测试或缺环境一律失败**，`--self-check` 用 7 条断言证明判定有效；
- `--profile live` 明确 BLOCKED（未实现，不伪装跑过）；CI 工作流已提交（尚未实际触发）。

V02-T01 的可信结论（`reports/V02-T01/20261108-1545-v02t01/`）：

- `POST /api/items`：首次 202、同 key 同 body 重放 200、同 key 不同 body 409、同规范化 URL 去重 200；
- 8 个并发请求同一 URL → 全部 200 且 MySQL 只有 1 条 item（唯一键 `uk_item_url` 兜底）；
- V2 迁移从 `contracts/mysql-schema.sql` 生成六张表，真实 MySQL 执行成功，重启不重复执行；
- accept 五项全过（后端 21 用例 + 契约 29 项 + 内容端到端 9 项）。

V02-T02 的可信结论（`reports/V02-T02/20261108-2125-v02t02/`）：

- 产物 key 由内容 sha256 派生（`artifacts/{owner}/{前两位}/{sha256}`），服务端算 hash，声明的 hash 只用于比对；
- 先写不可变对象、再发布 DB 引用：对象写失败不落库，落库失败登记孤立对象（JSONL），不产生坏引用；
- 同内容重复接收幂等复用；同 key 不同内容拒绝覆盖；**从不把 multipart ETag 当 hash**；
- 真实 MinIO 集成测试 1/1 通过，accept 五项全过（后端 33 用例）。

V03-T01 的可信结论（`reports/V03-T01/20261109-0800-v03t01/`）：

- 短事务领取（`FOR UPDATE SKIP LOCKED`）+ 事务外投递；完成/失败更新带 `relay_token` 条件（fencing）；
- mandatory + correlated confirm + returns：**unroutable 即使 ack 也不标 SENT**，退避后进 DEAD；
- 真实 RabbitMQ 上验证：正常 SENT、broker 断开时保留未完成状态、恢复后补投（第 4 次尝试成功）、无路由事件最终 DEAD；
- accept 五项全过（后端 44 用例 + 投递端到端 6 项）。

V03-T02 的可信结论（`reports/V03-T02/20261109-0915-v03t02/`）：

- inbox 与 job 接管**同事务**：崩溃在提交前全部回滚，提交后重投只 ack、不重复执行；
- 重复消息（同 event_id 重发）不会重复接管：lease_token 不变、inbox 仍 1 行；
- 坏消息（缺 jobId/job 不存在/payload 冲突）进 DLQ 且不写 inbox；有界 prefetch + 手动 ack；
- accept 五项全过（后端 51 用例 + 消费端到端 6 项）。

V03-T03 的可信结论（`reports/V03-T03/20261109-1130-v03t03/`，Java slice）：

- 提交 fencing = `token + processing_epoch + run_id + 租约未过期`，旧 worker 的迟到提交更新 0 行；
- 对账把过期 RUNNING 收回（attempt+1 退避）并重写 outbox 事件重新派发，达到上限转 FAILED；
- SSE 每次连接先发权威 snapshot，支持 `Last-Event-ID` 重连；实测断流重连 `resumedFrom=0`；
- 真实环境实测：租约过期后 `lease_token 1 → 2`、`attempt=2`、`outbox=2`（对账+重派生效）；
- accept 五项全过（后端 64 用例 + 租约端到端 4 项）。

V02-T03 的可信结论（`reports/V02-T03/20261109-1520-v02t03/`）：

- Runner 侧 `fetch/extract/chunk`：**每跳 SSRF 校验**（含 169.254.169.254）、重定向 3 次上限、大小/类型限制；
- 发布事务：锁 job → 校验 runId+epoch → 存正文 artifact → 写不可变 revision → 绑定 → 移动指针 → job SUCCEEDED；
- 端到端实测：一次回传后 revision=1、指针切换、job=SUCCEEDED；重复回传 `REPLAYED` 不重复写；旧 epoch 409 且 revision=0；
- accept 六项全过（后端 74 用例 + runner 34 用例 + 发布端到端 4 项）。

V02-T04 的可信结论（`reports/V02-T04/20261109-1630-v02t04/`，后端 slice）：

- 阅读必须带固定 `revisionId`，正文从 MinIO 读并校验 sha256，不符即拒绝（不伪造正文）；
- 标签/笔记/集合/阅读位置全部按 owner 归属；空笔记、跨条目 revision 一律 400；
- 真实端到端 8 项：发布 revision → 列表 → 详情 → 阅读 → 阅读状态过滤 → 标签过滤 → 笔记 → 阅读位置；
- accept 六项全过（后端 84 用例）。**前端页面仍未做。****Python 侧 scheduler/cancellation/events 未做。**

## 本地运行

```powershell
# 首次：复制模板并填密码（.env 不入库）
Copy-Item .env.example .env

# 启动八服务（发布拓扑，仅 web 暴露 127.0.0.1:8080）
.\scripts\dev\up.ps1

# 开发拓扑（额外暴露 mysql/redis/rabbitmq/meili/minio/api/runner 到 loopback）
.\scripts\dev\up.ps1 -Dev

# 端到端自检
.\scripts\dev\verify.ps1

# 停止（不带 -v，数据卷保留）
.\scripts\dev\down.ps1
```

构建入口：`.\backend\mvnw.cmd -f backend\pom.xml package`、`pnpm --dir frontend build`、`uv run --directory runner pytest`。

## 环境事实

1. **Docker Hub 直连在本机被阻断**：Docker Desktop 需配 manual proxy（`127.0.0.1:7890`），系统代理不会自动进入 Linux VM。配置备份：`settings-store.json.bak-v01t02`、`daemon.json.bak-v01t02`。
2. **MinIO 无上游安全更新**（仓库归档、官方下载渠道关闭）：当前用归档源码固定 tag 构建（commit `9e49d5e7…`，Linux 二进制 sha256 `1511a2b1…`），V12 必须重新评估。
3. **本机 15672 被其它进程占用**：开发拓扑把 RabbitMQ 管理台映射到 `127.0.0.1:15673`。

## 文档导航

| 文档 | 作用 |
|---|---|
| [交接资料导航](docs/交接资料导航.md) | 交接包原始说明（已修正链接），是规格出处 |
| [项目记忆与交接](docs/项目记忆.md) | 跨窗口接手用：当前进度、关键决策、已知坑、运行与验证命令 |
| [接口调试请求集](docs/api.http) | 登录/收录/SSE/内部回传的可执行请求，配合 IDEA HTTP Client |
| [调试手册](docs/调试手册.md) | 端口/日志/数据库/RabbitMQ/MinIO/IDEA 断点/故障排查/巡检 SQL |
| [11 四轮十二版本与并行DAG](docs/11-四轮十二版本与并行DAG.md) | 依赖、并行波次、每版本门禁 |
| [12 多窗口协作与集成协议](docs/12-多窗口协作与集成协议.md) | 文件锁、contractRev、CR、handoff 字段 |
| [AGENTS](AGENTS.md) | 授权边界、数据保护、架构硬边界 |
| [versions.lock.json](docs/versions.lock.json) | 唯一版本权威（45 个组件、digest、许可、验证状态） |
| [ADR 索引](docs/adr/versions/ADR-0001-java-boot-mybatis-flyway.md) | 版本、许可、维护风险（0002 Boot 4、0003 MinIO） |

规格优先级：用户最新要求 > AGENTS > 04+机器 schema > 16+DDL > 02/03 机制 > 14/15+design 前端 > 11+tasks 派工 > 12 协作 > 19 递进边界 > 20 注释。

## 不做什么

不自动 commit/push/发布；不清理或覆盖用户原件；不用 `docker compose down -v`、数据库 reset 修故障；不用 Mock 冒充真实集成；不在缺依赖时伪造 digest、性能数字或评测结论；不提前生成后续版本的业务模块。
