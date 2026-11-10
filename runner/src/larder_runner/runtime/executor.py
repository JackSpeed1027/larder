"""S02.EXEC：run 执行器（fetch → extract → chunk → 回传 Java 发布）。

设计约束：
- 只做一次逻辑 run：同 runId 由 /v1/runs 的幂等登记保证不会重复执行；
- 依赖全部注入（fetch/extract/chunk/publish），便于单测且不把网络写死；
- 失败必须把状态落到 FAILED 并保留错误码，不能静默吞掉；
- 回传 Java 用内部的 `/internal/runs/{runId}/result`，带 X-Internal-Token；
- 这里不做抓取的 SSRF 二次校验——由 stages.fetch 的 UrlGuard 负责，executor 只调用它。
"""

from __future__ import annotations

import hashlib
import json
import urllib.request
from dataclasses import dataclass
from typing import Callable, Protocol


class Fetched(Protocol):
    content_type: str
    body: bytes


@dataclass(frozen=True)
class ExecOutcome:
    run_id: str
    state: str
    revision_id: str | None = None
    error_code: str | None = None


class PublishError(RuntimeError):
    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"publish failed: {status} {body[:200]}")
        self.status = status


class JavaPublisher:
    """把结果回传 Java；错误码保持稳定，不吞异常。"""

    def __init__(self, base_url: str, token: str, timeout: float = 10.0) -> None:
        self._base = base_url.rstrip("/")
        self._token = token
        self._timeout = timeout

    def __call__(self, run_id: str, payload: dict) -> str:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            f"{self._base}/internal/runs/{run_id}/result",
            data=body,
            method="POST",
            headers={"Content-Type": "application/json", "X-Internal-Token": self._token},
        )
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:
                data = json.loads(response.read().decode("utf-8") or "{}")
                return str(data.get("revisionId", ""))
        except urllib.error.HTTPError as exc:
            raise PublishError(exc.code, exc.read().decode("utf-8", errors="replace")) from exc
        except urllib.error.URLError as exc:
            raise PublishError(0, str(exc.reason)) from exc


class RunExecutor:
    """执行一次 run：抓取 → 抽取 → 分段 → 发布。"""

    EXTRACTOR_VERSION = "extract-v1"
    CHUNKER_VERSION = "chunk-v1"

    def __init__(
        self,
        fetch: Callable[[str], Fetched],
        extract: Callable[[bytes, str], object],
        chunk: Callable[[str], list],
        publish: JavaPublisher,
    ) -> None:
        self._fetch = fetch
        self._extract = extract
        self._chunk = chunk
        self._publish = publish

    def execute(self, run_id: str, job_id: str, epoch: int, url: str) -> ExecOutcome:
        try:
            fetched = self._fetch(url)
            extracted = self._extract(fetched.body, fetched.content_type)
            text = getattr(extracted, "text")
            chunks = self._chunk(text)
            manifest = {
                "chunks": len(chunks),
                "chunkSha256": [c.sha256 for c in chunks],
            }
            revision_id = self._publish(run_id, {
                "jobId": job_id,
                "runId": run_id,
                "processingEpoch": epoch,
                "text": text,
                "contentSha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                "extractorVersion": self.EXTRACTOR_VERSION,
                "chunkerVersion": self.CHUNKER_VERSION,
                "manifestJson": json.dumps(manifest, ensure_ascii=False),
            })
            return ExecOutcome(run_id=run_id, state="SUCCEEDED", revision_id=revision_id)
        except PublishError as exc:
            code = "PUBLISH_REJECTED" if exc.status >= 400 else "JAVA_UNAVAILABLE"
            return ExecOutcome(run_id=run_id, state="FAILED", error_code=code)
        except Exception as exc:  # 抓取/抽取/分段的领域异常
            code = getattr(exc, "code", None) or type(exc).__name__.upper()
            return ExecOutcome(run_id=run_id, state="FAILED", error_code=code)
