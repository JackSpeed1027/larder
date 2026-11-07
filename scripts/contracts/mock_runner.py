"""契约 Mock 服务：用 contracts/examples 里的 fixture 响应，不内联第二份数据。

启动：
    uv run --with jsonschema python scripts/contracts/mock_runner.py --port 8090

端点：
    GET  /health                        -> {"status":"ok"}
    GET  /api/ping                      -> valid-api-response.json（Java 公开壳）
    POST /v1/runs                       -> 严格校验 run-request.schema.json，合法 202 + queued fixture，非法 400
    GET  /v1/runs/{runId}               -> valid-run-response.json
    GET  /v1/runs/{runId}/events        -> SSE，逐条发送 valid-run-event-*.json，带 id/event，末尾心跳注释

设计约束：Mock 只读 fixture，保证前端/Mock 与真实 Runner 共用同一份契约样例；
请求体一律按 schema 校验，避免"Mock 比真实更宽松"。
"""

from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import jsonschema

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "contracts" / "examples"


def fixture(name: str) -> dict:
    return json.loads((EXAMPLES / name).read_text(encoding="utf-8"))


RUN_REQUEST_VALIDATOR = jsonschema.Draft202012Validator(
    json.loads((ROOT / "contracts" / "run-request.schema.json").read_text(encoding="utf-8"))
)


class MockHandler(BaseHTTPRequestHandler):
    server_version = "LarderContractMock/0.1"

    def log_message(self, fmt: str, *args) -> None:  # noqa: D401 - 保持标准库签名
        print(f"[mock] {self.address_string()} {fmt % args}")

    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_text(self, status: int, text: str, content_type: str = "text/plain; charset=utf-8") -> None:
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 - 标准库回调
        if self.path == "/health":
            self._send_json(200, {"status": "ok"})
        elif self.path == "/api/ping":
            self._send_json(200, fixture("valid-api-response.json"))
        elif self.path.startswith("/v1/runs/") and self.path.endswith("/events"):
            self._stream_events()
        elif self.path.startswith("/v1/runs/"):
            self._send_json(200, fixture("valid-run-response.json"))
        else:
            self._send_json(404, {"code": "NOT_FOUND", "message": "no such mock route"})

    def do_POST(self) -> None:  # noqa: N802 - 标准库回调
        if self.path != "/v1/runs":
            self._send_json(404, {"code": "NOT_FOUND", "message": "no such mock route"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw)
        except json.JSONDecodeError as exc:
            self._send_json(400, {"code": "VALIDATION_FAILED", "message": f"invalid json: {exc}"})
            return
        errors = sorted(RUN_REQUEST_VALIDATOR.iter_errors(body), key=lambda e: list(e.path))
        if errors:
            self._send_json(400, {
                "code": "VALIDATION_FAILED",
                "message": errors[0].message,
                "path": "/".join(str(p) for p in errors[0].path),
            })
            return
        self._send_json(202, fixture("valid-run-response-queued.json"))

    def _stream_events(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        for name in ("valid-run-event-succeeded.json", "valid-run-event-failed.json"):
            event = fixture(name)
            frame = (
                f"id: {event['seq']}\n"
                f"event: {event['type']}\n"
                f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
            )
            self.wfile.write(frame.encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.05)
        # 心跳用注释行，不占 seq（docs/04 §5）
        self.wfile.write(b": keep-alive\n\n")
        self.wfile.flush()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8090)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), MockHandler)
    print(f"contract mock listening on http://127.0.0.1:{args.port}")
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        server.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
