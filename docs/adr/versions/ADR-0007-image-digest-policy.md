# ADR-0007 中间件镜像 digest 锁定策略与版本线选择

- 状态：ACCEPTED
- 日期：2026-11-06
- 任务：V01-T01
- 证据：`reports/V01-T01/20261106-195106-v01t01/raw/docker/*.tags.trimmed.json`、`fetch.log`

## 决策

1. 所有镜像**同时写 tag 与 sha256 digest**，compose 里固定到 digest（`image: mysql:8.4.13@sha256:...` 或直接写 digest）。禁止 `latest`、禁止只写 tag。
2. digest 只取 **registry API 当日返回的 manifest digest**，并在 `versions.lock.json` 的 `source.evidence` 指向原始 JSON；不复制、不手填、不猜测。
3. 多架构镜像的 digest 是 manifest list digest；部署时若固定架构，需另记 `images[].digest`（本 run 已存 amd64 digest 于 trimmed JSON）。
4. 运行期验证与构件验证分开：注册源存在只代表"artifact VERIFIED"，没在本机跑过就是 `runtime BLOCKED`。

## 版本线选择与理由

| 镜像 | 选定 | 备选 | 理由 |
|---|---|---|---|
| MySQL | `8.4.13` | 8.4.11 / 8.4.10 | 8.4 是 LTS，13 为 2026-11-03 最新补丁；8.4-oracle/8.4-oraclelinux9 指向同一 digest |
| Redis | `8.10.3` | 8.8.4 / 7.4.12 | 8.10 线最新补丁；许可见 ADR-0005；7.4 为非 OSI 许可，不选 |
| RabbitMQ | `4.3.7-management` | 4.4.0-management | 4.4.0 于 2026-10-28 发布（.0 版本），4.3.7 是成熟线的最新补丁（2026-11-04）；管理台只在开发 override 暴露 |
| Meilisearch | `v1.56.1` | v1.56.0 | 同线最新补丁（2026-11-02）；v1.56 与 v1.56.1 digest 不同，必须精确到补丁 |
| MinIO | `RELEASE.2025-10-15T17-29-55Z`（源码固定构建，sha256 `0347a92d…`） | — | 官方渠道全部关闭（dl.min.io 410 / GitHub assets 空 / Docker Hub 404 / quay 401），改为源码固定 tag 构建并锁 commit+sha256，见 ADR-0003 |

## 为什么不追新

- `4.4.0` 是刚发布的大版本首版，RabbitMQ 的 Erlang 兼容、插件与 management UI 行为需要单独验证；在 R1 阶段追首版只会把风险转移给 V03（Outbox/Inbox）。
- 版本冻结的意义是**让 V02–V12 的失败可归因**：如果后面 MQ 出问题，我们要能确定"不是今天顺手升级带来的"。

## 更新规则

- 补丁升级（同一 minor 线的新 patch）：允许，但必须重新拉 registry digest、更新 `versions.lock.json`、在报告里写清变更，并重跑对应服务的健康检查与至少一个行为用例。
- 大版本/次版本升级（如 RabbitMQ 4.3→4.4、MySQL 8.4→9.x）：必须先写 ADR，得到用户同意，再改 compose 与文档。
- 任何情况下不允许 `image: xxx:latest`，也不允许"临时用 latest 跑一下"。

## 未验证（BLOCKED）

全部镜像的运行期行为（启动、健康检查、初始化 bucket、迁移、DLQ、索引任务）仍为 BLOCKED。R2 已装 Docker Desktop 4.98.0（CLI 29.9.2），但守护进程依赖 WSL2：`wsl --install` 需提权并重启，当前进程未提权。V01-T02 必须在有 Docker 的环境执行 `docker compose config --quiet` 与 `up -d --wait`，并把真实日志写回 `reports/V01-T02/<runId>/`。
