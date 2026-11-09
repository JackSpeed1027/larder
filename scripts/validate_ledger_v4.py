"""S00 v4 账本校验：字段完整性、状态枚举、DONE 证据、契约 hash 一致性。

只读本地文件，不连接数据库/队列，不修改账本。
用法：python scripts/validate_ledger_v4.py
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / "tasks/execution-state-v4.json"
CORE = ROOT / "tasks/tasks.json"
SUPPLEMENT = ROOT / "tasks/supplement-v4.json"

STATUS = {"PLANNED", "IN_PROGRESS", "READY_FOR_INTEGRATION", "VERIFYING", "DONE", "BLOCKED"}
REQUIRED = {"taskId", "status", "owner", "baseSnapshot", "contractRev", "evidence", "verifiedAt", "blockers", "dependsOn", "source"}


def contract_rev() -> str:
    digest = hashlib.sha256()
    for p in sorted((ROOT / "contracts").rglob("*")):
        if p.is_file():
            digest.update(p.relative_to(ROOT).as_posix().encode())
            digest.update(p.read_bytes())
    return digest.hexdigest()


def main() -> int:
    ledger = json.loads(LEDGER.read_text(encoding="utf-8-sig"))
    core = json.loads(CORE.read_text(encoding="utf-8"))
    supplement = json.loads(SUPPLEMENT.read_text(encoding="utf-8"))

    failures: list[str] = []
    assert ledger["specRev"] == "larder-spec-v4-2026-11-09", "specRev 不匹配"

    expected = {t["id"] for t in core["tasks"]} | {t["id"] for t in supplement["tasks"]}
    actual = set(ledger["tasks"])
    for missing in sorted(expected - actual):
        failures.append(f"账本缺少任务: {missing}")
    for extra in sorted(actual - expected):
        failures.append(f"账本存在未知任务: {extra}")

    for task_id, item in ledger["tasks"].items():
        if item.get("taskId") != task_id:
            failures.append(f"{task_id}: taskId 与键不一致")
        for field in REQUIRED:
            if field not in item:
                failures.append(f"{task_id}: 缺少字段 {field}")
        status = item.get("status")
        if status not in STATUS:
            failures.append(f"{task_id}: 非法状态 {status}")
        if status == "DONE":
            if not item.get("verifiedAt"):
                failures.append(f"{task_id}: DONE 但缺少 verifiedAt")
            if not item.get("evidence"):
                failures.append(f"{task_id}: DONE 但缺少 evidence")
            for rel in item.get("evidence", []):
                if not (ROOT / rel).exists():
                    failures.append(f"{task_id}: evidence 路径不存在 {rel}")
        if status == "BLOCKED" and not item.get("blockers"):
            failures.append(f"{task_id}: BLOCKED 但 blockers 为空")
        if status != "BLOCKED" and item.get("blockers"):
            failures.append(f"{task_id}: 非 BLOCKED 却有 blockers")

    if ledger.get("contractRev") != contract_rev():
        failures.append("contractRev 与 contracts/ 当前内容不一致（契约已变，需重新生成账本）")

    if failures:
        print(f"LEDGER INVALID ({len(failures)})")
        for line in failures:
            print("  -", line)
        return 1

    by_status: dict[str, int] = {}
    for item in ledger["tasks"].values():
        by_status[item["status"]] = by_status.get(item["status"], 0) + 1
    print(f"LEDGER OK: {len(ledger['tasks'])} tasks, contractRev={ledger['contractRev'][:12]}…")
    print("status:", ", ".join(f"{k}={v}" for k, v in sorted(by_status.items())))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
