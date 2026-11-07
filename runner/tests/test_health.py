"""健康检查的契约测试。

这些用例能在实现被改坏时失败：路由不存在、返回结构变了、状态不是 ok 都会红。
"""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_ping_returns_ok() -> None:
    resp = client.get("/ping")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["service"] == "larder-runner"


def test_health_reports_version_and_config() -> None:
    resp = client.get("/v1/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["version"] == "0.1.0"
    assert body["protocolVersion"] == "v1"
    # 配置必须真的来自设置对象，而不是写死在路由里
    assert body["journalDir"]
    assert isinstance(body["maxConcurrentRuns"], int)
