"""Offline handoff checks, NOT application tests or a general JSON Schema validator."""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    """读取本地JSON，拒绝非标准数值常量。"""
    return json.loads(path.read_text(encoding="utf-8"),
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def check(value, schema):
    """Validate only the schema keywords used by this package's fixed fixtures."""
    if "const" in schema and value != schema["const"]:
        return False
    if "enum" in schema and value not in schema["enum"]:
        return False
    kinds = {"object": dict, "array": list, "string": str, "integer": int, "boolean": bool}
    kind = schema.get("type")
    if kind and (not isinstance(value, kinds[kind]) or kind == "integer" and isinstance(value, bool)):
        return False
    if "oneOf" in schema and sum(check(value, s) for s in schema["oneOf"]) != 1:
        return False
    if not all(check(value, s) for s in schema.get("allOf", [])):
        return False
    if "if" in schema and check(value, schema["if"]) and not check(value, schema.get("then", {})):
        return False
    if isinstance(value, dict):
        if any(k not in value for k in schema.get("required", [])):
            return False
        props = schema.get("properties", {})
        if schema.get("additionalProperties") is False and set(value) - set(props):
            return False
        if any(not check(value[k], s) for k, s in props.items() if k in value):
            return False
    if isinstance(value, list):
        if not schema.get("minItems", 0) <= len(value) <= schema.get("maxItems", float("inf")):
            return False
        if "items" in schema and any(not check(v, schema["items"]) for v in value):
            return False
    if isinstance(value, str):
        if not schema.get("minLength", 0) <= len(value) <= schema.get("maxLength", float("inf")):
            return False
        if "pattern" in schema and not re.search(schema["pattern"], value):
            return False
    if isinstance(value, int) and not isinstance(value, bool):
        if not schema.get("minimum", -float("inf")) <= value <= schema.get("maximum", float("inf")):
            return False
    return True


def check_refs(node, source):
    """递归验证本地schema引用，拒绝越出包目录或使用网络引用。"""
    if isinstance(node, dict):
        if "$ref" in node:
            ref = node["$ref"]
            assert "://" not in ref, f"Unexpected network ref: {ref}"
            filename, _, fragment = ref.partition("#")
            target = (source.parent / filename).resolve() if filename else source
            assert target.is_relative_to(ROOT), ref
            document = read(target)
            for component in fragment.strip("/").split("/") if fragment else []:
                document = document[component.replace("~1", "/").replace("~0", "~")]
        for value in node.values():
            check_refs(value, source)
    elif isinstance(node, list):
        for value in node:
            check_refs(value, source)


def main():
    """执行本脚本的检查流程，结果仅适用于文档包而非产品验收。"""
    documents = list(ROOT.rglob("*.md"))
    assert len(documents) >= 10, "Required design documents missing"
    links = 0
    for path in documents:
        content = path.read_text(encoding="utf-8")
        assert "\ufffd" not in content, path
        assert content.count("```") % 2 == 0, f"Unclosed fence: {path}"
        assert "不做什么" in content, f"Missing boundary: {path}"
        for link in re.findall(r"\[[^\]]+\]\(([^)]+)\)", content):
            if "://" in link or link.startswith("#"):
                continue
            assert (path.parent / link.split("#")[0]).is_file(), (path, link)
            links += 1
    bundle = read(ROOT / "tasks/tasks.json")
    tasks = {task["id"]: task for task in bundle["tasks"]}
    assert len(tasks) == len(bundle["tasks"]) == 56
    assert sum(k.startswith("V") for k in tasks) == 48
    done, visiting = set(), set()

    def visit(key):
        """深度优先验证依赖存在且没有环。"""
        assert key in tasks, f"Unknown task: {key}"
        assert key not in visiting, f"Dependency cycle: {key}"
        if key in done:
            return
        visiting.add(key)
        task = tasks[key]
        assert task["status"] in {"PLANNED", "IN_PROGRESS", "READY_FOR_INTEGRATION", "VERIFYING", "DONE", "BLOCKED"}
        assert all(task.get(k) for k in ["goal", "deliverables", "acceptance", "evidencePath"])
        for dep in task["dependsOn"]:
            visit(dep)
        visiting.remove(key)
        done.add(key)

    for key in tasks:
        visit(key)
    for path in (ROOT / "contracts").glob("*.json"):
        check_refs(read(path), path)
    api = read(ROOT / "contracts/runner.openapi.json")
    ids = []
    for route, methods in api["paths"].items():
        for operation in methods.values():
            ids.append(operation["operationId"])
            needed = set(re.findall(r"{([^}]+)}", route))
            provided = {p["name"] for p in operation["parameters"] if p["in"] == "path"}
            assert needed == provided
            assert operation["responses"]
    assert len(ids) == len(set(ids)) == 8
    cases = read(ROOT / "contracts/examples/cases.json")
    for case in cases:
        value = read(ROOT / "contracts/examples" / case["file"])
        schema = read(ROOT / "contracts" / case["schema"])
        assert check(value, schema) == case["valid"], case["file"]
    record = read(ROOT / "contracts/examples/journal-golden.json")
    expected = record.pop("recordHash")
    raw = json.dumps(record, sort_keys=True, separators=(",", ":"),
                     ensure_ascii=False, allow_nan=False).encode("utf-8")
    assert hashlib.sha256(raw).hexdigest() == expected
    print(json.dumps({"status": "PASS", "scope": "design-package-only",
                      "markdown_files": len(documents), "local_links": links,
                      "core_tasks": 48, "lab_tasks": 8, "runner_operations": len(ids),
                      "fixture_cases": len(cases), "journal_golden_hash": "PASS",
                      "application_tests_run": False,
                      "limitations": "Schema subset fixture checks; full OpenAPI/JSON Schema tooling is a V01 task."},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
