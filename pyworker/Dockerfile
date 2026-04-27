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
COPY pyproject.toml uv.lock* ./
RUN uv sync --frozen --no-dev || uv sync --no-dev

FROM base AS runtime
RUN groupadd -r magicjar && useradd -r -g magicjar -u 1000 magicjar
WORKDIR /app
COPY --from=builder /app/.venv /app/.venv
COPY app /app/app
RUN /app/.venv/bin/playwright install chromium --with-deps
USER 1000
ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --retries=10 \
  CMD curl -f http://localhost:8000/health || exit 1
ENTRYPOINT ["uvicorn","app.main:app","--host","0.0.0.0","--port","8000"]
