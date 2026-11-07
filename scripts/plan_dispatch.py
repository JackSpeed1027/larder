"""Read-only core-workpackage dispatcher. No claims, writes, Git actions, or execution."""
import argparse
import json
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]


def load_json(path):
    """读取协调者提供的JSON快照。"""
    return json.loads(path.read_text(encoding="utf-8"))


def normalized_path(value):
    """规范化允许路径并拒绝绝对路径、通配符和上级逃逸。"""
    value = value.replace("\\", "/").rstrip("/")
    path = PurePosixPath(value)
    if not value or path.is_absolute() or ":" in value or any(c in value for c in "*?[]") or ".." in path.parts:
        raise ValueError("Unsafe allowlist path: " + value)
    return str(path).casefold()


def paths_overlap(first, second):
    """按大小写不敏感的目录边界判定写入范围冲突。"""
    a, b = normalized_path(first), normalized_path(second)
    return a == b or a.startswith(b + "/") or b.startswith(a + "/")


def resource_overlap(first, second):
    # Same resource or parent/child namespace; unlike paths, resource names contain ':'.
    """识别相同或父子语义资源锁，弥补仅按文件路径检查的不足。"""
    a, b = first.casefold().rstrip("/"), second.casefold().rstrip("/")
    return a == b or a.startswith(b + "/") or b.startswith(a + "/")


def conflicts(first, second):
    """列出两个派工范围的文件及语义锁冲突。"""
    result = []
    for a in first.get("paths", []):
        for b in second.get("paths", []):
            if paths_overlap(a, b):
                result.append({"kind": "path", "first": a, "second": b})
    for a in first.get("exclusiveResources", []):
        for b in second.get("exclusiveResources", []):
            if resource_overlap(a, b):
                result.append({"kind": "resource", "first": a, "second": b})
    return result


def validate_state(tasks, coordination, state):
    """核实任务依赖、门禁及证据引用；引用存在不等于报告真实。"""
    known = set(tasks)
    gate_defs = {gate["id"]: gate for gate in coordination["gates"]}
    overrides = state.get("tasks", {})
    gates = state.get("gates", {})
    if set(overrides) - known or set(gates) - set(gate_defs):
        raise ValueError("Unknown task or gate in state")
    statuses = {key: "PLANNED" for key in known}
    for key, value in overrides.items():
        if not isinstance(value, dict) or value.get("status") not in coordination["taskStates"]:
            raise ValueError("Invalid task state: " + key)
        statuses[key] = value["status"]
        if value["status"] == "DONE" and not value.get("evidence"):
            raise ValueError("DONE requires integrated evidence reference: " + key)
    accepted = set()
    for key, value in gates.items():
        if not isinstance(value, dict) or value.get("status") not in {"PENDING", "ACCEPTED", "REJECTED"}:
            raise ValueError("Invalid gate state: " + key)
        if value["status"] == "ACCEPTED":
            if not value.get("evidence"):
                raise ValueError("ACCEPTED requires evidence reference: " + key)
            accepted.add(key)
    if (overrides or gates or state.get("claims")) and not state.get("baseline"):
        raise ValueError("Nonempty state requires integrated baseline identifier")
    for key in accepted:
        gate = gate_defs[key]
        if any(statuses[dep] != "DONE" for dep in gate["dependsOnTasks"]):
            raise ValueError("Gate accepted before prerequisite tasks: " + key)
        if set(gate["dependsOnGates"]) - accepted:
            raise ValueError("Gate accepted before prerequisite gates: " + key)
    for key, status in statuses.items():
        if status in {"IN_PROGRESS", "READY_FOR_INTEGRATION", "VERIFYING", "DONE"}:
            task = tasks[key]
            if any(statuses[d] != "DONE" for d in task["dependsOn"]):
                raise ValueError("Started task has unfinished dependencies: " + key)
            if set(task["requiresGates"]) - accepted:
                raise ValueError("Started task lacks gates: " + key)
    claims = state.get("claims", [])
    for claim in claims:
        if claim.get("taskId") not in known or not claim.get("ownerId"):
            raise ValueError("Claim requires known task and owner")
        if claim.get("status") not in {"ACTIVE", "STALE", "RELEASED"}:
            raise ValueError("Unknown claim status")
        for path in claim.get("paths", []):
            normalized_path(path)
    return statuses, accepted, claims


def dispatch(tasks, coordination, state, batch=None):
    """只读计算可派任务；STALE锁不自动释放，不触发执行。"""
    statuses, accepted, claims = validate_state(tasks, coordination, state)
    ready, blocked = [], {}
    for key, task in tasks.items():
        if statuses[key] != "PLANNED":
            continue
        reasons = ["dependency:" + dep for dep in task["dependsOn"] if statuses[dep] != "DONE"]
        reasons += ["gate:" + gate for gate in task["requiresGates"] if gate not in accepted]
        for claim in claims:
            if claim["status"] == "RELEASED":
                continue
            claimed_task = tasks[claim["taskId"]]
            # Explicit claim paths can narrow scope; missing paths use parent scope conservatively.
            scope = {"paths": claim.get("paths") or claimed_task["paths"],
                     "exclusiveResources": claim.get("exclusiveResources") or claimed_task["exclusiveResources"]}
            if claim["taskId"] == key or conflicts(task, scope):
                reasons.append("claim:" + claim["ownerId"] + ":" + claim["status"])
        if reasons:
            blocked[key] = reasons
        else:
            ready.append(key)
    pair_conflicts = []
    if batch:
        if len(batch) != len(set(batch)) or any(key not in tasks for key in batch):
            raise ValueError("Batch contains duplicate or unknown task")
        for index, first in enumerate(batch):
            for second in batch[index + 1:]:
                found = conflicts(tasks[first], tasks[second])
                if found:
                    pair_conflicts.append({"tasks": [first, second], "conflicts": found})
    invalid = [key for key in (batch or []) if key not in ready]
    return {"scope": "core-and-lab-workpackage-plan-only", "readOnly": True,
            "ready": ready, "blocked": blocked,
            "requestedBatch": batch or [], "batchNotReady": invalid,
            "batchConflicts": pair_conflicts,
            "batchAllowed": not invalid and not pair_conflicts,
            "limitations": "Evidence references are required but report truth is reviewed by Coordinator. Frontend slices need their own narrower assignment review; no auto-claim or auto-execution."}


def main():
    """执行本脚本的检查流程，结果仅适用于文档包而非产品验收。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, help="Coordinator snapshot; omitted = all tasks PLANNED")
    parser.add_argument("--batch", nargs="+", help="Check a proposed parallel batch")
    args = parser.parse_args()
    bundle = load_json(ROOT / "tasks/tasks.json")
    tasks = {task["id"]: task for task in bundle["tasks"]}
    coordination = load_json(ROOT / "tasks/coordination.json")
    state = load_json(args.state) if args.state else {}
    try:
        report = dispatch(tasks, coordination, state, args.batch)
    except ValueError as error:
        print(json.dumps({"status": "INVALID_STATE", "error": str(error)}, ensure_ascii=False, indent=2))
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["batchAllowed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
