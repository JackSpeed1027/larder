"""网页抓取：每跳都做 SSRF 校验、限制重定向/大小/类型。

安全边界（docs/02、AGENTS）：
- 只允许 http/https；
- 目标 IP 不得是 loopback/private/link-local/保留地址（含云元数据 169.254.169.254）；
- 每次重定向都重新解析并重新校验，防止"公网域名 302 到内网"；
- 限制重定向次数、响应大小与内容类型，避免被大文件/流拖死；\n- 不继承系统/环境代理（显式 ProxyHandler({})），防止请求被代理改写到内网。
"""

from __future__ import annotations

import ipaddress
import socket
import urllib.error
import urllib.request
from dataclasses import dataclass
from urllib.parse import urlsplit

ALLOWED_CONTENT_TYPES = (
    "text/html",
    "application/xhtml+xml",
    "text/plain",
    "text/markdown",
)


class FetchError(RuntimeError):
    """抓取失败：code 是稳定短语，message 可安全展示。"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


@dataclass(frozen=True)
class FetchResult:
    url: str
    final_url: str
    status: int
    content_type: str
    body: bytes

    @property
    def text(self) -> str:
        return self.body.decode("utf-8", errors="replace")


def _is_public_address(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    return not (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    )


class UrlGuard:
    """SSRF 守卫：按主机名解析出的每一个地址判断，全部必须是公网地址。"""

    def __init__(self, allow_private: bool = False, resolver=socket.getaddrinfo) -> None:
        self._allow_private = allow_private
        self._resolver = resolver

    def check(self, url: str) -> None:
        parts = urlsplit(url)
        if parts.scheme not in ("http", "https"):
            raise FetchError("SSRF_BLOCKED", f"不允许的协议: {parts.scheme}")
        host = parts.hostname
        if not host:
            raise FetchError("SSRF_BLOCKED", "URL 缺少主机名")
        if self._allow_private:
            return
        try:
            infos = self._resolver(host, parts.port or (443 if parts.scheme == "https" else 80))
        except socket.gaierror as exc:
            raise FetchError("DNS_FAILED", f"无法解析主机 {host}") from exc
        for info in infos:
            address = ipaddress.ip_address(info[4][0])
            if not _is_public_address(address):
                raise FetchError("SSRF_BLOCKED", f"目标地址不是公网地址: {address}")


class _GuardedRedirectHandler(urllib.request.HTTPRedirectHandler):
    """每次重定向都调用 guard；超过次数直接失败。"""

    max_redirections = 3

    def __init__(self, guard: UrlGuard) -> None:
        self._guard = guard

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102
        self._guard.check(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(
    url: str,
    *,
    guard: UrlGuard | None = None,
    timeout: float = 10.0,
    max_bytes: int = 5 * 1024 * 1024,
    opener=None,
) -> FetchResult:
    """抓取一个 URL；失败抛 FetchError。"""
    guard = guard or UrlGuard()
    guard.check(url)
    handler = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), _GuardedRedirectHandler(guard))
    request = urllib.request.Request(url, headers={"User-Agent": "larder-runner/0.1"})
    try:
        with handler.open(request, timeout=timeout) as response:
            content_type = (response.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            if content_type not in ALLOWED_CONTENT_TYPES:
                raise FetchError("UNSUPPORTED_CONTENT_TYPE", content_type or "missing")
            body = response.read(max_bytes + 1)
            if len(body) > max_bytes:
                raise FetchError("PAYLOAD_TOO_LARGE", f"超过 {max_bytes} 字节")
            return FetchResult(
                url=url,
                final_url=response.geturl(),
                status=response.status,
                content_type=content_type,
                body=body,
            )
    except urllib.error.HTTPError as exc:
        raise FetchError("HTTP_ERROR", f"{exc.code}") from exc
    except urllib.error.URLError as exc:
        raise FetchError("NETWORK_ERROR", str(exc.reason)) from exc
    except TimeoutError as exc:
        raise FetchError("TIMEOUT", "读取超时") from exc
