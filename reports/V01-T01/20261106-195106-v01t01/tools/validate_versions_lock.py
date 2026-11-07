"""V01-T01 版本锁清单校验器。

用法（在项目根目录执行）：
    python reports/V01-T01/<runId>/tools/validate_versions_lock.py

校验目标（任何一条失败都说明锁清单不可信）：
1. JSON 可解析，schemaVersion 存在，组件 id 唯一；
2. 每个组件都有来源 URL、证据路径、许可标识和两级验证状态；
3. pin 里不允许出现 latest / 范围符；镜像必须带 64 位 sha256 digest；
4. 运行期为 BLOCKED 的组件必须同时出现在 blockedSummary，并写明原因；
5. pin/digest 必须能在本 run 落盘的注册源证据里找到（防止"写了不存在的版本"）。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ALLOWED_ARTIFACT = {"VERIFIED", "BLOCKED", "NOT_APPLICABLE"}
ALLOWED_RUNTIME = {"VERIFIED", "PARTIAL", "BLOCKED", "NOT_APPLICABLE"}
FORBIDDEN_IN_PIN = ("latest", "^", "~", ">", "<", "*", " ")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def build_evidence_index(evidence_root: Path) -> dict:
    """把本 run 落盘的注册源数据整理成 {kind: [文本片段]}，用于"版本真的存在"的检查。"""
    index: dict[str, list[str]] = {"maven": [], "docker": [], "npm": [], "pypi": []}

    for path in sorted((evidence_root / "raw" / "maven").glob("*.xml")) + sorted(
        (evidence_root / "raw" / "maven").glob("*.pom")
    ):
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for version in re.findall(r"<version>([^<]+)</version>", text):
            index["maven"].append(version)
        # BOM 用属性名管理版本（如 spring-framework.version），也要能查到
        for value in re.findall(r"<[A-Za-z0-9.\-]+\.version>([^<]+)</[A-Za-z0-9.\-]+\.version>", text):
            index["maven"].append(value)

    for path in sorted((evidence_root / "raw" / "docker").glob("*.tags.trimmed.json")):
        try:
            rows = load_json(path)
        except (OSError, json.JSONDecodeError):
            continue
        for row in rows:
            if not isinstance(row, dict):
                continue
            tag = str(row.get("tag", ""))
            digest = str(row.get("digest", ""))
            index["docker"].append(tag)
            if digest:
                index["docker"].append(digest)

    for path in sorted((evidence_root / "raw" / "npm").glob("*.latest.json")):
        try:
            data = load_json(path)
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(data, dict) and data.get("version"):
            index["npm"].append(str(data["version"]))

    for path in sorted((evidence_root / "raw" / "pypi").glob("*.trimmed.json")):
        try:
            data = load_json(path)
        except (OSError, json.JSONDecodeError):
            continue
        if isinstance(data, dict) and data.get("version"):
            index["pypi"].append(str(data["version"]))

    return index


def evidence_kind(url: str) -> str | None:
    if "repo1.maven.org" in url:
        return "maven"
    if "hub.docker.com" in url:
        return "docker"
    if "registry.npmjs.org" in url:
        return "npm"
    if "pypi.org" in url:
        return "pypi"
    return None


def main() -> int:
    root = Path(__file__).resolve().parents[4]
    if len(sys.argv) > 1:
        root = Path(sys.argv[1]).resolve()
    lock_path = root / "docs" / "versions.lock.json"

    failures: list[str] = []
    checks: list[str] = []

    if not lock_path.exists():
        print(f"FAIL lock file missing: {lock_path}")
        return 1
    try:
        lock = load_json(lock_path)
    except json.JSONDecodeError as exc:
        print(f"FAIL lock file is not valid JSON: {exc}")
        return 1

    checks.append(f"lock parsed: {lock_path}")

    if not lock.get("schemaVersion"):
        failures.append("schemaVersion missing")

    evidence_root = root / lock.get("artifacts", {}).get("evidenceRoot", "")
    index = build_evidence_index(evidence_root) if evidence_root.exists() else {
        "maven": [], "docker": [], "npm": [], "pypi": []
    }
    checks.append(f"evidence root: {evidence_root} (exists={evidence_root.exists()})")

    components = lock.get("components", [])
    if not components:
        failures.append("no components")

    seen_ids: set[str] = set()
    blocked_ids: set[str] = set()

    for item in components:
        cid = item.get("id", "<no-id>")
        if cid in seen_ids:
            failures.append(f"{cid}: duplicate id")
        seen_ids.add(cid)

        source = item.get("source") or {}
        if not source.get("url"):
            failures.append(f"{cid}: source.url missing")
        if not source.get("evidence"):
            failures.append(f"{cid}: source.evidence missing")
        elif not (root / source["evidence"]).exists():
            failures.append(f"{cid}: evidence file not found -> {source['evidence']}")

        license_info = item.get("license") or {}
        if not license_info.get("spdx"):
            failures.append(f"{cid}: license.spdx missing")

        verification = item.get("verification") or {}
        artifact = (verification.get("artifact") or {}).get("status")
        runtime = (verification.get("runtime") or {}).get("status")
        if artifact not in ALLOWED_ARTIFACT:
            failures.append(f"{cid}: bad artifact status {artifact!r}")
        if runtime not in ALLOWED_RUNTIME:
            failures.append(f"{cid}: bad runtime status {runtime!r}")
        if artifact == "BLOCKED" and not (verification.get("artifact") or {}).get("method"):
            failures.append(f"{cid}: blocked artifact without method")
        if runtime == "BLOCKED":
            blocked_ids.add(cid)
            if not (verification.get("runtime") or {}).get("reason"):
                failures.append(f"{cid}: blocked runtime without reason")

        pin = item.get("pin")
        if pin is not None:
            if not isinstance(pin, str):
                failures.append(f"{cid}: pin must be string or null")
            else:
                lowered = pin.lower()
                for token in FORBIDDEN_IN_PIN:
                    if token in lowered:
                        failures.append(f"{cid}: pin contains forbidden token {token!r} -> {pin}")

        if item.get("kind") == "image":
            digest = item.get("digest")
            if digest is not None and not DIGEST_RE.match(digest):
                failures.append(f"{cid}: digest is not sha256:<64 hex> -> {digest}")
            if digest is None and artifact != "BLOCKED":
                failures.append(f"{cid}: image without digest but artifact not BLOCKED")

        # 版本必须在注册源证据里出现过，否则视为虚构
        kind = evidence_kind(source.get("url", ""))
        if kind and pin:
            haystack = index.get(kind, [])
            if haystack and pin not in haystack:
                failures.append(f"{cid}: pin {pin!r} not found in {kind} evidence")
        if kind == "docker" and item.get("digest"):
            if index.get("docker") and item["digest"] not in index["docker"]:
                failures.append(f"{cid}: digest not found in docker evidence")

    for entry in lock.get("blockedSummary", []):
        bid = entry.get("id")
        if bid not in seen_ids:
            failures.append(f"blockedSummary references unknown component {bid!r}")
        if not entry.get("reason"):
            failures.append(f"blockedSummary {bid!r} without reason")

    for cid in blocked_ids:
        if cid not in {e.get("id") for e in lock.get("blockedSummary", [])}:
            failures.append(f"{cid}: runtime BLOCKED but missing from blockedSummary")

    adr_paths = [d.get("path") for d in lock.get("decisions", [])]
    for path in adr_paths:
        if path and not (root / path).exists():
            failures.append(f"ADR file missing: {path}")

    print(f"components={len(components)} blocked={len(blocked_ids)} adr={len(adr_paths)}")
    for line in checks:
        print(f"INFO {line}")
    if failures:
        print(f"RESULT FAIL count={len(failures)}")
        for line in failures:
            print(f"FAIL {line}")
        return 1
    print("RESULT PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
