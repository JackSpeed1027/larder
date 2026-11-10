"""S02 最小 slice：Runner 运行入口的协议、幂等与心跳。

每个用例用独立 registry（容量 100），避免互相污染导致的 429。
"""

from __future__ import annotations

import time

from fastapi import FastAPI
from fastapi.testclient import TestClient

from larder_runner.api.runs import RunRegistry, build_runs_router


def new_client(limit: int = 100) -> TestClient:
    app = FastAPI()
    app.include_router(build_runs_router(RunRegistry(limit)))
    return TestClient(app)


def request_body(run_id: str, item_id: str = "itm_abc123def456") -> dict:
    return {
        "protocolVersion": "v1",
        "runId": run_id,
        "jobId": "job_S02",
        "mode": "INGEST",
        "scope": {"ownerId": "own_1", "itemId": item_id, "revisionId": None},
        "traceId": "trace-" + run_id,
        "input": {"url": "https://example.com/s02"},
    }


def test_start_run_accepts_and_returns_running() -> None:
    client = new_client()
    run_id = "run_ok_" + str(int(time.time() * 1000))
    response = client.post("/v1/runs", json=request_body(run_id))
    assert response.status_code == 202
    body = response.json()
    assert body["runId"] == run_id
    assert body["state"] == "RUNNING"
    assert body["dedupe"] == "CREATED"


def test_same_run_same_payload_replays() -> None:
    client = new_client()
    run_id = "run_replay_" + str(int(time.time() * 1000))
    first = client.post("/v1/runs", json=request_body(run_id))
    second = client.post("/v1/runs", json=request_body(run_id))
    assert first.status_code == 202 and second.status_code == 202
    assert second.json()["dedupe"] == "REPLAYED"
    assert first.json()["acceptedAt"] == second.json()["acceptedAt"]


def test_same_run_different_payload_conflicts() -> None:
    client = new_client()
    run_id = "run_conflict_" + str(int(time.time() * 1000))
    assert client.post("/v1/runs", json=request_body(run_id)).status_code == 202
    response = client.post("/v1/runs", json=request_body(run_id, item_id="itm_other"))
    assert response.status_code == 409
    assert response.json()["detail"] == "RUN_PAYLOAD_CONFLICT"


def test_unsupported_protocol_version_is_rejected() -> None:
    client = new_client()
    body = request_body("run_badproto_" + str(int(time.time() * 1000)))
    body["protocolVersion"] = "v2"
    assert client.post("/v1/runs", json=body).status_code == 400


def test_unknown_run_and_heartbeat() -> None:
    client = new_client()
    assert client.get("/v1/runs/run_missing").status_code == 404
    run_id = "run_hb_" + str(int(time.time() * 1000))
    assert client.post("/v1/runs", json=request_body(run_id)).status_code == 202
    beat = client.post(f"/v1/runs/{run_id}/heartbeat")
    assert beat.status_code == 200
    assert beat.json()["lastHeartbeatAt"] is not None


def test_capacity_limit_returns_429() -> None:
    client = new_client(limit=1)
    assert client.post("/v1/runs", json=request_body("run_cap_1")).status_code == 202
    second = client.post("/v1/runs", json=request_body("run_cap_2"))
    assert second.status_code == 429
    assert second.json()["detail"] == "RUN_CAPACITY_EXCEEDED"
