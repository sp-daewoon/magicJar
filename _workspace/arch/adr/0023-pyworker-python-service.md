# ADR 0023: Python 모듈 도입 (pyworker — FastAPI + Playwright + APScheduler)

- 상태: Accepted
- 작성일: 2026-04-27
- 결정자: architect (협의: market-data, ai-engineer, qa)
- 관계: ADR 0019(MSA 재구성), ADR 0020(Kafka 백본)

## 맥락

한국 주식 자동매매에서 다음 작업은 Python 생태계가 압도적으로 유리하다.

- 뉴스 스크래핑 (네이버 금융·증권사 리서치) — `httpx` + `BeautifulSoup`/`selectolax`
- DART 공시 — `opendart-py` 같은 한국어 SDK 다수
- 헤드리스 브라우저 캡처 (차트 페이지 전체 PNG, JS 렌더 후 캡처) — Playwright 파이썬 바인딩이 가장 안정
- 추후 분석 스크립트(pandas/polars), ML 실험

JVM에서 같은 일을 하면: jsoup·htmlunit은 한국 사이트 JS 렌더링 약함, Selenium-Java는 운영 부담, Playwright Java는 가능하지만 Python 대비 자료·생태계 빈약.

## 검토한 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| A. JVM에서 다 처리 | 단일 런타임 | 위 작업들의 도구 부족 |
| B. JVM + 서버리스(Lambda) Python | 인프라 분리 | 콜드스타트, 로컬 dev 복잡, Playwright 패키징 까다로움 |
| C. **Python 1급 모듈(pyworker)** + Kafka·HTTP·MinIO만으로 결합 (본 결정) | 도구 최적, 결합 차단 명확 | Python 런타임 추가, 컨테이너 +1 |

## 결정

**Python 3.12 + FastAPI + Playwright + APScheduler 기반 `pyworker`를 1급 모듈로 도입한다.**

### 구조

```
pyworker/
├─ pyproject.toml            # uv 또는 poetry
├─ Dockerfile                # python:3.12-slim + Playwright chromium
├─ docker-entrypoint.sh
└─ app/
   ├─ main.py                # FastAPI 진입
   ├─ config.py              # KAFKA_BOOTSTRAP, S3_ENDPOINT, ...
   ├─ scrapers/
   │  ├─ naver_news.py       # httpx + BeautifulSoup
   │  ├─ dart.py             # opendart-py
   │  └─ scheduler.py        # APScheduler 정기 수집
   ├─ screenshot/
   │  ├─ capture.py          # Playwright 헤드리스 (URL → PNG)
   │  └─ storage.py          # MinIO 업로드
   ├─ routers/
   │  ├─ scrape.py           # POST /scrape/{source}
   │  ├─ screenshot.py       # POST /screenshot
   │  └─ health.py           # GET  /health
   └─ messaging/
      └─ kafka_producer.py   # aiokafka — news.raw.v1, dart.disclosure.v1
```

### 책임 경계 — 결합 차단

- **pyworker는 PostgreSQL 직접 접근 금지.** DB는 JVM 측 책임 영역.
- pyworker가 외부와 통신하는 채널은 **3개로만 한정**: (1) Kafka 발행, (2) HTTP 응답(api-app가 호출), (3) MinIO 업로드.
- 영속성이 필요한 결과물은 Kafka로 발행하거나 MinIO에 올리고, JVM 측이 받아 DB에 기록.
- pyworker → JVM 직접 호출 금지 (단방향: JVM이 pyworker를 호출하거나, pyworker가 Kafka/MinIO에 쓰면 JVM이 소비).

### 트리거별 통신

| 트리거 | 통신 | 출력 |
|--------|------|------|
| 정기 크롤링 | APScheduler 내부 → Kafka publish | `news.raw.v1`, `dart.disclosure.v1` |
| 수동 스크래핑 | api-app → pyworker `POST /scrape/{source}` | HTTP 응답 + Kafka publish |
| 서버사이드 캡처 | api-app → pyworker `POST /screenshot` | MinIO 저장 + URL 응답 + `screenshot.captured.v1` |
| 클라이언트 캡처 | 프런트 → api-app `/journal/{id}/snapshots` (LWC takeScreenshot 결과) | api-app DB + MinIO (pyworker 미경유) |

### 패키징

- 베이스: `python:3.12-slim`
- Playwright chromium만 설치 (`playwright install --with-deps chromium`) — 이미지 ~1GB+ 수용
- 비루트 유저 (`USER 1000`)
- 헬스체크: `GET /health` → `{"status": "ok"}`

### 의존성 핵심

- `fastapi` + `uvicorn[standard]`
- `aiokafka` — Kafka producer
- `playwright` — 헤드리스 브라우저
- `httpx` + `beautifulsoup4` (또는 `selectolax`) — 스크래핑
- `apscheduler` — 정기 작업
- `boto3` 또는 `minio` — 객체 저장소 (ADR 0026)
- `pydantic` v2 — 스키마

## 결과

- 긍정: 한국 도메인 스크래핑/캡처가 본격 도구로 이동. JVM과 결합 차단으로 폭주·장애 격리 자연스러움.
- 부정: 런타임 1개 추가 → 로컬 docker compose 컨테이너 +1, 이미지 크기 +1GB+, 운영 모니터링 대상 +1.
- 후속:
  - `_workspace/python/setup-notes.md` 신규 작성 (uv·Playwright·Kafka·pytest)
  - ai-engineer 책임 확장: Spring AI(JVM) + pyworker 프롬프트 일관성 (ADR 0014 footnote 참고)
  - QA 자동화: Playwright via pyworker로 프런트 시나리오 캡처도 가능 (qa 에이전트 검토)

## Links

- spec: `docs/superpowers/specs/2026-04-27-msa-restructure-design.md` §7
- 관련 ADR: 0019, 0020, 0026, 0014(개정 footnote)
