"""从 contracts/mysql-schema.sql 抽取 artifact / revision_artifact，生成 Flyway V3 迁移。"""

from pathlib import Path

root = Path(r"D:\Work\Project\larder")
schema = (root / "contracts" / "mysql-schema.sql").read_text(encoding="utf-8")
TABLES = ["artifact", "revision_artifact"]


def extract(name: str) -> str:
    start = schema.find(f"CREATE TABLE {name} (")
    if start < 0:
        raise SystemExit(f"table not found: {name}")
    end = schema.find(") ENGINE", start)
    if end < 0:
        raise SystemExit(f"table end not found: {name}")
    end = schema.find("\n", end)
    return schema[start:end].rstrip()


parts = [
    "-- V3 产物：artifact / revision_artifact（V02-T02 MinIO 不可变产物）",
    "-- 由 reports/V02-T02/<runId>/tools/gen_migration_v3.py 从 contracts/mysql-schema.sql 抽取；",
    "-- 改列必须先改契约再重新生成。迁移只增不改。",
    "",
]
for table in TABLES:
    parts.append(extract(table))
    parts.append("")

out = root / "backend/src/main/resources/db/migration/V3__artifacts.sql"
out.write_text("\n".join(parts), encoding="utf-8")
print("wrote", out, len(out.read_text(encoding='utf-8')), "chars")
