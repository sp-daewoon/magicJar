# Phase 3 — pyworker (Python) + frontend (React) 병렬

> **Sub-skill:** `superpowers:subagent-driven-development`. 본 plan은 두 에이전트에게 병렬 위임됨.

**Goal:** P2 머지 위에서 (a) `pyworker/` Python 서비스 (FastAPI + Playwright + APScheduler + aiokafka) 골격, (b) `frontend/` React SPA (TS + Vite + LWC + shadcn/ui) 골격. 각자 독립 PR, 독립 머지.

**Source:** spec §7 §11, master plan §Phase 3, ADR 0022 (frontend) + 0023 (pyworker), P1 main(`645d36a` 후속).

**Branches:**
- `agent/ai-engineer/p3-pyworker` (ai-engineer, opus)
- `agent/frontend/p3-frontend` (frontend, sonnet)

**Authors:**
- pyworker: `bot+ai-engineer@magicjar.local`
- frontend: `bot+frontend@magicjar.local`

**병렬 안전성:** 두 에이전트가 다른 디렉토리(`pyworker/`, `frontend/`)에서만 작업하고, 각자 worktree isolation으로 격리됨. 같은 main 기반 두 브랜치 동시 작업.

---

## Phase 3-A: pyworker (ai-engineer, opus)

### File Structure

```
pyworker/
├─ pyproject.toml             uv 또는 poetry (uv 권장 — 빠름)
├─ uv.lock
├─ Dockerfile                 multi-stage: python:3.12-slim + Playwright chromium
├─ docker-entrypoint.sh
├─ .python-version
├─ README.md
├─ app/
│  ├─ __init__.py
│  ├─ main.py                 FastAPI 진입 + 라우터 등록 + lifespan(Kafka producer 시작/종료)
│  ├─ config.py               Pydantic Settings: KAFKA_BOOTSTRAP, S3_*, NAVER_*, DART_*
│  ├─ scrapers/
│  │  ├─ __init__.py
│  │  ├─ naver_news.py        BeautifulSoup + httpx (동기 또는 httpx.AsyncClient)
│  │  ├─ dart.py              opendart-py 또는 직접 httpx
│  │  └─ scheduler.py         APScheduler (BackgroundScheduler) — 정기 크롤링 등록
│  ├─ screenshot/
│  │  ├─ __init__.py
│  │  ├─ capture.py           Playwright async API: 헤드리스 chromium 페이지 스크린샷
│  │  └─ storage.py           MinIO/S3 업로드 (boto3 또는 minio-py)
│  ├─ routers/
│  │  ├─ __init__.py
│  │  ├─ scrape.py            POST /scrape/{source} (수동 트리거)
│  │  ├─ screenshot.py        POST /screenshot {url, viewport} → S3 URL 응답
│  │  └─ health.py            GET /health
│  └─ messaging/
│     ├─ __init__.py
│     ├─ kafka_producer.py    aiokafka AIOKafkaProducer 래퍼 + 토픽 publisher 클래스 3개
│     │                       (NewsRawPublisher, DartDisclosurePublisher, ScreenshotCapturedPublisher)
│     └─ schemas.py           Pydantic models — Kotlin 도메인 이벤트와 호환되는 JSON 스키마
└─ tests/
   ├─ __init__.py
   ├─ conftest.py             pytest fixtures (mock S3·Kafka producer)
   ├─ test_scrapers_naver.py  네이버 RSS 모킹 응답 파싱
   ├─ test_screenshot_capture.py   Playwright stub
   ├─ test_kafka_producer.py  aiokafka 모킹 publish
   ├─ test_routers_health.py  FastAPI TestClient
   └─ test_routers_screenshot.py  POST /screenshot mock S3
```

### 핵심 결정

- **패키지 매니저**: uv (`pip install uv` → `uv sync`). pip-tools보다 10배 빠름.
- **FastAPI lifespan**: app 시작 시 aiokafka producer 시작, 종료 시 graceful close.
- **APScheduler**: BackgroundScheduler를 lifespan 안에서 start. 정기 잡은 매 N분 (config로 조정 가능).
- **Playwright**: `playwright install chromium` (단일 브라우저, 이미지 크기 절감). browser context는 요청마다 새로 생성, 또는 pool. MVP는 매 요청 새 context.
- **MinIO**: minio-py (S3 호환 API).
- **Kafka 스키마 호환**: Pydantic model의 field name·type을 Kotlin domain event 클래스와 일치시킴 (Jackson default snake_case 또는 camelCase 합의 — 합의는 spec §4 그대로 camelCase Jackson default).

### Dockerfile

```dockerfile
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
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

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
```

### 종료 게이트 (Phase 3-A)

- [ ] `cd pyworker && uv sync` 통과
- [ ] `cd pyworker && uv run pytest -v` 모든 테스트 PASS (최소 5건)
- [ ] `cd pyworker && uv run uvicorn app.main:app` 기동 후 `curl localhost:8000/health` → `{"status":"ok"}`
- [ ] `docker build -t magicjar/pyworker:p3 ./pyworker` 성공
- [ ] PR `agent/ai-engineer/p3-pyworker` → main

### 통보 경계 (자율 vs 사용자)

- **API 키 추가**: `OPENDART_API_KEY` 신규 env 추가 시 사용자에게 알림 (외부 자원 변경)
- **Kotlin domain event 스키마와 불일치**: pyworker가 publish하는 Pydantic model이 Kotlin data class와 정확히 매칭 안 되면 Kotlin 측 수정 vs Python 측 수정 결정 필요 — 사용자 통보
- 그 외 자율 판단

---

## Phase 3-B: frontend (frontend, sonnet)

### File Structure

```
frontend/
├─ package.json               pnpm 권장
├─ pnpm-lock.yaml
├─ vite.config.ts             alias @ → src/, proxy /api → http://localhost:8080
├─ tsconfig.json              strict, target ES2022
├─ tailwind.config.ts
├─ postcss.config.js
├─ Dockerfile                 multi-stage: node:20-alpine → nginx:alpine
├─ nginx.conf                 SPA fallback (try_files $uri /index.html)
├─ .gitignore
├─ index.html
├─ README.md
└─ src/
   ├─ main.tsx
   ├─ App.tsx
   ├─ router.tsx              React Router v6 (5 페이지)
   ├─ index.css               tailwind base + shadcn 변수
   ├─ api/
   │  ├─ client.ts            ofetch 또는 fetch 래퍼 (baseURL=/api, credentials)
   │  └─ generated/           OpenAPI orval 자동 생성 (P3는 stub만, P4에서 spec 동기)
   ├─ ws/
   │  └─ stomp.ts             @stomp/stompjs 래퍼 — /ws 연결, 토픽 구독 hooks
   ├─ stores/
   │  ├─ auth.ts              Zustand
   │  ├─ marketTick.ts        Zustand — 실시간 틱 버퍼
   │  └─ position.ts          Zustand
   ├─ pages/
   │  ├─ Dashboard/index.tsx
   │  ├─ Backtest/index.tsx
   │  ├─ Journal/index.tsx
   │  ├─ Strategy/index.tsx
   │  └─ HarnessOps/index.tsx
   ├─ components/
   │  ├─ chart/
   │  │  ├─ Chart.tsx         **LWC 공식 API만** (커스텀 지양 원칙, spec §11-5)
   │  │  ├─ TickChart.tsx
   │  │  ├─ CandleChart.tsx
   │  │  └─ chartCapture.ts   chart.takeScreenshot() → PNG Blob
   │  └─ ui/                  shadcn/ui — Button, Card, Sheet, Combobox 등 (필요 시 1개씩 추가)
   └─ lib/
      ├─ queryClient.ts       TanStack Query
      └─ cn.ts                clsx + twMerge
```

### 핵심 결정

- **패키지 매니저**: pnpm (workspace 친화, 디스크 절약).
- **차트 라이브러리**: TradingView Lightweight Charts v4.x (Apache 2.0). **공식 API만 사용** — `addLineSeries`, `addCandlestickSeries`, `addHistogramSeries`, `update()`, `setData()`, `subscribeVisibleLogicalRangeChange`, `takeScreenshot()`. 캔버스 직접 그리기·DOM hack·`html-to-image` 같은 외부 캡처 라이브러리 도입 **금지** (spec §11-5).
- **차트 캡처**: 1차 `chart.takeScreenshot()`, 실패 시 (P4 docker compose 단계에서) 서버 사이드 pyworker `/screenshot` fallback. P3에서는 1차만.
- **UI 라이브러리**: shadcn/ui — `npx shadcn@latest add <component>`로 필요한 것만 1개씩.
- **상태 관리**: Zustand (전역) + TanStack Query (서버 상태). Redux 안 씀.
- **WebSocket**: @stomp/stompjs로 STOMP 클라이언트. p3은 mock 데이터로 chart 구동, p4에서 api-app 실제 연결.
- **5 페이지**: 골격만 (route + 더미 페이지). 실제 콘텐츠는 P4 통합 시 채움.

### Dockerfile

```dockerfile
# syntax=docker/dockerfile:1.6
FROM node:20-alpine AS builder
RUN corepack enable && corepack prepare pnpm@9 --activate
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:alpine AS runtime
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost/ > /dev/null || exit 1
```

### 종료 게이트 (Phase 3-B)

- [ ] `cd frontend && pnpm install` 성공
- [ ] `cd frontend && pnpm test` (Vitest) 모든 테스트 PASS (최소 5건)
- [ ] `cd frontend && pnpm dev` 기동 후 5 페이지 라우트 접근 가능 (404 없음)
- [ ] `cd frontend && pnpm build` 성공 (`dist/` 생성)
- [ ] `docker build -t magicjar/frontend:p3 ./frontend` 성공
- [ ] Chart 컴포넌트가 LWC 공식 API만 사용하는지 PR 리뷰 (`html-to-image`·캔버스 그리기 grep 0건)
- [ ] PR `agent/frontend/p3-frontend` → main

### 통보 경계 (자율 vs 사용자)

- **API 스펙**: api-app(P2) OpenAPI spec이 아직 없으므로 mock 클라이언트 작성. P4에서 orval로 자동 생성 시점에 통합. 이 시점은 자율.
- **shadcn 추가 컴포넌트**: 자율 (필요한 만큼).
- **외부 라이브러리 추가**: spec §11-5 위반 시(`html-to-image`, drawing libs 등) 사용자 통보 후 거부.
- 그 외 자율.

---

## 후속 단계

- **P4** (architect, opus): `docker/` compose + 통합 스모크 + main 풀 QA. P1·P2·P3 모두 머지된 main 기반.
