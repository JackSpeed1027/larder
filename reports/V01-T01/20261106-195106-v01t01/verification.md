# V01-T01 验证报告：版本与兼容性冻结

- runId：`20261106-195106-v01t01`
- 结论：**TASK_LOCAL_PASS**，其中 12 项运行期验证 **BLOCKED**（本机无 Docker / MinIO 镜像下线），不构成完整验收。
- 版本权威：[`docs/versions.lock.json`](../../../docs/versions.lock.json)
- 决策记录：[`docs/adr/versions/`](../../../docs/adr/versions/)

## 1. 本机环境（决定"能验证什么"）

| 项 | 值 | 证据 |
|---|---|---|
| OS | Windows 11 (10.0.26200) | `environment.txt` |
| JDK | Temurin 21.0.12.1+1 (LTS) | `environment.txt` |
| Maven | 3.9.11 | `environment.txt` |
| Node / pnpm | 24.22.0 / 11.25.0 | `environment.txt` |
| Python（系统） | 3.13.16 | `environment.txt` |
| uv | 0.13.1 | `environment.txt` |
| Docker | **不存在**（CLI 未安装、Desktop 未装、WSL 无发行版） | `environment.txt` |

Docker 的缺失是本次绝大多数 BLOCKED 的直接原因，不是"环境差不多就行"的借口：`docs/06` 要求的 MySQL/Redis/RabbitMQ/Meili/S3 行为全部依赖真实容器。

## 2. 已验证（可证伪、有原始日志）

| # | 断言 | 方式 | 结果 | 日志 |
|---|---|---|---|---|
| 1 | Java 21 + Boot 3.5.16 + MyBatis-Plus 3.5.18 + Flyway 11.7.2 组合能编译并解析依赖 | `mvn dependency:list` | exit 0 | `logs/mvn-dependency-list.log`、`logs/dependency-list.txt` |
| 2 | 真实 Spring 上下文能启动，Security/AMQP/Actuator/MyBatis-Plus 自动配置装配成功 | `@SpringBootTest` | 2/2 通过 | `logs/mvn-test.log` |
| 3 | Flyway 能发现并执行迁移，`flyway-mysql` 方言模块在类路径 | 断言 `MySQLDatabaseType` 可加载 + 迁移插入种子行 | 通过 | `logs/mvn-test.log` |
| 4 | MyBatis-Plus `BaseMapper` 能真实执行 SQL（不是只装配接口） | `selectCount`/`selectList` 读回种子行 | 通过 | `logs/mvn-test.log` |
| 5 | 运行期版本自证：`SpringBootVersion=3.5.16`、`flyway-core-11.7.2.jar`、`mybatis-plus-core-3.5.18.jar`、`s3-2.57.10.jar`、JDK 21.0.12.1 | 测试内断言 + 打印 | 通过 | `logs/mvn-test.log` |
| 6 | Python 3.12 解释器可获取并能建 venv | `uv venv --python 3.12` | exit 0，CPython 3.12.15 | `logs/uv-venv.log` |
| 7 | FastAPI 0.162.0 / uvicorn 0.54.0 / pydantic 2.14.3 / pydantic-settings 2.16.2 / httpx 0.28.1 可同装无冲突 | `uv pip install` 精确版本 | exit 0 | `logs/uv-pip-install.log` |
| 8 | FastAPI 路由真实响应 + Pydantic 未知字段拒绝 | `smoke.py`（TestClient + `extra=forbid`） | 4 项检查全过 | `logs/smoke.log` |
| 9 | 前端 React 19.3.1 / Vite 8.3.6 / plugin-react 6.2.1 / TS 7.0.2 / Tailwind 4.3.4 / TanStack 5.106.1 / Router 8.5.1 / Zustand 5.0.16 / Vitest 5.1.0 组合可解析 | `pnpm install --lockfile-only`（会执行 peer/engines 检查） | exit 0，116 包 | `logs/pnpm-install-lockfile-only.log`、`compat-check-frontend/pnpm-lock.yaml` |
| 10 | 锁清单自身可信：无 latest/范围符、每个 pin 与 digest 都能在当日注册源快照里找到、BLOCKED 都有原因 | `tools/validate_versions_lock.py` | 39 组件 / 12 BLOCKED / 7 ADR，PASS | `validate.log` |

## 3. 未验证（BLOCKED）与原因

| 组件/行为 | 状态 | 原因 | 谁来补 |
|---|---|---|---|
| MySQL 8.4 真实连接、迁移、索引长度、事务语义 | BLOCKED | 无 Docker | V01-T02 |
| RabbitMQ confirm/return/manual ack/DLQ | BLOCKED | 无 Docker | V03 |
| Redis Lua 限流/超时降级/缓存回源 | BLOCKED | 无 Docker | V03/V07 |
| Meilisearch 异步 task / 向量维度 / 检索参数 | BLOCKED | 无 Docker | V04/V07 |
| S3 读写/分片/校验/预签名 | BLOCKED | 无 S3 端点（MinIO 镜像下线，ADR-0003） | V02 |
| Testcontainers 真实容器语义 | BLOCKED | 依赖 Docker | V01-T02/L08 |
| 镜像拉取与 `docker image inspect` 校验 | BLOCKED | 无 Docker | V01-T02 |
| 前端 `vite build` / `tsc` / Vitest / 浏览器 E2E | BLOCKED | 无前端工程（V01-T02 才建） | V01-T02/T03 |
| Runner pytest | BLOCKED | 无 runner 工程 | V01-T02 |
| `scripts/accept.py` 验收入口 | NOT_IMPLEMENTED | 属 V01-T04 | V01-T04 |

## 4. 关键判断（为什么不是"直接升级"）

1. **Boot 3.5 已 EOL（2026-06-30）**：3.5.16 是最后补丁，4.1.2 才是有支持的线。本任务按 `docs/02` 的"不自动升 Boot4"保持锁定，并把风险写成 ADR-0002 交给用户决定，而不是自行换栈。
2. **Flyway 13 / Testcontainers 2 已有新版但不采用**：Boot 3.5 的自动配置是对着 11.x / 1.21.x 写的，单独升级属于手工混配。
3. **MinIO 服务端镜像已不可得**：Docker Hub 404、quay 401。对象存储能力不能删，因此改为"本地验证用可固定 digest 的替代构建 + S3 适配接口"，ADR-0003 列出候选并等用户决定。
4. **S3 客户端改用 AWS SDK v2**：端点可换、维护活跃、许可 Apache-2.0，业务侧只依赖 `ObjectStore` 端口（ADR-0004）。

## 5. 需要用户决定的两件事

1. **对象存储**（阻塞 V01-T02）：接受 `bitnamilegacy/minio:2025.7.23-debian-12-r5` 固定 digest 作为本地验证对象存储，还是直接改用 SeaweedFS / Garage / RustFS？（ADR-0003）
2. **Boot 升级时机**：R1 保持 3.5.16 → R1 后升 4.1.x → 还是 V02 前就升？（ADR-0002）

在得到回复前，V01-T02 可以推进除"对象存储服务"之外的部分；Docker 环境必须先补齐，否则八服务验证无法完成。
