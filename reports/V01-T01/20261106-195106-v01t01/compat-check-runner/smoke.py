"""V01-T01 Python 端兼容性冒烟测试（验证脚手架，不是 Runner 工程）。

验证目标（失败即说明锁定组合不可用）：
1. Python 3.12 解释器能建虚拟环境并安装 lock 里的精确版本；
2. FastAPI 能起 ASGI 应用并返回 JSON；
3. Pydantic v2 能按契约习惯拒绝未知字段（docs/09：输入未知字段拒绝）。
"""
import json
import sys

import fastapi
import httpx
import pydantic
import uvicorn
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel, ConfigDict, ValidationError


class RunRequest(BaseModel):
    """最小请求模型：模拟 Runner 边界模型的未知字段策略。"""

    model_config = ConfigDict(extra="forbid", alias_generator=None)

    runId: str
    inputMode: str


def main() -> int:
    failures = []

    app = FastAPI()

    @app.get("/ping")
    def ping():
        return {"status": "ok", "protocolVersion": "v1"}

    client = TestClient(app)
    resp = client.get("/ping")
    if resp.status_code != 200 or resp.json().get("status") != "ok":
        failures.append(f"fastapi route failed: {resp.status_code} {resp.text}")
    else:
        print(f"[COMPAT] fastapi route ok: {resp.json()}")

    # 合法输入
    ok = RunRequest(runId="run_abc123", inputMode="url")
    print(f"[COMPAT] pydantic accepted: {ok.model_dump()}")

    # 非法输入：多一个未知字段必须被拒绝，而不是静默丢弃
    try:
        RunRequest(runId="run_abc123", inputMode="url", unexpected="x")
        failures.append("pydantic accepted unknown field (should fail)")
    except ValidationError:
        print("[COMPAT] pydantic rejected unknown field as expected")

    print("[COMPAT] python=" + sys.version.split()[0])
    print("[COMPAT] fastapi=" + fastapi.__version__)
    print("[COMPAT] pydantic=" + pydantic.VERSION)
    print("[COMPAT] uvicorn=" + uvicorn.__version__)
    print("[COMPAT] httpx=" + httpx.__version__)

    if failures:
        print(json.dumps({"result": "FAIL", "failures": failures}, ensure_ascii=False))
        return 1
    print(json.dumps({"result": "PASS"}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
