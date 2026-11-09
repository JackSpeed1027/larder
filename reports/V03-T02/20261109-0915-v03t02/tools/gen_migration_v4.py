"""从 contracts/mysql-schema.sql 抽取 inbox，生成 Flyway V4 迁移。"""

from pathlib import Path

root = Path(r"D:\Work\Project\larder")
schema = (root / "contracts" / "mysql-schema.sql").read_text(encoding="utf-8")

start = schema.find("CREATE TABLE inbox (")
end = schema.find(") ENGINE", start)
end = schema.find("\n", end)
block = schema[start:end].rstrip()

parts = [
    "-- V4 消费去重：inbox（V03-T02 Inbox 及持久接管）",
    "-- 由 reports/V03-T02/<runId>/tools/gen_migration_v4.py 从 contracts/mysql-schema.sql 抽取；",
    "-- 改列必须先改契约再重新生成。迁移只增不改。",
    "",
    block,
    "",
]
out = root / "backend/src/main/resources/db/migration/V4__inbox.sql"
out.write_text("\n".join(parts), encoding="utf-8")
print("wrote", out, len(out.read_text(encoding="utf-8")), "chars")
