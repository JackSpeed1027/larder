# ADR-0003 对象存储：采用 MinIO（源码固定构建 + 后续镜像路线）

- 状态：ACCEPTED（R2，已执行并验证）
- 日期：2026-11-06
- 任务：V01-T01 R2
- 证据：`reports/V01-T01/20261106-2045-v01t01-r2/minio-build.json`、`logs/s3-smoke.log`、`logs/mvn-boot4-test-s3.log`、`raw/minio/`

## 决定

**继续使用 MinIO 作为对象存储**（用户明确要求），不换 SeaweedFS / Garage / RustFS。

但官方交付渠道已经全部关闭，因此改用**从归档源码固定 tag 构建**，并用 commit + 二进制 SHA256 锁定：

| 项 | 值 |
|---|---|
| 源码 | `https://github.com/minio/minio`（已归档） |
| tag | `RELEASE.2025-10-15T17-29-55Z` |
| commit | `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a` |
| 构建 | `go1.27.3 windows/amd64`，`go build -trimpath -ldflags "-s -w -X .../cmd.Version=2025-10-15T17-29-55Z -X .../cmd.ReleaseTag=RELEASE.2025-10-15T17-29-55Z"` |
| 产物 | `minio.exe` 111,360,000 B，`sha256:0347a92d13165c0a1659ded11658f9993e3174dab3b8c746e7abf195bc9277a6` |
| 版本自报 | `RELEASE.2025-10-15T17-29-55Z (commit-id=DEVELOPMENT.GOGET)` |
| 许可 | AGPLv3 |

## 为什么必须自己构建

本 run 实测（全部有原始响应）：

- `https://dl.min.io/server/minio/release/windows-amd64/` → **410 Gone**（二进制下载站关闭）；
- `https://api.github.com/repos/minio/minio/releases/latest` → release 存在但 **assets 为空**（不再挂二进制）；
- `https://hub.docker.com/v2/repositories/minio/minio/tags` → **404**（镜像下架）；
- `https://quay.io/api/v1/repository/minio/minio/tag/` → **401**（需认证）。

也就是说：**今天没有任何"官方现成构建"可拉**。在这种情况下仍然"用 MinIO"的唯一诚实做法，是从归档源码在固定 tag 上构建，并记录可复核的 commit + 二进制哈希；而不是拿一个来路不明的 `minio/minio` 镜像或第三方打包件冒充官方构建。

## 验证（真跑过，不是"应该能用"）

1. 启动本地 MinIO（`--address 127.0.0.1:9000`），健康检查 `/minio/health/live` 返回 200；
2. boto3 冒烟（`logs/s3-smoke.log`）：建桶 200、put/get/head 一致、预签名 URL 可生成、**开启 versioning 后同一 key 保留 2 个版本**、6 MiB 对象写入读取正常；
3. Java 侧（`logs/mvn-boot4-test-s3.log`）：锁定的 AWS SDK v2 `2.57.10` 以 path-style 完成建桶/写入/读取/HEAD，**3 个测试 0 skipped**。

这证明 MinIO 能满足 V02 需要的"不可变对象 + 版本 + 大对象"语义，也证明确实是 MinIO（二进制自报版本）。

## 部署路线（V01-T02 执行）

1. 用同一 `minio.exe` 写一个最小 `Dockerfile`（`FROM scratch` 或 `debian-slim` + 拷贝二进制），镜像内**校验二进制 sha256**；镜像 tag 用版本号，compose 里固定 digest。
2. compose 服务名保持 `minio`，只暴露 9000 给内部网络、9001 管理台仅开发 override 限 loopback。
3. 初始化 bucket 用短命 init 服务；root 凭据只用于初始化/维护，应用侧用受限账号。
4. **Docker 守护进程当前未就绪**（WSL2 未启用），所以镜像构建与 compose 验证要等用户提权执行 `wsl --install` 并重启后补；在那之前 MinIO 以原生进程方式供本地验证。

## 风险与后果

- **无上游安全更新**：仓库归档，2025-10-15 之后不再有补丁。V12 必须重新评估：继续自建、换维护中的 S3 实现、或改用商业版；这一步不能悄悄跳过。
- 自建二进制只在本机 Windows 验证过；容器内的 glibc/时区/文件权限差异要在 V01-T02 用真实 compose 再验一次。
- 业务代码只依赖 `ObjectStore` 端口（ADR-0004），未来换实现只动 adapter。
- 不允许把"MinIO 已归档"当作删掉对象存储能力的理由；也不允许用其它镜像冒充 MinIO 却仍写 `minio` 服务名。
