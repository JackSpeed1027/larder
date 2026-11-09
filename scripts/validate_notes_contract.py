"""U00 契约冻结校验：schema 合法、正例通过、反例被拒、错误码已注册。

通过后写：reports/U00/<run>/verification.md、handoffs/U00/handoff.json、账本 U00 状态。
"""

from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
RUN = "20261109-2300-u00"
OUT = ROOT / "reports/U00" / RUN
OUT.mkdir(parents=True, exist_ok=True)
(ROOT / "handoffs/U00").mkdir(parents=True, exist_ok=True)

schema = json.loads((ROOT / "contracts/notes.schema.json").read_text(encoding="utf-8"))
Draft202012Validator.check_schema(schema)

checks: list[tuple[str, bool, str]] = []


def validate(def_name: str, fixture: str, expect_valid: bool) -> None:
    document = json.loads((ROOT / fixture).read_text(encoding="utf-8"))
    validator = Draft202012Validator({**schema, "$ref": f"#/$defs/{def_name}"})
    errors = list(validator.iter_errors(document))
    ok = (not errors) if expect_valid else bool(errors)
    detail = "accepted" if not errors else f"rejected({errors[0].message[:60]})"
    checks.append((f"{def_name} <- {Path(fixture).name}", ok, detail))


validate("NoteCreate", "contracts/examples/notes/valid-create-source.json", True)
validate("NoteCreate", "contracts/examples/notes/valid-create-standalone.json", True)
validate("NotePatch", "contracts/examples/notes/valid-patch.json", True)
validate("NotePatch", "contracts/examples/notes/invalid-patch-missing-version.json", False)

registry = json.loads((ROOT / "contracts/error-codes.json").read_text(encoding="utf-8"))
codes = {item["code"] for item in registry["codes"]}
required = [
    "NOTE_VERSION_CONFLICT", "NOTE_SOURCE_GONE", "NOTE_TOO_LARGE",
    "IDEMPOTENCY_IN_PROGRESS", "IDEMPOTENCY_PAYLOAD_MISMATCH", "VERSION_CONFLICT",
    "EXPORT_SOURCE_CHANGED_OR_GONE", "EXPORT_ATTACHMENT_MISSING", "EXPORT_TOO_LARGE",
    "EXPORT_VERIFY_FAILED", "EXPORT_RECEIPT_FAILED",
    "LOCAL_FS_UNSUPPORTED", "LOCAL_FS_PERMISSION_REQUIRED", "LOCAL_FS_PERMISSION_DENIED",
    "LOCAL_FS_WRITE_FAILED", "LOCAL_FS_CONTENT_CONFLICT",
]
missing = [code for code in required if code not in codes]
checks.append(("错误码注册（16 个 v4 码）", not missing, "all registered" if not missing else f"missing={missing}"))

failures = [f"{name}: {detail}" for name, ok, detail in checks if not ok]
lines = [
    "# U00 验证报告：笔记/导出契约冻结（草案）",
    "",
    f"- runId：`{RUN}`",
    "- 结论：" + ("**TASK_LOCAL_PASS**（契约冻结，非功能实现）" if not failures else "**TASK_LOCAL_FAIL**"),
    "- 本轮只冻结形状与错误码；未改后端实现、未执行迁移、未安装前端依赖。",
    "",
    "## 校验项",
    "",
    "| 项 | 结果 | 说明 |",
    "|---|---|---|",
]
for name, ok, detail in checks:
    lines.append(f"| {name} | {'PASS' if ok else 'FAIL'} | {detail} |")
lines += [
    "",
    "## 交付",
    "",
    "- `contracts/notes.schema.json`：NoteCreate / NotePatch / NoteDetail / NoteSource（draft 2020-12）",
    "- `contracts/examples/notes/*.json`：2 正例 + 1 正例 patch + 1 反例（缺 expectedVersion）",
    "- `contracts/error-codes.json` v2：新增 16 个笔记/导出/本地文件错误码",
    "- `docs/notes-api-freeze-v1.md`：版本/来源/CAS/软删/限额/迁移计划/未决项",
    "",
    "## 未做",
    "",
    "- 未实现笔记后端的 revision/CAS（U02）",
    "- 未执行 `V6__note_revision.sql`（U02 负责，已应用迁移只增不改）",
    "- 未安装 CodeMirror/shadcn/TanStack/IndexedDB（U01 spike）",
    "- 未做导出/本地写入（U07–U11）",
]
(OUT / "verification.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

files = ["contracts/notes.schema.json", "contracts/error-codes.json", "docs/notes-api-freeze-v1.md",
         "contracts/examples/notes/valid-create-source.json", "contracts/examples/notes/valid-create-standalone.json",
         "contracts/examples/notes/valid-patch.json", "contracts/examples/notes/invalid-patch-missing-version.json"]
manifest = [{"path": rel, "sha256": hashlib.sha256((ROOT / rel).read_bytes()).hexdigest()} for rel in files]

handoff = {
    "taskId": "U00", "sliceId": "U00.FREEZE", "result": "TASK_LOCAL_PASS" if not failures else "TASK_LOCAL_FAIL",
    "summary": "冻结笔记与导出契约草案：notes.schema.json、16 个错误码、正反 fixture、冻结说明与迁移计划；未实现功能",
    "checks": [{"command": "python scripts/validate_notes_contract.py", "exitCode": 0 if not failures else 1,
                "log": f"reports/U00/{RUN}/verification.md"}],
    "notRun": ["笔记后端 revision/CAS（U02）", "V6 迁移", "组件 spike（U01）", "导出（U07–U11）"],
    "files": manifest,
}
(ROOT / "handoffs/U00/handoff.json").write_text(json.dumps(handoff, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

ledger_path = ROOT / "tasks/execution-state-v4.json"
ledger = json.loads(ledger_path.read_text(encoding="utf-8-sig"))
u00 = ledger["tasks"]["U00"]
u00["status"] = "READY_FOR_INTEGRATION" if not failures else "BLOCKED"
u00["evidence"] = [f"reports/U00/{RUN}/verification.md", "contracts/notes.schema.json", "docs/notes-api-freeze-v1.md"]
if failures:
    u00["blockers"] = failures
ledger_path.write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

print(f"U00 checks: {sum(1 for _, ok, _ in checks if ok)}/{len(checks)} PASS")
for name, ok, detail in checks:
    print(("PASS " if ok else "FAIL ") + name + " -> " + detail)
raise SystemExit(0 if not failures else 1)
