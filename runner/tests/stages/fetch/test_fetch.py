"""抓取阶段：真实本地 HTTP 服务 + SSRF 守卫边界。"""

from __future__ import annotations

import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from larder_runner.stages.fetch import FetchError, UrlGuard, fetch


class _Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/page":
            body = b"<html><body><p>hello</p></body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/page")
            self.end_headers()
        elif self.path == "/image":
            body = b"\x89PNG"
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, *args) -> None:  # 静默
        return


@pytest.fixture()
def server() -> str:
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


def test_fetch_returns_body_and_content_type(server: str) -> None:
    result = fetch(f"{server}/page", guard=UrlGuard(allow_private=True))
    assert result.status == 200
    assert result.content_type == "text/html"
    assert "hello" in result.text


def test_fetch_follows_redirect_and_reports_final_url(server: str) -> None:
    result = fetch(f"{server}/redirect", guard=UrlGuard(allow_private=True))
    assert result.final_url.endswith("/page")


def test_fetch_rejects_unsupported_content_type(server: str) -> None:
    with pytest.raises(FetchError) as info:
        fetch(f"{server}/image", guard=UrlGuard(allow_private=True))
    assert info.value.code == "UNSUPPORTED_CONTENT_TYPE"


def test_fetch_enforces_size_limit(server: str) -> None:
    with pytest.raises(FetchError) as info:
        fetch(f"{server}/page", guard=UrlGuard(allow_private=True), max_bytes=10)
    assert info.value.code == "PAYLOAD_TOO_LARGE"


def test_guard_blocks_loopback_and_metadata() -> None:
    guard = UrlGuard()
    with pytest.raises(FetchError) as info:
        fetch("http://127.0.0.1:1/", guard=guard)
    assert info.value.code == "SSRF_BLOCKED"

    private_guard = UrlGuard(resolver=lambda host, port: [
        (2, 1, 6, "", ("169.254.169.254", 0)),
    ])
    with pytest.raises(FetchError) as metadata:
        private_guard.check("http://metadata.example/")
    assert metadata.value.code == "SSRF_BLOCKED"


def test_guard_allows_public_resolution() -> None:
    guard = UrlGuard(resolver=lambda host, port: [(2, 1, 6, "", ("93.184.216.34", 0))])
    guard.check("https://example.com/page")  # 不抛即通过


def test_guard_rejects_non_http_scheme() -> None:
    with pytest.raises(FetchError) as info:
        UrlGuard(allow_private=True).check("file:///etc/passwd")
    assert info.value.code == "SSRF_BLOCKED"
