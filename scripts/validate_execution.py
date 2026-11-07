"""Validate handoff metadata and dispatch behavior; never connect to application services."""
import copy
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from build_task_prompts import render
from plan_dispatch import conflicts, dispatch, normalized_path

ROOT = Path(__file__).resolve().parents[1]


def read(relative):
    """Read one local specification file without mutating task state."""
    return json.loads((ROOT / relative).read_text(encoding="utf-8"))


def validate_sql():
    """Check this fixed DDL's comments, FK columns and unique target keys, not MySQL syntax."""
    sql = (ROOT / "contracts/mysql-schema.sql").read_text(encoding="utf-8")
    blocks = re.findall(r"CREATE TABLE (\w+) \((.*?)\n\) ENGINE=.*?;", sql, re.S)
    assert len(blocks) == 43
    tables, count = {}, 0
    for name, body in blocks:
        columns = set()
        unique = []
        for line in body.splitlines():
            match = re.match(r"  (\w+)\s+(?:VARCHAR|CHAR|BIGINT|INT|TINYINT|BOOLEAN|DATETIME|DATE|DECIMAL|TEXT|MEDIUMTEXT|JSON)\b", line)
            if match:
                columns.add(match[1])
                assert " COMMENT '" in line, (name, match[1])
                if " PRIMARY KEY" in line:
                    unique.append((match[1],))
            match = re.search(r"(?:UNIQUE KEY \w+|PRIMARY KEY) \(([^)]+)\)", line)
            if match:
                unique.append(tuple(part.strip() for part in match[1].split(",")))
        tables[name] = {"columns": columns, "unique": unique}
        count += len(columns)
    refs = 0
    for source, body in blocks:
        for local, target, remote in re.findall(r"FOREIGN KEY \(([^)]+)\) REFERENCES (\w+)\(([^)]+)\)", body):
            left = tuple(x.strip() for x in local.split(","))
            right = tuple(x.strip() for x in remote.split(","))
            assert len(left) == len(right)
            assert set(left) <= tables[source]["columns"], source
            assert target in tables and set(right) <= tables[target]["columns"]
            assert right in tables[target]["unique"], (source, target, right)
            refs += 1
    for source, local, target, remote in re.findall(r"ALTER TABLE (\w+) ADD CONSTRAINT \w+\s+FOREIGN KEY \(([^)]+)\) REFERENCES (\w+)\(([^)]+)\)", sql):
        assert set(x.strip() for x in local.split(",")) <= tables[source]["columns"]
        assert tuple(x.strip() for x in remote.split(",")) in tables[target]["unique"]
        refs += 1
    assert count == 380
    assert len(re.findall(r"\) ENGINE=[^;]+ COMMENT='[^']+';", sql)) == 43
    executable = re.sub(r"--[^\n]*", "", sql)
    assert not re.search(r"\b(DROP|TRUNCATE|DELETE)\b", executable, re.I)
    return {"tables": len(tables), "commentedColumns": count, "foreignKeysChecked": refs,
            "mysqlExecuted": False, "scope": "fixed-format static checks, not a SQL engine"}


def validate_dispatch(tasks, coord):
    """Use synthetic state to test prerequisites, genuine parallelism and rejection paths."""
    checks = 0
    state = {"baseline": "SYNTHETIC-TEST-ONLY", "tasks": {}, "gates": {}, "claims": []}

    def done(key):
        """Mark only synthetic fixture tasks complete; never write a real task file."""
        state["tasks"][key] = {"status": "DONE", "evidence": "synthetic integrated fixture"}

    def accept(key):
        """Create a synthetic gate record for dependency tests."""
        state["gates"][key] = {"status": "ACCEPTED", "evidence": "synthetic gate fixture"}

    assert dispatch(tasks, coord, {})["ready"] == ["V01-T01"]
    checks += 1
    for key in ["V01-T01", "V01-T02", "V01-T03", "V01-T04"]:
        done(key)
    accept("R1-CONTRACT")
    done("V02-T01")
    assert dispatch(tasks, coord, state, ["V02-T02", "V03-T01"])["batchAllowed"]
    checks += 1
    for round_number, frontier in [(2, ["V04-T01", "V05-T01", "V06-T01"]),
                                   (3, ["V07-T01", "V08-T01", "V09-T01"]),
                                   (4, ["V10-T01", "V11-T01", "V12-T01"])]:
        previous = f"R{round_number - 1}"
        for task in tasks.values():
            if task["id"].startswith("V") and task["round"] == previous:
                done(task["id"])
        accept(previous + "-ACCEPTED")
        assert not dispatch(tasks, coord, state, frontier)["batchAllowed"]
        accept(f"R{round_number}-CONTRACT")
        assert dispatch(tasks, coord, state, frontier)["batchAllowed"]
        checks += 2
    broken = copy.deepcopy(state)
    broken["tasks"]["V10-T04"] = {"status": "DONE", "evidence": "synthetic"}
    try:
        dispatch(tasks, coord, broken)
    except ValueError:
        checks += 1
    else:
        raise AssertionError("Accepted DONE without completed prerequisites")
    claimed = copy.deepcopy(state)
    claimed["claims"] = [{"taskId": "V10-T01", "ownerId": "old-window", "status": "STALE"}]
    assert "V10-T01" not in dispatch(tasks, coord, claimed)["ready"]
    checks += 1
    assert conflicts({"paths": ["frontend/src/"]}, {"paths": ["FRONTEND/src/features/cards/"]})
    assert not conflicts({"paths": ["runner/tools/"]}, {"paths": ["runner/toolsmith/"]})
    checks += 2
    for bad in ["../secrets", "C:/Users/data", "/etc", "backend/*"]:
        try:
            normalized_path(bad)
        except ValueError:
            checks += 1
        else:
            raise AssertionError("Unsafe path accepted: " + bad)
    return checks


def main():
    """Validate execution assets and print honest design-only results."""
    tasks = {task["id"]: task for task in read("tasks/tasks.json")["tasks"]}
    coord = read("tasks/coordination.json")
    assert len(coord["rounds"]) == 4 and len(coord["gates"]) == 8
    gates = {gate["id"] for gate in coord["gates"]}
    for task in tasks.values():
        assert set(task["requiresGates"]) <= gates
        assert task["leadRole"] in coord["roles"]
        assert task["instructions"] and task["paths"] and task["exclusiveResources"]
        assert (ROOT / task["promptPath"]).is_file()
        for path in task["paths"]:
            normalized_path(path)
    pages = read("design/page-contracts.json")["pages"]
    frontend = read("tasks/frontend-tasks.json")["tasks"]
    assert len(pages) == len(frontend) == 16
    assert {p["id"] for p in pages} == {t["id"] for t in frontend}
    page_map = {p["id"]: p for p in pages}
    visited = set()

    def visit(key, active):
        """Reject missing page dependencies and cyclic component prerequisites."""
        assert key in page_map and key not in active
        if key in visited:
            return
        for dep in page_map[key]["dependsOnPages"]:
            visit(dep, active | {key})
        visited.add(key)

    for page in pages:
        visit(page["id"], set())
        assert page["parentTask"] in tasks and page["requiredStates"]
    tokens = read("design/tokens.json")
    assert tokens["pagination"] == {"default": 20, "choices": [10, 20, 50], "maximum": 50}
    assert tokens["motion"]["flipMs"] == 280
    for svg in (ROOT / "design").glob("*.svg"):
        ET.parse(svg)
    assert len(list((ROOT / "design").glob("*.svg"))) == 4
    report = {"status": "PASS", "scope": "design-and-dispatch-only", "coreTasks": 48,
              "labTasks": 8, "frontendSlices": 16, "rounds": 4, "gates": 8,
              "promptCheck": render(check=True), "dispatchBehaviorChecks": validate_dispatch(tasks, coord),
              "sqlStaticChecks": validate_sql(), "applicationTestsRun": False}
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
