"""从 contracts/mysql-schema.sql 抽取 V02-T01 需要的 6 张表，生成 Flyway V2 迁移。

为什么要"生成"而不是手抄：DDL 是 docs/16 的权威基线，手抄迟早与契约漂移；
抽取时保留原列注释/约束/外键顺序，保证与契约逐字一致。
"""

from pathlib import Path
import re

root = Path(r"D:\Work\Project\larder")
schema = (root / "contracts" / "mysql-schema.sql").read_text(encoding="utf-8")

TABLES = ["owner", "item", "content_revision", "job", "outbox", "idempotency_request"]


def extract(name: str) -> str:
    """按表名截取 CREATE TABLE 到匹配的 ) ENGINE 行（含）。"""
    start = schema.find(f"CREATE TABLE {name} (")
    if start < 0:
        raise SystemExit(f"table not found: {name}")
    end = schema.find(") ENGINE", start)
    if end < 0:
        raise SystemExit(f"table end not found: {name}")
    end = schema.find("\n", end)
    return schema[start:end].rstrip()


parts = [
    "-- V2 内容核心：owner / item / content_revision / job / outbox / idempotency_request",
    "-- 由 reports/V02-T01/<runId>/tools/gen_migration.py 从 contracts/mysql-schema.sql 抽取，",
    "-- 与 docs/16 的 DDL 基线保持逐字一致；修改列必须先改契约再重新生成。",
    "-- 迁移只增不改：V1 已应用，本文件后续只能通过 V3+ 前滚修复。",
    "",
]
for table in TABLES:
    parts.append(extract(table))
    parts.append("")

out = root / "backend/src/main/resources/db/migration/V2__content_core.sql"
out.write_text("\n".join(parts), encoding="utf-8")
print(f"wrote {out} ({len(parts)} parts)")
