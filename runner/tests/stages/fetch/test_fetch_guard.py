"""S06：抓取安全守卫（SSRF 地址族、代理、重定向）。"""

from __future__ import annotations

import pytest
import urllib.request

from larder_runner.stages.fetch import FetchError, UrlGuard, _GuardedRedirectHandler


def resolver_returning(*addresses: str):
    def resolve(host: str, port: int):
        return [(0, 0, 0, "", (address, port or 443)) for address in addresses]

    return resolve


@pytest.mark.parametrize(
    "address",
    [
        "127.0.0.1",
        "10.0.0.5",
        "192.168.1.10",
        "169.254.169.254",
        "::1",
        "fc00::1",
    ],
)
def test_blocks_non_public_addresses(address: str) -> None:
    guard = UrlGuard(resolver=resolver_returning(address))
    with pytest.raises(FetchError) as error:
        guard.check("https://example.com/page")
    assert error.value.code == "SSRF_BLOCKED"


def test_allows_public_address() -> None:
    guard = UrlGuard(resolver=resolver_returning("93.184.216.34"))
    guard.check("https://example.com/page")


def test_blocks_dns_with_mixed_public_and_private() -> None:
    guard = UrlGuard(resolver=resolver_returning("93.184.216.34", "10.0.0.1"))
    with pytest.raises(FetchError):
        guard.check("https://example.com/")


def test_blocks_unsupported_scheme() -> None:
    guard = UrlGuard(resolver=resolver_returning("93.184.216.34"))
    with pytest.raises(FetchError):
        guard.check("file:///etc/passwd")


def test_redirect_target_is_revalidated() -> None:
    guard = UrlGuard(resolver=resolver_returning("10.0.0.1"))
    handler = _GuardedRedirectHandler(guard)
    with pytest.raises(FetchError):
        handler.redirect_request(None, None, 302, "Found", {}, "http://metadata.internal/latest")


def test_fetch_builds_opener_without_environment_proxy() -> None:
    """不得继承系统/环境代理：显式清空 ProxyHandler。"""
    guard = UrlGuard(resolver=resolver_returning("93.184.216.34"))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _GuardedRedirectHandler(guard))
    proxies = [h for h in opener.handlers if isinstance(h, urllib.request.ProxyHandler)]
    # build_opener 会直接丢弃空的 ProxyHandler；两种结果都表示“没有代理”
    assert all(handler.proxies == {} for handler in proxies)
