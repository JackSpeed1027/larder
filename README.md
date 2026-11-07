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
| V01-T03 契约/Mock/类型生成 | 未开始 | — |
| V01-T04 认证与验收基础 | 未开始 | — |

V01-T02 的可信结论（`reports/V01-T02/20261107-0010-v01t02/`）：

- `docker compose up -d --wait` → **八服务全部 healthy**（web/api/runner/mysql/redis/rabbitmq/meili/minio）；
- Web→API→MySQL 真实链路：`GET /api/ping` 经 nginx 反代到 Spring Boot，读到 Flyway 写入的 `app_meta.schema_version=V1`；
- `flyway_schema_history` 在真实 MySQL 8.4.13 上是 `1 1`（迁移成功）；
- `scripts/dev/verify.ps1` 10 项检查全过、exit 0；
- 前端真实 `tsc --noEmit && vite build`，由 nginx 提供静态文件。

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
| [11 四轮十二版本与并行DAG](docs/11-四轮十二版本与并行DAG.md) | 依赖、并行波次、每版本门禁 |
| [12 多窗口协作与集成协议](docs/12-多窗口协作与集成协议.md) | 文件锁、contractRev、CR、handoff 字段 |
| [AGENTS](AGENTS.md) | 授权边界、数据保护、架构硬边界 |
| [versions.lock.json](docs/versions.lock.json) | 唯一版本权威（45 个组件、digest、许可、验证状态） |
| [ADR 索引](docs/adr/versions/ADR-0001-java-boot-mybatis-flyway.md) | 版本、许可、维护风险（0002 Boot 4、0003 MinIO） |

规格优先级：用户最新要求 > AGENTS > 04+机器 schema > 16+DDL > 02/03 机制 > 14/15+design 前端 > 11+tasks 派工 > 12 协作 > 19 递进边界 > 20 注释。

## 不做什么

不自动 commit/push/发布；不清理或覆盖用户原件；不用 `docker compose down -v`、数据库 reset 修故障；不用 Mock 冒充真实集成；不在缺依赖时伪造 digest、性能数字或评测结论；不提前生成后续版本的业务模块。
