"""V01-T02 完成后更新版本锁：把已真实运行的组件从 BLOCKED 改为 VERIFIED，并登记新固定的基础镜像。"""

import json
from pathlib import Path

root = Path(r"D:\Work\Project\larder")
lock_path = root / "docs" / "versions.lock.json"
lock = json.loads(lock_path.read_text(encoding="utf-8"))

R2 = "reports/V01-T01/20261106-2045-v01t01-r2"
T2 = "reports/V01-T02/20261107-0010-v01t02"
compose_up = f"{T2}/logs/compose-up.log"
verify_log = f"{T2}/logs/verify.log"

by_id = {c["id"]: c for c in lock["components"]}

def verify_runtime(cid: str, method: str, evidence: str = verify_log) -> None:
    comp = by_id[cid]
    comp["verification"]["runtime"] = {"status": "VERIFIED", "method": method, "evidence": evidence}

# 真实起来并跑过探针的组件
verify_runtime("docker-engine", "Docker 29.9.2 守护进程就绪；docker compose up -d --wait 八服务全部 healthy", compose_up)
verify_runtime("mysql-connector-j", "API 通过 Connector/J 9.7.0 连上 MySQL 8.4.13，执行 Flyway 迁移并查询 app_meta")
verify_runtime("mysql-image", "mysql:8.4.13 容器 healthy，Flyway 在真实库执行 V1 迁移成功", compose_up)
verify_runtime("redis-image", "redis:8.10.3 容器 healthy，redis-cli ping 返回 PONG", compose_up)
verify_runtime("rabbitmq-image", "rabbitmq:4.3.7-management 容器 healthy，rabbitmq-diagnostics ping 通过", compose_up)
verify_runtime("meilisearch-image", "meilisearch:v1.56.1 容器 healthy，/health 返回 available", compose_up)
verify_runtime("minio-server", "Linux 二进制打进 larder/minio 镜像，容器 healthy，/minio/health/live 通过", compose_up)

by_id["minio-server"]["binarySha256Linux"] = "1511a2b15f6513d10cf89d86f8a96e836dfb9bddf6e9c1f3e0c8d9e2cda1920a"
by_id["minio-server"]["containerImage"] = "larder/minio:RELEASE.2025-10-15T17-29-55Z"

# 新固定的基础镜像（此前只在 Dockerfile 里按 tag 引用，现已按 digest 固定并真实拉取）
base_images = [
    ("image-eclipse-temurin", "Eclipse Temurin 21 JRE 运行镜像", "eclipse-temurin:21-jre",
     "sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a",
     "https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags?page_size=100&name=21-jre", "GPL-2.0-with-classpath-exception"),
    ("image-python-slim", "Python 3.12 slim 运行镜像", "python:3.12-slim",
     "sha256:4840d514c583a4d3d8cad3940e68dee6f0f6f3635c12fcd0a3d74c21a5b93a54",
     "https://hub.docker.com/v2/repositories/library/python/tags?page_size=100&name=3.12-slim", "PSF-2.0"),
    ("image-nginx-alpine", "nginx 1.29 alpine（Web 静态服务）", "nginx:1.29-alpine",
     "sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de",
     "https://hub.docker.com/v2/repositories/library/nginx/tags?page_size=100&name=1.29", "BSD-2-Clause"),
    ("image-node-alpine", "Node 24 alpine（前端构建期）", "node:24-alpine",
     "sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8",
     "https://hub.docker.com/v2/repositories/library/node/tags?page_size=100&name=24-alpine", "MIT"),
    ("image-alpine", "Alpine 3.20（MinIO 运行镜像基底）", "alpine:3.20",
     "sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc",
     "https://hub.docker.com/v2/repositories/library/alpine/tags?page_size=5&name=3.20", "MIT"),
]
existing = {c["id"] for c in lock["components"]}
for cid, name, tag, digest, url, lic in base_images:
    if cid in existing:
        continue
    lock["components"].append({
        "id": cid,
        "kind": "image",
        "name": name,
        "pin": tag.split(":")[1],
        "digest": digest,
        "source": {"url": url, "retrievedAt": "2026-11-07T00:05:00+08:00",
                   "evidence": f"{T2}/logs/docker-images.txt"},
        "license": {"spdx": lic, "evidence": "镜像内 /usr/share/doc 或上游项目声明（本 run 未逐字核验）"},
        "maintenance": {"status": "SUPPORTED", "note": "V01-T02 按 digest 固定并真实拉取"},
        "verification": {
            "artifact": {"status": "VERIFIED", "method": "docker pull + docker image inspect RepoDigests", "evidence": f"{T2}/logs/docker-images.txt"},
            "runtime": {"status": "VERIFIED", "method": "作为 api/runner/web/minio 构建基底或运行基底实际使用", "evidence": compose_up},
        },
    })

# 仍无法验证的项
blocked_ids = {"spring-amqp", "testcontainers", "pytest", "vitest", "shadcn-cli"}
reasons = {
    "spring-amqp": "容器已跑，但 API 尚未接入 AMQP，confirm/ack/DLQ 行为未验证",
    "testcontainers": "未编写 Testcontainers 集成测试（V03 起）",
    "pytest": "runner 已有 pytest 用例但本任务未执行（V01-T03 起跑）",
    "vitest": "前端测试框架已装但无测试用例（F00/V01-T03）",
    "shadcn-cli": "尚未生成 shadcn 组件（F00 前端骨架）",
}
lock["blockedSummary"] = [{"id": i, "reason": reasons[i]} for i in sorted(blocked_ids)]

lock["artifacts"]["v01t02EvidenceRoot"] = T2
lock["generatedAt"] = "2026-11-07T00:20:00+08:00"
lock["revision"] = "R2 + V01-T02 运行期验证"
lock["limitations"] = [
    "Docker Hub 直连在本机被阻断，Docker Desktop 需要 manual proxy（127.0.0.1:7890）；这是环境事实，不影响镜像 digest 的可复核性（digest 已由真实拉取确认）。",
    "MySQL 的 my.cnf 在 Windows bind mount 下是 0777 会被 MySQL 忽略，字符集/时区改由 command 参数传入；Linux 部署仍用 my.cnf。",
    "镜像 digest 已在 docker image inspect 中确认；应用镜像（larder/api|runner|web|minio）是本地构建，尚未推送仓库。",
    "H2(MODE=MySQL) 的兼容脚手架仍在，但真实 MySQL 迁移已在本任务跑通。",
    "前端只验证构建与静态服务，未做浏览器 E2E；TypeScript 7.0.2 已真实通过 tsc --noEmit（vite build 内含）。",
    "MinIO 仍无上游安全更新（仓库归档），V12 需重新评估。",
]

lock_path.write_text(json.dumps(lock, ensure_ascii=False, indent=2), encoding="utf-8")
print("components:", len(lock["components"]))
print("blocked:", len(lock["blockedSummary"]), [b["id"] for b in lock["blockedSummary"]])
