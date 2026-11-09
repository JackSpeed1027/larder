"""Run 事件日志与 SSE 编码：snapshot 为准，事件按序号增量补齐。

为什么需要 snapshot：SSE 可能重复、丢失、乱序；客户端重连时最可靠的做法是
先收一份权威状态（snapshot），再按 Last-Event-ID 增量补齐，而不是假设自己记得旧状态。
"""

from __future__ import annotations

import json
import time
from collections import deque
from collections.abc import Callable, Iterator, Mapping
from dataclasses import dataclass

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse

SNAPSHOT_EVENT = "snapshot"
TERMINAL_EVENTS = frozenset({"succeeded", "failed", "cancelled", "superseded"})


@dataclass(frozen=True)
class RunEvent:
    """一条 run 事件：序号单调递增，event 名稳定，data 是 JSON 对象。"""

    seq: int
    event: str
    data: dict


class RunEventLog:
    """有界事件缓冲：内存只保留最近 N 条，更早的请求一律用 snapshot 兜底。"""

    def __init__(self, max_events: int = 200) -> None:
        if max_events < 1:
            raise ValueError("max_events 必须 >= 1")
        self._max = max_events
        self._events: deque[RunEvent] = deque()
        self._seq = 0

    @property
    def tail_seq(self) -> int:
        return self._seq

    def append(self, event: str, data: dict) -> RunEvent:
        self._seq += 1
        item = RunEvent(seq=self._seq, event=event, data=data)
        self._events.append(item)
        while len(self._events) > self._max:
            self._events.popleft()
        return item

    def resume(self, last_event_id: int | None, snapshot: Callable[[], dict]) -> list[RunEvent]:
        """按 Last-Event-ID 计算要补发的事件。

        - 没有 last id：只发 snapshot（客户端刚连上）；
        - last id 早于缓冲区下界：历史已丢，发 snapshot 覆盖；
        - 否则：发 seq > last id 的事件（可能为空）。
        """
        oldest = self._events[0].seq if self._events else None
        if last_event_id is None or oldest is None or last_event_id < oldest - 1:
            return [RunEvent(seq=self._seq, event=SNAPSHOT_EVENT, data=snapshot())]
        return [item for item in self._events if item.seq > last_event_id]

    @staticmethod
    def encode(item: RunEvent) -> str:
        """编码为 SSE 帧；data 用紧凑 JSON，保证中文可读且不转义。"""
        payload = json.dumps(item.data, ensure_ascii=False, separators=(",", ":"))
        return f"id: {item.seq}\nevent: {item.event}\ndata: {payload}\n\n"


def build_events_router(
    logs: Mapping[str, RunEventLog],
    snapshot_factory: Callable[[str], dict],
    *,
    poll_interval: float = 0.5,
    idle_timeout: float = 30.0,
) -> APIRouter:
    """构造 /v1/runs/{run_id}/events 的 SSE 路由（V05 装配到 app）。"""
    router = APIRouter()

    @router.get("/v1/runs/{run_id}/events")
    def run_events(run_id: str, request: Request) -> StreamingResponse:
        log = logs.get(run_id)
        if log is None:
            raise HTTPException(status_code=404, detail="run not found")
        last_id = _parse_last_event_id(request.headers.get("last-event-id"))
        return StreamingResponse(
            _stream(log, snapshot_factory(run_id), last_id, poll_interval, idle_timeout),
            media_type="text/event-stream",
            headers={"cache-control": "no-cache", "x-accel-buffering": "no"},
        )

    return router


def _parse_last_event_id(raw: str | None) -> int | None:
    if raw is None or not raw.strip():
        return None
    try:
        return int(raw)
    except ValueError:
        return None


def _stream(
    log: RunEventLog,
    snapshot: dict,
    last_id: int | None,
    poll_interval: float,
    idle_timeout: float,
) -> Iterator[str]:
    """先补 snapshot/增量，再轮询新事件；遇到终态或空闲超时结束。"""
    sent = 0
    for item in log.resume(last_id, lambda: snapshot):
        sent = max(sent, item.seq)
        yield RunEventLog.encode(item)

    deadline = time.monotonic() + idle_timeout
    while time.monotonic() < deadline:
        time.sleep(poll_interval)
        pending = [item for item in log.resume(sent, lambda: snapshot) if item.seq > sent]
        for item in pending:
            sent = item.seq
            yield RunEventLog.encode(item)
            if item.event in TERMINAL_EVENTS:
                return
        if not pending:
            yield ": heartbeat\n\n"
