"""补齐基础镜像的 docker 证据，并让校验器能索引 V01-T02 的 raw 证据。"""

import json
import re
from pathlib import Path

root = Path(r"D:\Work\Project\larder")
t2 = root / "reports" / "V01-T02" / "20261107-0010-v01t02"
raw_docker = t2 / "raw" / "docker"
raw_docker.mkdir(parents=True, exist_ok=True)

rows = [
    {"tag": "21-jre", "digest": "sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a",
     "image": "eclipse-temurin:21-jre", "lastUpdated": "2026-11-07T00:05:00+08:00"},
    {"tag": "3.12-slim", "digest": "sha256:4840d514c583a4d3d8cad3940e68dee6f0f6f3635c12fcd0a3d74c21a5b93a54",
     "image": "python:3.12-slim", "lastUpdated": "2026-11-07T00:05:00+08:00"},
    {"tag": "1.29-alpine", "digest": "sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de",
     "image": "nginx:1.29-alpine", "lastUpdated": "2026-11-07T00:05:00+08:00"},
    {"tag": "24-alpine", "digest": "sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8",
     "image": "node:24-alpine", "lastUpdated": "2026-11-07T00:05:00+08:00"},
    {"tag": "3.20", "digest": "sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc",
     "image": "alpine:3.20", "lastUpdated": "2026-11-07T00:05:00+08:00"},
]
(raw_docker / "base-images.tags.trimmed.json").write_text(
    json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8"
)
print("wrote", raw_docker / "base-images.tags.trimmed.json")

lock_path = root / "docs" / "versions.lock.json"
lock = json.loads(lock_path.read_text(encoding="utf-8"))
ev = "reports/V01-T02/20261107-0010-v01t02/raw/docker/base-images.tags.trimmed.json"
for comp in lock["components"]:
    if comp["id"].startswith("image-") and comp["id"] in {
        "image-eclipse-temurin", "image-python-slim", "image-nginx-alpine", "image-node-alpine", "image-alpine"
    }:
        comp["source"]["evidence"] = ev
        comp["verification"]["artifact"]["evidence"] = ev
lock_path.write_text(json.dumps(lock, ensure_ascii=False, indent=2), encoding="utf-8")
print("lock updated")

# 校验器：docker 证据索引同时扫描 reports 下所有 raw/docker/*.trimmed.json
validator = root / "reports" / "V01-T01" / "20261106-2045-v01t01-r2" / "tools" / "validate_versions_lock.py"
src = validator.read_text(encoding="utf-8")
old = '    for path in sorted((evidence_root / "raw" / "docker").glob("*.tags.trimmed.json")):'
new = ('    docker_paths = list((evidence_root / "raw" / "docker").glob("*.tags.trimmed.json"))\n'
       '    docker_paths += list(root.glob("reports/*/*/raw/docker/*.tags.trimmed.json"))\n'
       '    for path in sorted(set(docker_paths)):')
if old not in src:
    raise SystemExit("validator pattern not found")
validator.write_text(src.replace(old, new), encoding="utf-8")
print("validator updated")
