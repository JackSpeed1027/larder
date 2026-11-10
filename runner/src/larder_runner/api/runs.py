"""S02：Runner 运行入口（/v1/runs）。

边界：
- 只接受冻结的 protocolVersion=v1；
- runId 是幂等键：同 runId 同 payload 重放同一逻辑 run，不同 payload → 409 RUN_PAYLOAD_CONFLICT；
- 本模块只做“接纳 + 状态 + 心跳”，不执行抓取/模型；执行装配由 S02 后续 slice 接入 scheduler；
- 不信任任何客户端自报的 owner/预算执行结果；租约凭据只记录，校验在 Java 侧。
"""

from __future__ import annotations

import hashlib
import json
import threading
import time
from dataclasses import dataclass, field

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field

PROTOCOL_VERSION = "v1"


class RunLease(BaseModel):
    """Java 派发时携带的租约凭据（Runner 只记录，不自行判定归属）。"""

    model_config = ConfigDict(extra="forbid")

    owner: str = Field(min_length=1, max_length=128)
    token: int = Field(ge=0)
    until: str = Field(min_length=1, max_length=64)


class RunScope(BaseModel):
    """run 的作用域：只允许触及该 owner 的条目/版本。"""

    model_config = ConfigDict(extra="forbid")

    ownerId: str = Field(min_length=1, max_length=64)
    itemId: str | None = Field(default=None, max_length=64)
    revisionId: str | None = Field(default=None, max_length=64)


class RunStartRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    protocolVersion: str
    runId: str = Field(pattern=r"^[A-Za-z][A-Za-z0-9_-]{1,63}$")
    jobId: str = Field(pattern=r"^[A-Za-z][A-Za-z0-9_-]{1,63}$")
    mode: str = Field(default="INGEST", max_length=32)
    scope: RunScope
    budget: dict | None = None
    traceId: str | None = Field(default=None, max_length=128)
    lease: RunLease | None = None
    input: dict = Field(default_factory=dict)


class RunView(BaseModel):
    runId: str
    jobId: str
    state: str
    dedupe: str
    acceptedAt: float
    lastHeartbeatAt: float | None = None


@dataclass
class _Run:
    request: RunStartRequest
    payload_hash: str
    state: str = "QUEUED"
    accepted_at: float = field(default_factory=time.time)
    last_heartbeat_at: float | None = None


def payload_hash(request: RunStartRequest) -> str:
    canonical = json.dumps(request.model_dump(exclude_none=True), ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class RunRegistry:
    """进程内 run 登记；S02 后续 slice 会换成持久账本（当前不假装持久）。"""

    def __init__(self, max_concurrent: int = 2) -> None:
        self._lock = threading.Lock()
        self._runs: dict[str, _Run] = {}
        self._max = max(1, max_concurrent)

    def submit(self, request: RunStartRequest) -> tuple[RunView, bool]:
        digest = payload_hash(request)
        with self._lock:
            existing = self._runs.get(request.runId)
            if existing is not None:
                if existing.payload_hash != digest:
                    raise HTTPException(status_code=409, detail="RUN_PAYLOAD_CONFLICT")
                return self._view(existing, "REPLAYED"), False
            if sum(1 for run in self._runs.values() if run.state == "RUNNING") >= self._max:
                raise HTTPException(status_code=429, detail="RUN_CAPACITY_EXCEEDED")
            self._runs[request.runId] = _Run(request=request, payload_hash=digest, state="RUNNING")
            return self._view(self._runs[request.runId], "CREATED"), True

    def get(self, run_id: str) -> _Run | None:
        with self._lock:
            return self._runs.get(run_id)

    def heartbeat(self, run_id: str) -> RunView:
        with self._lock:
            run = self._runs.get(run_id)
            if run is None:
                raise HTTPException(status_code=404, detail="RUN_NOT_FOUND")
            run.last_heartbeat_at = time.time()
            return self._view(run, "HEARTBEAT")

    def finish(self, run_id: str, state: str) -> RunView:
        with self._lock:
            run = self._runs.get(run_id)
            if run is None:
                raise HTTPException(status_code=404, detail="RUN_NOT_FOUND")
            run.state = state
            return self._view(run, "UPDATED")

    @staticmethod
    def _view(run: _Run, dedupe: str) -> RunView:
        return RunView(runId=run.request.runId, jobId=run.request.jobId, state=run.state,
                       dedupe=dedupe, acceptedAt=run.accepted_at, lastHeartbeatAt=run.last_heartbeat_at)


def build_runs_router(registry: RunRegistry) -> APIRouter:
    router = APIRouter()

    @router.post("/v1/runs", status_code=202)
    def start_run(request: RunStartRequest) -> RunView:
        if request.protocolVersion != PROTOCOL_VERSION:
            raise HTTPException(status_code=400, detail="PROTOCOL_VERSION_UNSUPPORTED")
        view, _ = registry.submit(request)
        return view

    @router.get("/v1/runs/{run_id}")
    def get_run(run_id: str) -> RunView:
        run = registry.get(run_id)
        if run is None:
            raise HTTPException(status_code=404, detail="RUN_NOT_FOUND")
        return RunRegistry._view(run, "READ")

    @router.post("/v1/runs/{run_id}/heartbeat")
    def heartbeat(run_id: str) -> RunView:
        return registry.heartbeat(run_id)

    return router
