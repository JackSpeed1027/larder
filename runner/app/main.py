"""Runner 的 FastAPI 入口（V01-T02 骨架）。

只暴露健康检查与版本信息，不包含任何业务路由：真实 run 协议（/v1/runs...）由
V01-T03 依据 contracts/runner.openapi.json 生成，V03 再实现持久化与 SSE。
"""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import FastAPI
from pydantic_settings import BaseSettings, SettingsConfigDict

from larder_runner.api.runs import RunRegistry, build_runs_router

APP_VERSION = "0.1.0"


class Settings(BaseSettings):
    """Runner 配置：全部走 LARDER_ 前缀的环境变量，启动时就校验。"""

    model_config = SettingsConfigDict(env_prefix="LARDER_RUNNER_", extra="ignore")

    journal_dir: str = "/var/lib/larder/journal"
    max_concurrent_runs: int = 2


settings = Settings()
app = FastAPI(title="Larder Runner", version=APP_VERSION)
app.include_router(build_runs_router(RunRegistry(settings.max_concurrent_runs)))


def _now() -> str:
    """统一用 UTC ISO8601，和 Java 侧的时间语义保持一致。"""
    return datetime.now(timezone.utc).isoformat()


@app.get("/ping")
def ping() -> dict[str, str]:
    """最小存活探针，不访问磁盘/网络。"""
    return {"status": "ok", "service": "larder-runner", "time": _now()}


@app.get("/v1/health")
def health() -> dict[str, object]:
    """健康检查：报告版本与关键配置，不伪装已连上模型或对象存储。"""
    return {
        "status": "ok",
        "service": "larder-runner",
        "version": APP_VERSION,
        "protocolVersion": "v1",
        "journalDir": settings.journal_dir,
        "maxConcurrentRuns": settings.max_concurrent_runs,
        "time": _now(),
    }
