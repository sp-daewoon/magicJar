# syntax=docker/dockerfile:1.6
FROM python:3.12-slim AS base
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates wget gnupg \
    libnss3 libatk-bridge2.0-0 libdrm2 libxkbcommon0 libxcomposite1 \
    libxdamage1 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libcairo2 \
    libasound2 libatspi2.0-0 \
 && rm -rf /var/lib/apt/lists/*

FROM base AS builder
RUN pip install uv
WORKDIR /app
# pyproject.toml의 readme="README.md" 검증 + hatchling 빌드용 패키지 소스 함께 복사
COPY pyproject.toml uv.lock* README.md ./
COPY app ./app
RUN uv sync --frozen --no-dev || uv sync --no-dev

FROM base AS runtime
# 비루트 user. playwright는 -u 1000의 home(/home/magicjar)에 chromium을 설치해야 한다.
RUN groupadd -r magicjar && useradd -r -g magicjar -u 1000 -m magicjar
WORKDIR /app
COPY --from=builder /app/.venv /app/.venv
COPY app /app/app
RUN chown -R magicjar:magicjar /app
USER 1000
# magicjar user의 home (/home/magicjar/.cache/ms-playwright)에 chromium 설치
RUN /app/.venv/bin/playwright install chromium
ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --retries=10 \
  CMD curl -f http://localhost:8000/health || exit 1
ENTRYPOINT ["uvicorn","app.main:app","--host","0.0.0.0","--port","8000"]
