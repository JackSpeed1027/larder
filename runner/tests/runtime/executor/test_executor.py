"""S02.EXEC：执行器编排（不联网；依赖全部注入）。"""

from __future__ import annotations

import hashlib
import json
from types import SimpleNamespace

from larder_runner.runtime.executor import ExecOutcome, PublishError, RunExecutor


class FakeFetched:
    content_type = "text/html"
    body = b"<html><body><p>hello</p></body></html>"


class FakeChunk:
    def __init__(self, sha: str) -> None:
        self.sha256 = sha


def build_executor(published: list, publish_error: Exception | None = None) -> RunExecutor:
    def publish(run_id: str, payload: dict) -> str:
        if publish_error is not None:
            raise publish_error
        published.append((run_id, payload))
        return "rev_1"

    def extract(body: bytes, content_type: str):
        return SimpleNamespace(text="hello world")

    def chunk(text: str):
        return [FakeChunk(hashlib.sha256(text.encode()).hexdigest())]

    return RunExecutor(lambda url: FakeFetched(), extract, chunk, publish)


def test_execute_publishes_text_and_manifest() -> None:
    published: list = []
    executor = build_executor(published)

    outcome = executor.execute("run_1", "job_1", 0, "https://example.com/a")

    assert outcome == ExecOutcome(run_id="run_1", state="SUCCEEDED", revision_id="rev_1")
    _, payload = published[0]
    assert payload["jobId"] == "job_1"
    assert payload["processingEpoch"] == 0
    assert payload["text"] == "hello world"
    assert payload["contentSha256"] == hashlib.sha256(b"hello world").hexdigest()
    manifest = json.loads(payload["manifestJson"])
    assert manifest["chunks"] == 1


def test_extract_failure_returns_failed_with_code() -> None:
    class Boom(Exception):
        code = "EXTRACT_EMPTY"

    def broken_extract(body: bytes, content_type: str):
        raise Boom("empty")

    executor = RunExecutor(lambda url: FakeFetched(), broken_extract, lambda text: [], lambda *a: "rev_x")

    outcome = executor.execute("run_2", "job_2", 0, "https://example.com/b")

    assert outcome.state == "FAILED"
    assert outcome.error_code == "EXTRACT_EMPTY"


def test_publish_rejection_is_failed_not_success() -> None:
    executor = build_executor([], publish_error=PublishError(409, '{"code":"EPOCH_SUPERSEDED"}'))

    outcome = executor.execute("run_3", "job_3", 0, "https://example.com/c")

    assert outcome.state == "FAILED"
    assert outcome.error_code == "PUBLISH_REJECTED"
