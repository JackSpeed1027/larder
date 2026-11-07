# V01-T02 验证报告：八服务与工程骨架

- runId：`20261107-0010-v01t02`
- 结论：**TASK_LOCAL_PASS**——八服务全部 healthy，Web→API→MySQL 真实链路通，Flyway 在真实 MySQL 8.4 上执行成功，前端真实构建、Runner 健康检查、MinIO 容器运行均通过。
- 依赖：V01-T01（版本锁 + MinIO 源码构建）

## 1. 交付内容

| 目录/文件 | 内容 |
|---|---|
| `backend/` | Spring Boot 4.1.2、MyBatis-Plus boot4 3.5.18、Flyway 12.4.0、`/api/ping`（读 `app_meta`）、`/actuator/health`、`mvnw` 包装器（固定 3.9.11）、Dockerfile |
| `runner/` | FastAPI 0.162.0、`/ping`、`/v1/health`、pytest 用例、`uv.lock`、Dockerfile |
| `frontend/` | Vite 8 + React 19 + TypeScript 7 + Tailwind 4 骨架页（调用 `/api/ping` 展示 schema 版本）、`pnpm-lock.yaml`、nginx 配置与 Dockerfile |
| `compose.yaml` | 八服务：web / api / runner / mysql / redis / rabbitmq / meili / minio；只有 web 绑 `127.0.0.1:8080`；镜像按 digest 固定 |
| `compose.dev.yaml` | 开发端口覆盖（mysql/redis/rabbitmq/meili/minio/api/runner 绑 loopback；RabbitMQ 管理台用 15673，因为本机 15672 被占用） |
| `.env.example` / `.env` | LARDER_ 前缀；本机 `.env` 随机生成密码，不入库 |
| `infra/mysql/my.cnf` | Linux 部署用 MySQL 配置（Windows bind mount 会被 MySQL 以 world-writable 忽略，已在文件头与 compose 注释说明） |
| `infra/minio/Dockerfile` | alpine + 源码构建的 Linux MinIO 二进制 |
| `scripts/dev/` | `up.ps1`（默认发布拓扑，`-Dev` 才开开发端口）、`down.ps1`（不带 -v）、`verify.ps1`（10 项端到端自检）、`build-minio.ps1`（交叉编译 + sha256 校验） |

## 2. 实测结果

| # | 检查 | 结果 |
|---|---|---|
| 1 | `docker compose config --quiet` | exit 0 |
| 2 | `docker compose build`（api/runner/web/minio） | 四个镜像构建成功 |
| 3 | `docker compose up -d --wait` | 八服务全部 **healthy** |
| 4 | `web /healthz` | 200 `ok` |
| 5 | `web → api → mysql`（`/api/ping`） | `schemaVersion=V1` |
| 6 | `api /actuator/health` | `UP`（含 db） |
| 7 | `runner /v1/health` | `version=0.1.0 protocol=v1` |
| 8 | `minio /minio/health/live` | live |
| 9 | MySQL `flyway_schema_history` | `1 1`（V1 成功） |
| 10 | MySQL `app_meta.schema_version` | `V1` |
| 11 | `meili /health` | `available` |
| 12 | `rabbitmq-diagnostics ping` | pong |
| 13 | `redis-cli ping` | PONG |

`verify.ps1` 汇总：**10 项通过 / 0 失败 / exit 0**。

## 3. 构建期的三个真实坑（已修，写进交付）

1. **Docker Hub 直连被墙**：Docker Desktop 需要配 manual proxy（`127.0.0.1:7890`）；系统代理设置不会自动传给 Linux VM。已备份 `settings-store.json.bak-v01t02`。
2. **Boot 4 的 Flyway 自动配置拆包**：只加 `flyway-core` 时 Flyway bean 不存在、迁移不执行（V01-T01 已记录）；本任务用 `spring-boot-starter-flyway`，真实迁移成功。
3. **Meilisearch 镜像没有 bash**：最初的 `bash -c /dev/tcp` 健康检查永远不健康；改成镜像自带的 busybox `wget` 打真实 `/health` 后才 healthy。**没有放宽检查来伪造通过**。

另外两个小坑：`vite.config.ts` 用 `process.env` 缺 `@types/node`（改为固定 localhost 代理）、PowerShell 5.1 读取无 BOM 的 UTF-8 脚本会吞代码（scripts 统一转 UTF-8 with BOM）。

## 4. 未验证（如实标注）

- 后端**没有单元测试**：本任务只验证启动/迁移/探针；契约 DTO 与测试由 V01-T03 补，`accept.py` 的"无测试即失败"在 V01-T04。
- Testcontainers、前端 Vitest、浏览器 E2E 未跑。
- 业务读写（对象存储、MQ、缓存、检索）尚未接入，健康检查只证明进程与端口可用。
- 应用镜像以本地 tag 为准，尚未推送任何仓库。

## 5. 结论与下一步

V01-T02 的验收标准（八服务拓扑、真实 health、真实 MySQL 迁移、构建 wrapper 与隔离环境脚本）**全部满足**。下一步 V01-T03：从 `contracts/` 生成三端类型与 Mock，并补契约用例。
