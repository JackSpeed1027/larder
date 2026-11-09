"""事件日志与 SSE：序号单调、snapshot 兜底、增量补齐、帧格式。"""

from __future__ import annotations

import json

from fastapi import FastAPI
from fastapi.testclient import TestClient

from larder_runner.api.events import RunEventLog, build_events_router


def snapshot() -> dict:
    return {"jobId": "job_1", "state": "RUNNING", "attempt": 1}


def test_append_is_monotonic() -> None:
    log = RunEventLog()
    first = log.append("state", {"state": "RUNNING"})
    second = log.append("state", {"state": "SUCCEEDED"})

    assert (first.seq, second.seq) == (1, 2)
    assert log.tail_seq == 2


def test_resume_without_last_id_sends_snapshot() -> None:
    log = RunEventLog()
    log.append("state", {"state": "RUNNING"})

    events = log.resume(None, snapshot)

    assert len(events) == 1
    assert events[0].event == "snapshot"
    assert events[0].seq == log.tail_seq
    assert events[0].data["jobId"] == "job_1"


def test_resume_with_old_id_falls_back_to_snapshot() -> None:
    log = RunEventLog(max_events=2)
    for _ in range(5):
        log.append("state", {"state": "RUNNING"})

    events = log.resume(1, snapshot)

    assert len(events) == 1
    assert events[0].event == "snapshot"


def test_resume_returns_only_newer_events() -> None:
    log = RunEventLog()
    log.append("a", {})
    log.append("b", {})
    log.append("c", {})

    events = log.resume(1, snapshot)

    assert [item.event for item in events] == ["b", "c"]


def test_encode_has_id_event_and_json_data() -> None:
    log = RunEventLog()
    item = log.append("state", {"state": "RUNNING", "note": "中文"})

    frame = RunEventLog.encode(item)

    assert frame.startswith("id: 1\nevent: state\ndata: ")
    assert frame.endswith("\n\n")
    payload = json.loads(frame.split("data: ", 1)[1].strip())
    assert payload["note"] == "中文"


def test_router_streams_snapshot_and_rejects_unknown_run() -> None:
    log = RunEventLog()
    log.append("state", {"state": "RUNNING"})
    app = FastAPI()
    app.include_router(build_events_router({"run_1": log}, lambda _: snapshot(),
                                           poll_interval=0.01, idle_timeout=0.05))
    client = TestClient(app)

    response = client.get("/v1/runs/run_1/events")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "event: snapshot" in response.text

    missing = client.get("/v1/runs/nope/events")
    assert missing.status_code == 404
