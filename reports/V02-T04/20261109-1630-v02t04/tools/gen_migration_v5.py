"""从 contracts/mysql-schema.sql 抽取 organize 相关表，生成 Flyway V5 迁移。

包含 chunk：note.chunk_id 的外键指向 chunk(owner,item,revision,id)，
不先建 chunk 就无法建 note；chunk 数据仍由 V04 的索引构建写入。
"""

from pathlib import Path

root = Path(r"D:\Work\Project\larder")
schema = (root / "contracts" / "mysql-schema.sql").read_text(encoding="utf-8")
TABLES = ["chunk", "tag", "item_tag", "collection", "collection_item", "note", "reading_position"]


def extract(name: str) -> str:
    start = schema.find(f"CREATE TABLE {name} (")
    if start < 0:
        raise SystemExit(f"table not found: {name}")
    end = schema.find(") ENGINE", start)
    end = schema.find("\n", end)
    return schema[start:end].rstrip()


parts = [
    "-- V5 组织与阅读：chunk / tag / item_tag / collection / collection_item / note / reading_position",
    "-- 由 reports/V02-T04/<runId>/tools/gen_migration_v5.py 从 contracts/mysql-schema.sql 抽取。",
    "-- chunk 先建：note/reading_position 的外键依赖它（数据由 V04 索引构建写入）。",
    "",
]
for table in TABLES:
    parts.append(extract(table))
    parts.append("")

out = root / "backend/src/main/resources/db/migration/V5__organize.sql"
out.write_text("\n".join(parts), encoding="utf-8")
print("wrote", out, len(out.read_text(encoding="utf-8")), "chars")
