"""Larder 验收入口（V01-T04 起可用）。

用法：
    python scripts/accept.py --task V01-T04 --profile offline

设计原则（对应任务卡）：\n
- **零测试必须失败**：后端/runner 用例数为 0 直接 FAIL，不允许"没测试所以通过"；
- **缺环境必须失败**：命令不存在、依赖没装、Docker 不可用都是 FAIL，不降级成 skip；
- **证据落盘**：每条命令的原始输出写进 reports/<task>/<runId>/logs/，summary.json 记录退出码与用例数。

当前 profile 只实现 offline（离线，不调用收费模型）。live 明确报未实现，避免假装跑过。
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IS_WINDOWS = platform.system() == "Windows"


@dataclass
class Result:
    """一条命令的结果；用例数为 None 表示不是测试类检查。"""

    name: str
    command: str
    exit_code: int
    log: str
    passed: int | None = None
    failed: int | None = None
    skipped: int | None = None
    note: str = ""
    failure: str | None = None

    @property
    def ok(self) -> bool:
        return self.failure is None


@dataclass
class Report:
    task: str
    profile: str
    run_id: str
    results: list[Result] = field(default_factory=list)

    @property
    def failures(self) -> list[str]:
        return [f"{r.name}: {r.failure}" for r in self.results if r.failure]

    @property
    def result(self) -> str:
        return "PASS" if not self.failures else "FAIL"


def run_command(name: str, cmd: list[str], log_path: Path, timeout: int = 1800) -> tuple[int, str]:
    """执行命令并落日志；命令不存在返回 127 而不是抛栈。"""
    log_path.parent.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.setdefault("PYTHONUTF8", "1")
    env.setdefault("PYTHONIOENCODING", "utf-8")
    header = f"$ {' '.join(cmd)}\n(cwd={ROOT})\n\n"
    try:
        proc = subprocess.run(
            cmd,
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            env=env,
        )
        output = (proc.stdout or "") + (proc.stderr or "")
        code = proc.returncode
    except FileNotFoundError:
        output = f"COMMAND NOT FOUND: {cmd[0]}\n"
        code = 127
    except subprocess.TimeoutExpired as exc:
        output = f"TIMEOUT after {timeout}s\n{exc.stdout or ''}\n{exc.stderr or ''}"
        code = 124
    log_path.write_text(header + output, encoding="utf-8")
    return code, output


def parse_maven_tests(output: str) -> tuple[int, int, int, int]:
    # Surefire 会为每个测试类打印一行，再打印一行汇总；求和会重复计数，
    # 取最后一行（汇总）才是真实用例数。
    matches = re.findall(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)", output)
    if not matches:
        return 0, 0, 0, 0
    total, failures, errors, skipped = (int(x) for x in matches[-1])
    return total, failures, errors, skipped


def parse_pytest(output: str) -> tuple[int, int, int]:
    passed = int(m.group(1)) if (m := re.search(r"(\d+) passed", output)) else 0
    failed = int(m.group(1)) if (m := re.search(r"(\d+) failed", output)) else 0
    skipped = int(m.group(1)) if (m := re.search(r"(\d+) skipped", output)) else 0
    return passed, failed, skipped


def check_secrets(logs: Path) -> Result:
    """扫描被 git 跟踪的文件：真实 .env、私钥、硬编码密码/Token 都算失败。"""
    name = "secrets"
    log = logs / f"{name}.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    code, out = run_command(name, ["git", "ls-files"], log, timeout=120)
    if code != 0:
        return Result(name, "git ls-files", code, str(log), failure="无法列出 Git 跟踪文件（仓库/tools 缺失）")

    files = [line.strip() for line in out.splitlines() if line.strip()]
    findings: list[str] = []
    private_key = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
    cloud_key = re.compile(r"\bAKIA[0-9A-Z]{16}\b")
    gh_token = re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b")
    hardcoded = re.compile(r"(?i)\b(password|passwd|secret|api[_-]?key|token)\b\s*[:=]\s*['\"]([^'\"]{6,})['\"]")

    for rel in files:
        if rel in {".env", ".env.production"}:
            findings.append(f"{rel}: 真实环境文件被跟踪")
            continue
        # reports/ 是外部快照（含供应商文档里的示例 key），docs/ 是规格；两者都不是运行配置
        if rel.startswith(("reports/", "docs/")):
            continue
        path = ROOT / rel
        if not path.is_file() or path.stat().st_size > 2_000_000:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if private_key.search(text):
            findings.append(f"{rel}: 含私钥")
        if cloud_key.search(text):
            findings.append(f"{rel}: 含云访问密钥")
        if gh_token.search(text):
            findings.append(f"{rel}: 含 GitHub token")
        for m in hardcoded.finditer(text):
            value = m.group(2)
            if value.startswith("change-me") or value.startswith("$") or value in {"correct-horse"}:
                continue
            findings.append(f"{rel}: 疑似硬编码 {m.group(1)}")
            break

    with log.open("a", encoding="utf-8") as handle:
        handle.write("\n".join(findings) if findings else "\nno findings\n")
    if findings:
        return Result(name, "git ls-files + secret scan", 1, str(log), failure="; ".join(findings[:5]))
    return Result(name, "git ls-files + secret scan", 0, str(log), note=f"扫描 {len(files)} 个跟踪文件")


def check_backend(logs: Path) -> Result:
    name = "backend_tests"
    log = logs / f"{name}.log"
    wrapper = ROOT / "backend" / ("mvnw.cmd" if IS_WINDOWS else "mvnw")
    if not wrapper.exists():
        return Result(name, "backend/mvnw verify", 127, str(log), failure="找不到 Maven wrapper")
    cmd = [str(wrapper), "-B", "-ntp", "-f", "backend/pom.xml", "verify", "-DfailIfNoTests=true"]
    code, out = run_command(name, cmd, log)
    total, failures, errors, skipped = parse_maven_tests(out)
    result = Result(name, " ".join(cmd), code, str(log), total, failures + errors, skipped)
    verdict = judge_backend(code, total, failures + errors, skipped)
    if verdict:
        result.failure = f"{verdict}（看 {log.name}）"
    return result


def check_runner(logs: Path) -> Result:
    name = "runner_tests"
    log = logs / f"{name}.log"
    if shutil.which("uv") is None:
        return Result(name, "uv run pytest", 127, str(log), failure="uv 未安装（环境缺失，不跳过）")
    # 必须在 runner 目录内执行：测试 import app.main，从仓库根跑会 ModuleNotFoundError
    # 不再叠加 -q：pyproject 的 addopts 已有 -q，再加一个会变成 -qq 并吞掉 "N passed" 汇总行
    cmd = ["uv", "run", "--directory", "runner", "pytest", "tests", "-m", "not live"]
    code, out = run_command(name, cmd, log)
    passed, failed, skipped = parse_pytest(out)
    result = Result(name, " ".join(cmd), code, str(log), passed, failed, skipped)
    verdict = judge_runner(code, passed, failed, skipped)
    if verdict:
        result.failure = f"{verdict}（看 {log.name}）"
    return result


def check_frontend(logs: Path) -> Result:
    name = "frontend_build"
    log = logs / f"{name}.log"
    if find_pnpm() is None:
        return Result(name, "pnpm build", 127, str(log), failure="pnpm 未安装（环境缺失，不跳过）")
    if not (ROOT / "frontend" / "node_modules").exists():
        return Result(name, "pnpm build", 127, str(log),
                      failure="frontend/node_modules 不存在，先 pnpm install（缺环境必须失败）")
    cmd = pnpm_command("--dir", "frontend", "build")
    code, out = run_command(name, cmd, log)
    result = Result(name, " ".join(cmd), code, str(log))
    if code != 0:
        result.failure = f"exit={code}（看 {log.name}）"
    return result


def check_contracts(logs: Path) -> Result:
    name = "contracts"
    log = logs / f"{name}.log"
    has_jsonschema = subprocess.run(
        [sys.executable, "-c", "import jsonschema"], capture_output=True
    ).returncode == 0
    if has_jsonschema:
        cmd = [sys.executable, "scripts/contracts/check_contracts.py"]
    elif shutil.which("uv"):
        cmd = ["uv", "run", "--with", "jsonschema", "python", "scripts/contracts/check_contracts.py"]
    else:
        return Result(name, "check_contracts", 127, str(log), failure="缺 jsonschema 且无 uv（环境缺失）")
    code, out = run_command(name, cmd, log)
    result = Result(name, " ".join(cmd), code, str(log))
    if code != 0:
        result.failure = f"exit={code}（看 {log.name}）"
    elif "RESULT PASS" not in out:
        result.failure = "输出中没有 RESULT PASS"
    return result


def check_compose(logs: Path) -> Result:
    name = "compose_config"
    log = logs / f"{name}.log"
    if shutil.which("docker") is None:
        return Result(name, "docker compose config", 127, str(log), failure="docker 未安装（环境缺失，不跳过）")
    cmd = ["docker", "compose", "-f", "compose.yaml", "config", "--quiet"]
    code, out = run_command(name, cmd, log, timeout=300)
    result = Result(name, " ".join(cmd), code, str(log))
    if code != 0:
        result.failure = f"exit={code}（看 {log.name}）"
    return result


def find_pnpm() -> str | None:
    """定位 pnpm：Windows 上是 pnpm.cmd/pnpm.ps1，Python 的 which("pnpm") 找不到。"""
    for candidate in ("pnpm", "pnpm.cmd", "pnpm.exe"):
        found = shutil.which(candidate)
        if found:
            return found
    return None


def pnpm_command(*args: str) -> list[str]:
    """构造可被 subprocess 直接执行的 pnpm 命令。"""
    exe = find_pnpm() or "pnpm"
    if IS_WINDOWS and exe.lower().endswith(".ps1"):
        # .ps1 不能直接 CreateProcess，用 cmd 调 pnpm.cmd 更稳
        return ["cmd", "/c", "pnpm", *args]
    return [exe, *args]


def judge_backend(exit_code: int, total: int, failures: int, skipped: int) -> str | None:
    """后端判定：零测试/失败/错误都必须返回失败原因。"""
    if exit_code != 0:
        return f"exit={exit_code}"
    if total == 0:
        return "零测试：Maven 没有执行任何用例（-DfailIfNoTests=true 应失败）"
    if failures:
        return f"失败/错误 {failures}"
    return None


def judge_runner(exit_code: int, passed: int, failed: int, skipped: int) -> str | None:
    """runner 判定：零测试/失败都必须失败；skip 单独记录但不掩盖零测试。"""
    if exit_code != 0:
        return f"exit={exit_code}"
    if passed == 0 and failed == 0:
        return "零测试：pytest 没有收集到用例"
    if failed:
        return f"失败 {failed}"
    return None


def self_check() -> int:
    """证明"零测试/失败必须失败"不是写在文档里的口号，而是可执行断言。"""
    cases = [
        ("backend 零测试", judge_backend(0, 0, 0, 0), True),
        ("backend 失败", judge_backend(0, 3, 1, 0), True),
        ("backend 正常", judge_backend(0, 3, 0, 0), False),
        ("runner 零测试", judge_runner(0, 0, 0, 0), True),
        ("runner 失败", judge_runner(0, 2, 1, 0), True),
        ("runner 正常", judge_runner(0, 2, 0, 0), False),
        ("runner 退出码非0", judge_runner(1, 2, 0, 0), True),
    ]
    failed = 0
    for name, verdict, should_fail in cases:
        ok = (verdict is not None) == should_fail
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: verdict={verdict!r}")
        failed += 0 if ok else 1
    print(f"\nself-check failures={failed}")
    return 0 if failed == 0 else 1


def check_content_e2e(logs: Path) -> Result:
    """内容收录端到端：真实 MySQL 容器 + 真实 HTTP，验证幂等/去重/并发/迁移可重复。"""
    name = "content_e2e"
    log = logs / f"{name}.log"
    script = "scripts/dev/verify-content.ps1"
    if not (ROOT / script).exists():
        return Result(name, "verify-content", 127, str(log), failure="脚本不存在")
    if IS_WINDOWS:
        cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script]
    else:
        cmd = ["pwsh", "-NoProfile", "-File", script]
    code, out = run_command(name, cmd, log, timeout=1800)
    result = Result(name, " ".join(cmd), code, str(log))
    if code != 0:
        result.failure = f"exit={code}（看 {log.name}）"
    elif "ALL CONTENT CHECKS PASSED" not in out:
        result.failure = "输出中没有 ALL CONTENT CHECKS PASSED"
    return result


CHECKS = {
    "secrets": check_secrets,
    "backend_tests": check_backend,
    "runner_tests": check_runner,
    "frontend_build": check_frontend,
    "contracts": check_contracts,
    "compose_config": check_compose,
    "content_e2e": check_content_e2e,
}

TASKS = {
    "V01-T04": ["secrets", "backend_tests", "runner_tests", "frontend_build", "contracts", "compose_config"],
    "V02-T01": ["secrets", "backend_tests", "contracts", "compose_config", "content_e2e"],
    "V01-T03": ["secrets", "contracts", "backend_tests"],
    "V01-T02": ["secrets", "backend_tests", "runner_tests", "frontend_build", "compose_config"],
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Larder 验收入口")
    parser.add_argument("--task", default=None)
    parser.add_argument("--profile", default="offline", choices=["offline", "live"])
    parser.add_argument("--run-id", default=None)
    parser.add_argument("--self-check", action="store_true", help="只验证零测试/失败判定逻辑")
    args = parser.parse_args()

    if args.self_check:
        return self_check()
    if not args.task:
        parser.error("--task 是必填项（--self-check 除外）")

    if args.profile == "live":
        print("BLOCKED: live profile 尚未实现（需要真实模型供应商与预算配置）；不得假装跑过。")
        return 2
    if args.task not in TASKS:
        print(f"BLOCKED: 未登记的任务 {args.task}；先在本脚本 TASKS 中声明它需要的检查。")
        return 2

    run_id = args.run_id or datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + args.task.lower()
    report_dir = ROOT / "reports" / args.task / run_id
    logs = report_dir / "logs"
    logs.mkdir(parents=True, exist_ok=True)

    report = Report(task=args.task, profile=args.profile, run_id=run_id)
    for name in TASKS[args.task]:
        print(f"== {name} ==")
        result = CHECKS[name](logs)
        report.results.append(result)
        if result.ok:
            print(f"   PASS exit={result.exit_code}")
        else:
            print(f"   FAIL {result.failure}")
            try:
                tail = Path(result.log).read_text(encoding="utf-8").splitlines()[-12:]
                for line in tail:
                    print(f"     | {line}")
            except OSError:
                pass

    summary = {
        "task": report.task,
        "profile": report.profile,
        "runId": report.run_id,
        "result": report.result,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "environment": {
            "os": platform.platform(),
            "python": platform.python_version(),
            "node": shutil.which("node"),
            "docker": shutil.which("docker"),
            "uv": shutil.which("uv"),
        },
        "checks": [
            {
                "name": r.name,
                "command": r.command,
                "exitCode": r.exit_code,
                "passed": r.passed,
                "failed": r.failed,
                "skipped": r.skipped,
                "log": str(Path(r.log).relative_to(ROOT)).replace("\\", "/"),
                "note": r.note,
                "failure": r.failure,
            }
            for r in report.results
        ],
        "failures": report.failures,
    }
    (report_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nRESULT {report.result}  ({run_id})")
    if report.failures:
        for failure in report.failures:
            print(f"  - {failure}")
    return 0 if report.result == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
