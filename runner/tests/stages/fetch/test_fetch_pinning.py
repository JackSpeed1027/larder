"""S06：解析与连接绑定（防 TOCTOU / DNS rebinding）。"""

from __future__ import annotations

import socket

import pytest

from larder_runner.stages.fetch import (
    FetchError,
    UrlGuard,
    _PinnedHTTPConnection,
    _PinnedHTTPSConnection,
    build_opener,
)


def resolver(*addresses: str):
    def resolve(host: str, port: int):
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, port)) for address in addresses]

    return resolve


def test_resolve_for_returns_validated_ip() -> None:
    guard = UrlGuard(resolver=resolver("93.184.216.34"))
    host, port, pinned = guard.resolve_for("https://example.com/a?b=1")
    assert (host, port, pinned) == ("example.com", 443, "93.184.216.34")


def test_resolve_for_blocks_private_even_if_public_present() -> None:
    guard = UrlGuard(resolver=resolver("93.184.216.34", "10.0.0.1"))
    with pytest.raises(FetchError) as error:
        guard.resolve_for("http://example.com/")
    assert error.value.code == "SSRF_BLOCKED"


def test_http_connection_uses_pinned_ip(monkeypatch) -> None:
    captured = {}

    def fake_create_connection(target, timeout=None):
        captured["target"] = target
        return object()

    monkeypatch.setattr(socket, "create_connection", fake_create_connection)
    connection = _PinnedHTTPConnection("example.com", 80, "93.184.216.34")
    connection.connect()
    assert captured["target"] == ("93.184.216.34", 80)


def test_https_connection_keeps_sni_and_pins_ip(monkeypatch) -> None:
    captured = {}

    def fake_create_connection(target, timeout=None):
        captured["target"] = target
        return "raw-socket"

    class FakeContext:
        def wrap_socket(self, sock, server_hostname=None):
            captured["sni"] = server_hostname
            return "tls-socket"

    monkeypatch.setattr(socket, "create_connection", fake_create_connection)
    connection = _PinnedHTTPSConnection("example.com", 443, "93.184.216.34", context=FakeContext())
    connection.connect()
    assert captured["target"] == ("93.184.216.34", 443)
    assert captured["sni"] == "example.com"
    assert connection.sock == "tls-socket"


def test_build_opener_has_no_proxy() -> None:
    import urllib.request

    opener = build_opener(UrlGuard(resolver=resolver("93.184.216.34")))
    proxies = [h for h in opener.handlers if isinstance(h, urllib.request.ProxyHandler)]
    assert all(handler.proxies == {} for handler in proxies)
