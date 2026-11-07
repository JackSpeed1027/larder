"""契约校验：样例双向校验、事件 payload、错误码注册表、三端字段一致性、Mock 同源、生成可重复。

用法：
    uv run --with jsonschema python scripts/contracts/check_contracts.py
    uv run --with jsonschema python scripts/contracts/check_contracts.py --check   # 仅核对生成产物 hash

退出码非 0 表示契约不成立；任何一项漂移都应让 CI 失败，而不是"看起来能跑"。
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

import jsonschema

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"
EXAMPLES = CONTRACTS / "examples"
GENERATED = ROOT / "scripts" / "contracts" / "generated.sha256"

failures: list[str] = []
checks = 0


def fail(msg: str) -> None:
    failures.append(msg)


def ok(msg: str) -> None:
    global checks
    checks += 1
    print(f"[PASS] {msg}")


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def schema_for(name: str):
    return load(CONTRACTS / name)


def validator_for(name: str, definition: str | None):
    schema = schema_for(name)
    if definition:
        schema = {**schema, **schema["$defs"][definition]}
        # 保留 $defs 以便内部 $ref 仍可解析
        schema["$defs"] = schema_for(name)["$defs"]
    jsonschema.Draft202012Validator.check_schema(schema)
    return jsonschema.Draft202012Validator(schema)


def check_cases() -> None:
    cases = load(EXAMPLES / "cases.json")
    event_schema = schema_for("run-event.schema.json")
    payload_map = event_schema.get("x-eventPayloadMap", {})
    for case in cases:
        data = load(EXAMPLES / case["file"])
        validator = validator_for(case["schema"], case.get("def"))
        errors = list(validator.iter_errors(data))

        # 事件 payload 有独立 $defs：base schema 允许任意对象，所以对 run-event 还要看 payload
        if not errors and case["schema"] == "run-event.schema.json":
            def_name = payload_map.get(data.get("type"))
            if def_name:
                payload_validator = jsonschema.Draft202012Validator(
                    {"$ref": f"#/$defs/{def_name}", "$defs": event_schema["$defs"]}
                )
                errors = list(payload_validator.iter_errors(data.get("payload", {})))

        if case["valid"] and errors:
            fail(f"{case['file']}: 期望合法但被拒绝：{errors[0].message}")
        elif not case["valid"] and not errors:
            fail(f"{case['file']}: 期望非法却被接受（schema {case['schema']}）")
        else:
            ok(f"case {case['file']} valid={case['valid']}")


def check_event_payloads() -> None:
    """对 run-event 里 map 到的事件类型，payload 必须通过对应 $defs 校验。"""
    schema = schema_for("run-event.schema.json")
    payload_map = schema.get("x-eventPayloadMap", {})
    for case in load(EXAMPLES / "cases.json"):
        if case["schema"] != "run-event.schema.json" or not case["valid"]:
            continue
        event = load(EXAMPLES / case["file"])
        def_name = payload_map.get(event.get("type"))
        if not def_name:
            continue
        payload_schema = {"$ref": f"#/$defs/{def_name}", "$defs": schema["$defs"]}
        errors = list(jsonschema.Draft202012Validator(payload_schema).iter_errors(event["payload"]))
        if errors:
            fail(f"{case['file']}: payload 不符合 {def_name}: {errors[0].message}")
        else:
            ok(f"payload {case['file']} -> {def_name}")


def check_error_registry() -> None:
    registry = load(CONTRACTS / "error-codes.json")
    codes = [c["code"] for c in registry["codes"]]
    if len(codes) != len(set(codes)):
        fail("error-codes.json 存在重复 code")
    for c in registry["codes"]:
        if not re.fullmatch(r"[A-Z][A-Z0-9_]{2,63}", c["code"]):
            fail(f"错误码格式不合法: {c['code']}")
        if not isinstance(c.get("httpStatus"), int) or not 400 <= c["httpStatus"] <= 599:
            fail(f"错误码 {c['code']} 的 httpStatus 不合法")
    ok(f"error registry: {len(codes)} codes")

    known = set(codes)
    valid_files = {c["file"] for c in load(EXAMPLES / "cases.json") if c["valid"]}
    for path in EXAMPLES.glob("*.json"):
        if path.name not in valid_files:
            continue
        data = load(path)
        if isinstance(data, dict) and isinstance(data.get("code"), str) and "retryable" in data:
            if data["code"] not in known:
                fail(f"{path.name}: 错误码 {data['code']} 未在 error-codes.json 注册")
    ok("所有合法 fixture 的错误码都在注册表中")


def _ts_block(text: str, type_name: str) -> str | None:
    """取出一个导出类型自身的花括号内容（支持 `interface X {}` 与 `type X = {} & {}`）。"""
    m = re.search(rf"export (?:interface|type) {type_name}\b", text)
    if not m:
        return None
    i = text.find("{", m.end())
    if i < 0:
        return None
    blocks: list[str] = []
    while i >= 0:
        depth = 0
        start = i + 1
        j = i
        while j < len(text):
            ch = text[j]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        blocks.append(text[start:j])
        rest = text[j + 1:]
        nxt = re.match(r"\s*&\s*\{", rest)
        if not nxt:
            break
        i = j + 1 + nxt.end() - 1
    return "\n".join(blocks)


def _ts_props(path: Path, type_name: str) -> set[str]:
    """取生成 TS 里目标类型自身的一级字段名。

    json-schema-to-typescript 对普通对象输出 `export interface X {}`，对 allOf 输出
    `export type X = {...} & {...}`；同一个文件里还可能并列其它 interface，
    所以必须先按花括号配对截出目标类型的块，不能简单按缩进扫全文件。
    """
    text = path.read_text(encoding="utf-8")
    block = _ts_block(text, type_name)
    if block is None:
        fail(f"TS 生成文件缺少类型 {type_name}: {path.name}")
        return set()
    keys = re.findall(r'^  ("?[A-Za-z_][A-Za-z0-9_]*"?)\??\s*:', block, re.M)
    return {k.strip('"') for k in keys}


def _py_props(path: Path, model: str) -> set[str]:
    text = path.read_text(encoding="utf-8")
    match = re.search(rf"class {model}\(BaseModel\):(.*?)(?:\nclass |\Z)", text, re.S)
    if not match:
        fail(f"Python 生成文件缺少模型 {model}: {path.name}")
        return set()
    return set(re.findall(r"^\s{4}(\w+)\s*:", match.group(1), re.M))


def _split_top_level(text: str) -> list[str]:
    """按逗号切分，但不切泛型 <> 与括号 () 内部的逗号（Map<String, Object> 这种）。"""
    parts, depth, current = [], 0, []
    for ch in text:
        if ch in "<(":
            depth += 1
        elif ch in ">)":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    if current:
        parts.append("".join(current))
    return parts


def _java_props(path: Path, record: str) -> set[str]:
    text = path.read_text(encoding="utf-8")
    match = re.search(rf"public record {record}(?:<[^>]*>)?\((.*?)\)\s*\{{", text, re.S)
    if not match:
        fail(f"Java 文件缺少 record {record}: {path.name}")
        return set()
    props = set()
    for part in _split_top_level(match.group(1)):
        part = part.strip()
        if not part:
            continue
        # 形如 "Map<String, Object> details"：类型可能带空格，字段名是最后一个 token
        props.add(part.split()[-1])
    return props


def check_parity() -> None:
    """三端生成的字段名必须与 schema 的 properties 一致（防"Mock 和真实字段不同"）。

    TS 的 allOf 会生成交叉类型，一级键仍可完整取出；多出来的键只允许是
    index signature 之类的生成器附带项，不允许出现业务字段差异。
    """
    pairs = [
        ("run-request.schema.json", "RunRequest", ROOT / "frontend/src/generated/run-request.ts", "RunRequest"),
        ("run-response.schema.json", "RunResponse", ROOT / "frontend/src/generated/run-response.ts", "RunResponse"),
        ("run-event.schema.json", "RunEvent", ROOT / "frontend/src/generated/run-event.ts", "RunEvent"),
    ]
    allowed_ts_extra = {"AdditionalProperties"}
    for schema_name, _, ts_path, ts_type in pairs:
        expected = set(schema_for(schema_name)["properties"])
        got = _ts_props(ts_path, ts_type)
        missing, extra = expected - got, got - expected - allowed_ts_extra
        if missing or extra:
            fail(f"TS {ts_type} 字段漂移: missing={sorted(missing)} extra={sorted(extra)}")
        else:
            ok(f"TS parity {ts_type} ({len(expected)} fields)")

    py_specs = [
        ("run-request.schema.json", "RunRequest", ROOT / "runner/src/larder_runner/api/models/run_request.py", "RunRequest"),
        ("run-response.schema.json", "RunResponse", ROOT / "runner/src/larder_runner/api/models/run_response.py", "RunResponse"),
        ("run-event.schema.json", "RunEvent", ROOT / "runner/src/larder_runner/api/models/run_event.py", "RunEvent"),
    ]
    for schema_name, _, py_path, model in py_specs:
        expected = set(schema_for(schema_name)["properties"])
        got = _py_props(py_path, model)
        if got != expected:
            fail(f"Python {model} 字段漂移: missing={sorted(expected - got)} extra={sorted(got - expected)}")
        else:
            ok(f"Python parity {model} ({len(expected)} fields)")

    defs = schema_for("public-api-common.schema.json")["$defs"]
    for record, def_name in (("ApiResponse", "ApiResponse"), ("ApiErrorResponse", "ApiErrorResponse")):
        expected = set(defs[def_name]["properties"])
        got = _java_props(ROOT / "backend/src/main/java/com/larder/common/contract" / f"{record}.java", record)
        if got != expected:
            fail(f"Java {record} 字段漂移: missing={sorted(expected - got)} extra={sorted(got - expected)}")
        else:
            ok(f"Java parity {record} ({len(expected)} fields)")


def check_mock_same_source() -> None:
    src = {p.name: p.read_bytes() for p in EXAMPLES.glob("*.json")}
    dst_dir = ROOT / "frontend/mocks/fixtures"
    missing = [n for n in src if not (dst_dir / n).exists()]
    differing = [n for n, b in src.items() if (dst_dir / n).exists() and (dst_dir / n).read_bytes() != b]
    if missing or differing:
        fail(f"Mock fixture 未同源: missing={missing} differing={differing}")
    else:
        ok(f"mock fixtures 同源 ({len(src)} files)")


def check_reproducible() -> None:
    if not GENERATED.exists():
        fail("缺少 scripts/contracts/generated.sha256，先运行 generate.ps1")
        return
    expected = {}
    for line in GENERATED.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        digest, rel = line.split("  ", 1)
        expected[rel] = digest
    actual = {rel: hashlib.sha256((ROOT / rel).read_bytes()).hexdigest() for rel in expected}
    drift = [rel for rel in expected if expected[rel] != actual[rel]]
    if drift:
        fail(f"生成产物与记录不一致（重跑 generate.ps1 并确认差异来源）: {drift}")
    else:
        ok(f"生成产物可重复 ({len(expected)} files)")


def check_fixtures_used_by_mock() -> None:
    """Mock server 必须只读 contracts/examples 里的 fixture，不允许内联第二份数据。"""
    text = (ROOT / "scripts/contracts/mock_runner.py").read_text(encoding="utf-8")
    inline = re.findall(r'file"\s*:\s*"([^"]+\.json)"', text)
    if inline:
        fail(f"mock_runner.py 内联了 fixture 数据：{inline}（应读 contracts/examples）")
    else:
        ok("mock server 不内联 fixture")


def main() -> int:
    only_check = "--check" in sys.argv
    check_cases()
    check_event_payloads()
    check_error_registry()
    check_parity()
    check_mock_same_source()
    check_fixtures_used_by_mock()
    if not only_check:
        check_reproducible()

    print(f"\nchecks={checks} failures={len(failures)}")
    for f in failures:
        print(f"[FAIL] {f}")
    print("RESULT " + ("PASS" if not failures else "FAIL"))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
