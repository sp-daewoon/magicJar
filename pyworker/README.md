# pyworker

magicJar Python 1급 모듈 — FastAPI + Playwright + APScheduler + aiokafka.

## 책임

- 한국 금융 뉴스 스크래핑 (네이버 금융)
- DART 공시 수집 (OpenDART API)
- 헤드리스 차트·페이지 캡처 (Playwright chromium)
- 결과를 Kafka 발행 또는 MinIO에 업로드

## 통신 경계 (ADR 0023)

| 채널 | 방향 | 용도 |
|------|------|------|
| Kafka publish | pyworker → JVM | `news.raw.v1`, `dart.disclosure.v1`, `screenshot.captured.v1` |
| HTTP request | api-app → pyworker | 수동 트리거 (`POST /scrape/{source}`, `POST /screenshot`) |
| MinIO/S3 | pyworker → MinIO | 스크린샷 PNG 업로드 |

**금지**: PostgreSQL 직접 접근, JVM 측 직접 호출.

## 빠른 시작

```bash
# 1) uv 설치 (한 번만)
brew install uv  # 또는 curl -LsSf https://astral.sh/uv/install.sh | sh

# 2) 의존성 설치 + virtualenv 생성
cd pyworker
uv sync

# 3) Playwright chromium 설치 (실제 캡처 사용 시)
uv run playwright install chromium

# 4) 테스트
uv run pytest -v

# 5) 로컬 실행 (외부 의존 비활성화 — Kafka·MinIO connect 안 함)
uv run uvicorn app.main:app --port 8000

# 6) 외부 의존 포함 실행 (docker compose에서 Kafka·MinIO 가동된 상태)
BOOTSTRAP_EXTERNAL=true SCHEDULER_ENABLED=true \
KAFKA_BOOTSTRAP=localhost:9092 \
S3_ENDPOINT=localhost:9000 S3_ACCESS_KEY=minioadmin S3_SECRET_KEY=minioadmin \
uv run uvicorn app.main:app --port 8000
```

## 환경변수

| 키 | 기본값 | 설명 |
|----|--------|------|
| `BOOTSTRAP_EXTERNAL` | `false` | Kafka/MinIO/Playwright 실 connect 여부 (false면 stub/in-memory) |
| `SCHEDULER_ENABLED` | `false` | APScheduler 정기 잡 등록 여부 |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | |
| `KAFKA_TOPIC_NEWS_RAW` | `news.raw.v1` | Kotlin `NewsRawEvent` 호환 |
| `KAFKA_TOPIC_DART` | `dart.disclosure.v1` | Kotlin `DartDisclosureEvent` 호환 |
| `KAFKA_TOPIC_SCREENSHOT` | `screenshot.captured.v1` | (Kotlin 클래스 P4에 추가 예정) |
| `S3_ENDPOINT` | `localhost:9000` | |
| `S3_ACCESS_KEY` | `minioadmin` | |
| `S3_SECRET_KEY` | `minioadmin` | |
| `S3_BUCKET_SCREENSHOT` | `magicjar-screenshots` | |
| `DART_API_KEY` | _(빈값)_ | 미설정 시 DART scraper는 no-op |
| `NAVER_NEWS_LIST_URL` | `https://finance.naver.com/news/news_list.naver` | |
| `SCRAPE_INTERVAL_NEWS_SEC` | `300` | |
| `SCRAPE_INTERVAL_DART_SEC` | `600` | |
| `PLAYWRIGHT_HEADLESS` | `true` | |
| `PLAYWRIGHT_DEFAULT_TIMEOUT` | `15000` | ms |

## 엔드포인트

- `GET /health` — `{"status": "ok"}`
- `POST /scrape/naver` — 네이버 뉴스 1회 fetch + Kafka publish
- `POST /scrape/dart` — DART 공시 1회 fetch + Kafka publish
- `POST /screenshot` — body `{url, viewport, fullPage, requestedBy}` → S3 저장 + Kafka publish

## Kotlin domain event 스키마 호환

Pydantic 모델 (`app/messaging/schemas.py`)이 Kotlin data class와 **필드명·타입 모두 동일**하게 직렬화한다.

- `NewsRawEvent` ↔ `com.mj.domain.news.event.NewsRawEvent`
- `DartDisclosureEvent` ↔ `com.mj.domain.news.event.DartDisclosureEvent`
- `ScreenshotCapturedEvent` ↔ `com.mj.domain.market.event.ScreenshotCapturedEvent` _(Kotlin 측은 P4에서 동일 필드로 추가 예정)_

발행 시 Kafka 헤더 `__TypeId__` 에 Kotlin FQN을 같이 보내므로, Spring Kafka `JsonDeserializer` 가 클래스를 정확히 매핑한다 (또는 `spring.json.type.mapping` 으로 alias 매핑 가능).

직렬화 규약:
- 필드명: camelCase
- `UUID`: 문자열
- `Instant`: ISO-8601 UTC (`2026-04-27T09:30:00Z`)
- `BigDecimal`: 문자열 (Pydantic Decimal 도입은 P4+에서)

## Docker

```bash
docker build -t magicjar/pyworker:p3 ./pyworker
docker run --rm -p 8000:8000 -e BOOTSTRAP_EXTERNAL=false magicjar/pyworker:p3
curl localhost:8000/health
```

## 구조

```
pyworker/
├─ pyproject.toml           uv 의존성
├─ Dockerfile               python:3.12-slim + Playwright chromium + USER 1000
├─ docker-entrypoint.sh
├─ .python-version          → 3.12
├─ app/
│  ├─ main.py               FastAPI + lifespan
│  ├─ config.py             Pydantic Settings
│  ├─ scrapers/             naver_news.py · dart.py · scheduler.py
│  ├─ screenshot/           capture.py (Playwright/Stub) · storage.py (MinIO/InMemory)
│  ├─ routers/              health.py · scrape.py · screenshot.py
│  └─ messaging/            kafka_producer.py · schemas.py
└─ tests/                   conftest + 5 테스트 모듈
```
