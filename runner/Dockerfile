# Runner 镜像：Python 3.12 slim + 精确版本依赖（不使用 uv 是为了少一个构建期镜像）。
# 基础镜像按 digest 固定，见 docs/versions.lock.json 的 python 条目。
FROM python:3.12-slim@sha256:4840d514c583a4d3d8cad3940e68dee6f0f6f3635c12fcd0a3d74c21a5b93a54

WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TZ=UTC

COPY pyproject.toml /app/pyproject.toml
COPY app /app/app

# 版本与 docs/versions.lock.json 一致；不用 requirements.txt 是避免两处版本漂移
RUN pip install --no-cache-dir \
        fastapi==0.162.0 \
        uvicorn==0.54.0 \
        pydantic==2.14.3 \
        pydantic-settings==2.16.2 \
        httpx==0.28.1

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
