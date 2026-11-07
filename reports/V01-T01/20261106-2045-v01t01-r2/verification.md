# V01-T01 R2 验证报告：Boot 4.1.2 升级 + MinIO 确认

- runId：`20261106-2045-v01t01-r2`（R1：`20261106-195106-v01t01`）
- 结论：**TASK_LOCAL_PASS**，11 项运行期验证 BLOCKED（Docker 守护进程未就绪 / 后续工程未建），不构成完整验收。
- 版本权威：[`docs/versions.lock.json`](../../../docs/versions.lock.json)
- 本 R2 由三条用户决定驱动：①用 MinIO；②Docker 可安装；③Boot 现在直接升级、保证兼容即可。

## 1. 与 R1 的差异

| 项 | R1 | R2 |
|---|---|---|
| Spring Boot | 3.5.16（EOL） | **4.1.2**（支持到 2027-07-31） |
| Spring Framework | 6.2.19 | 7.0.10 |
| Spring Security | 6.5.11 | 7.1.2 |
| Spring AMQP | 3.2.12 | 4.1.2 |
| Flyway | 11.7.2 | 12.4.0（必须 `starter-flyway` + `flyway-mysql`） |
| Testcontainers | 1.21.4 | 2.0.5 |
| JUnit | 5.12.2 | 6.0.3 |
| MyBatis-Plus | boot3 starter | **boot4 starter** 3.5.18 |
| 对象存储 | BLOCKED（MinIO 镜像下线） | **MinIO 源码固定构建，已跑通 S3 语义** |
| Docker | 未安装 | Desktop 4.98.0 / CLI 29.9.2 已装，守护进程待 WSL2+重启 |

## 2. 已验证（真实执行，有原始日志）

| # | 断言 | 结果 | 证据 |
|---|---|---|---|
| 1 | Boot 4.1.2 + Spring 7.0.10 + MyBatis-Plus boot4 + Flyway 12.4.0 能编译、启动、迁移、查询 | **3/3 通过，0 skip** | `logs/mvn-boot4-test.log` |
| 2 | 运行时版本自证：`SpringBootVersion=4.1.2`、`spring-context-7.0.10.jar`、`flyway-core-12.4.0.jar`、`mybatis-plus-core-3.5.18.jar`、`s3-2.57.10.jar` | 断言通过 | `logs/mvn-boot4-test.log` |
| 3 | AWS SDK v2 对 **真实 MinIO** 建桶/写入/读取/HEAD（path-style） | 通过 | `logs/mvn-boot4-test-s3.log` |
| 4 | MinIO 源码按固定 tag 构建，commit/sha256/版本自报可核对 | `RELEASE.2025-10-15T17-29-55Z`，sha256 `0347a92d…` | `minio-build.json` |
| 5 | MinIO S3 语义：建桶、put/get/head、预签名、**版本化保留 2 版本**、6MiB 对象 | 7/7 PASS | `logs/s3-smoke.log` |
| 6 | 锁清单自检 | PASS | `validate.log` |

## 3. 升级踩到的两个 Boot 4 硬差异

1. **Flyway 自动配置被拆走**：只加 `flyway-core` 时上下文能起、但 `Flyway` bean 不存在、迁移完全不执行。第一次运行 `<No qualifying bean of type 'org.flywaydb.core.Flyway'>` 就是这么来的；必须用 `spring-boot-starter-flyway`。
2. **MyBatis-Plus 必须换 boot4 starter**：`mybatis-plus-spring-boot3-starter` 引 Boot 3 的 autoconfigure API。

这两点已写进 ADR-0002，V01-T02 建工程时直接按正确依赖来，避免"服务起来了但迁移没跑"的假通过。

## 4. MinIO 现状（用户选择：继续用 MinIO）

官方交付渠道已全部关闭（本次实测）：`dl.min.io` **410**、GitHub release **assets 为空**、Docker Hub `minio/minio` **404**、quay **401**。因此：

- 采用 **源码固定 tag 构建**：commit `9e49d5e7…`、`go build -trimpath` + 版本 ldflags、产物 sha256 `0347a92d…`；
- 本机以原生进程跑通 S3 语义（boto3 + AWS SDK v2 两侧）；
- V01-T02 用同一二进制打镜像、compose 固定 digest；
- **风险**：上游已归档、无安全更新，V12 必须重新评估（ADR-0003 已写明）。

## 5. 仍未验证（BLOCKED）与补齐方式

| 项 | 原因 | 补齐 |
|---|---|---|
| MySQL/Redis/RabbitMQ/Meili/Testcontainers | Docker 守护进程未就绪 | 提权 `wsl --install` → 重启 → `docker compose up -d --wait` |
| MinIO 容器化 | 同上 | 用同一 `minio.exe` 构建镜像并核对 sha256 |
| 前端 build/tsc/Vitest | 无前端工程 | V01-T02/T03 |
| pytest | 无 runner 工程 | V01-T02 |
| `scripts/accept.py` | 未实现 | V01-T04 |

## 6. 下一步

1. 用户在**提权终端**执行 `wsl --install` 并重启（Docker Desktop 已装好，只差 WSL2 内核）。
2. 重启后我这边跑 `docker info` 确认守护进程，再做 V01-T02 的八服务拓扑与真实 MySQL 迁移。
3. 其余按 `tasks/tasks.json` 的 DAG 推进（V01-T03/T04）。
