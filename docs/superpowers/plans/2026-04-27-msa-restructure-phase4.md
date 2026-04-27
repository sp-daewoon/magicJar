# Phase 4 — Docker compose + 통합 스모크 + main 풀 QA

> **Sub-skill:** `superpowers:subagent-driven-development`. 본 plan은 architect 에이전트(opus, ADR 0014)에게 위임됨.

**Goal:** P1·P2·P3 머지된 main 위에서 (a) `docker/` compose 토폴로지 (3 모드), (b) 통합 스모크 스크립트(5 시나리오, spec §15-2), (c) bootRun + 컨테이너 검증, (d) QA 풀 게이트(ADR 0016 — 7 트리거 중 5개 해당 = 본격 QA 1회), (e) main 머지 + Phase D 종료 기록.

**Source:** spec §8 §10 §15, master plan §Phase 4, ADR 0024 (Docker), 0025 (DB), 0026 (MinIO).

**Branch:** `agent/architect/p4-docker-smoke`
**Author:** `bot+architect@magicjar.local`
**Model:** opus (architect)

---

## 0. Pre-flight

- [ ] `git checkout main && git pull` (P3 머지 후 main = `646e6a7` 또는 그 이후)
- [ ] 4 Gradle 모듈 빌드 통과: `./gradlew clean build`
- [ ] frontend·pyworker 디렉토리 모두 존재 + Dockerfile 존재
- [ ] 작업 브랜치 `git checkout -b agent/architect/p4-docker-smoke`

---

## 1. docker-compose 3종

### 1-A. `docker/docker-compose.yml` — 전체 스택 (인프라 + 5 앱)

```yaml
services:
  # ─── 인프라 ───
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: magicjar
      POSTGRES_USER: magicjar
      POSTGRES_PASSWORD: magicjar
    ports: ["5432:5432"]
    volumes: [pg_data:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD","pg_isready","-U","magicjar"]
      interval: 10s
      retries: 10

  kafka:
    image: bitnami/kafka:3.7
    environment:
      KAFKA_CFG_NODE_ID: 1
      KAFKA_CFG_PROCESS_ROLES: controller,broker
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: "true"
    ports: ["9092:9092"]
    healthcheck:
      test: ["CMD-SHELL","kafka-topics.sh --bootstrap-server localhost:9092 --list || exit 1"]
      interval: 15s
      retries: 10

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD","redis-cli","ping"]
      interval: 5s
      retries: 5

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: magicjar
      MINIO_ROOT_PASSWORD: magicjar123
    ports: ["9000:9000","9001:9001"]
    volumes: [minio_data:/data]
    healthcheck:
      test: ["CMD-SHELL","curl -f http://localhost:9000/minio/health/live || exit 1"]
      interval: 10s
      retries: 10

  # ─── 앱 ───
  api-app:
    build: { context: ../api, dockerfile: Dockerfile }
    image: magicjar/api:p4
    depends_on:
      postgres: { condition: service_healthy }
      kafka: { condition: service_healthy }
      redis: { condition: service_healthy }
      minio: { condition: service_healthy }
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/magicjar
      DB_USER: magicjar
      DB_PASSWORD: magicjar
      KAFKA_BOOTSTRAP: kafka:9092
      REDIS_HOST: redis
      MINIO_ENDPOINT: http://minio:9000
      MINIO_ACCESS_KEY: magicjar
      MINIO_SECRET_KEY: magicjar123
      PYWORKER_URL: http://pyworker:8000
    ports: ["8080:8080"]

  batch-app:
    build: { context: ../batch, dockerfile: Dockerfile }
    image: magicjar/batch:p4
    depends_on:
      postgres: { condition: service_healthy }
      kafka: { condition: service_healthy }
    environment: # api와 동일 + spring.batch.job.enabled=false
      DB_URL: jdbc:postgresql://postgres:5432/magicjar
      DB_USER: magicjar
      DB_PASSWORD: magicjar
      KAFKA_BOOTSTRAP: kafka:9092
    ports: ["8081:8081"]

  consumer-app:
    build: { context: ../consumer, dockerfile: Dockerfile }
    image: magicjar/consumer:p4
    depends_on:
      kafka: { condition: service_healthy }
      postgres: { condition: service_healthy }
      minio: { condition: service_healthy }
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/magicjar
      KAFKA_BOOTSTRAP: kafka:9092
      MINIO_ENDPOINT: http://minio:9000
    ports: ["8082:8082"]

  pyworker:
    build: { context: ../pyworker, dockerfile: Dockerfile }
    image: magicjar/pyworker:p4
    depends_on:
      kafka: { condition: service_healthy }
      minio: { condition: service_healthy }
    environment:
      KAFKA_BOOTSTRAP: kafka:9092
      MINIO_ENDPOINT: http://minio:9000
    ports: ["8000:8000"]

  frontend:
    build: { context: ../frontend, dockerfile: Dockerfile }
    image: magicjar/frontend:p4
    depends_on: [api-app]
    ports: ["3000:80"]

volumes:
  pg_data:
  minio_data:
```

### 1-B. `docker/docker-compose.infra.yml` — 인프라만

위 yml에서 4 인프라(postgres·kafka·redis·minio)만 추출. 개발자가 IDE에서 앱 실행 시 사용.

### 1-C. `docker/docker-compose.dev.yml` — 핫 리로드 override

- volume mount: `../api/build/libs`, `../pyworker/app`, `../frontend/src`
- entrypoint override (각 앱 dev 모드)

---

## 2. 신규 도메인 이벤트 (P3-A 발견사항)

`domain/src/main/kotlin/com/mj/domain/market/event/ScreenshotCapturedEvent.kt`:

```kotlin
package com.mj.domain.market.event

import java.time.Instant
import java.util.UUID

data class ScreenshotCapturedEvent(
    val captureId: UUID,
    val source: String,
    val url: String,
    val storageKey: String,
    val publicUrl: String,
    val capturedAt: Instant,
    val tradeId: UUID? = null,
    val symbol: String? = null,
    val timeframe: String? = null,
    val width: Int,
    val height: Int,
)
```

→ pyworker `ScreenshotCapturedEvent` Pydantic model과 1:1 호환. consumer 측에서 `JsonDeserializer` 매핑 가능.

---

## 3. scripts/smoke.sh — 5 통합 시나리오

bash 스크립트로 5 시나리오 검증 (spec §15-2):

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

API=http://localhost:8080
PYWORKER=http://localhost:8000
MINIO=http://localhost:9000

# === 시나리오 1: 백테스트 트리거 → 결과 → 매매일지 ===
echo "[1/5] 백테스트 → 매매일지"
RESP=$(curl -sf -X POST $API/api/backtest -H 'Content-Type: application/json' \
  -d '{"strategyId":"sma-cross","symbol":"005930","from":"2024-01-01","to":"2024-12-31"}')
RUN_ID=$(echo $RESP | jq -r .runId)
# ... (batch-app job poll, journal entry 확인)

# === 시나리오 2: 실시간 시세 → 신호 → STOMP fan-out ===
echo "[2/5] 시세 → 신호 → STOMP"
# Kafka에 직접 tick 1건 publish (kafka-console-producer 또는 별도 스크립트)
# STOMP 클라이언트로 /topic/market.tick.005930 구독해서 수신 확인

# === 시나리오 3: pyworker 뉴스 크롤 → consumer AI 분석 ===
echo "[3/5] 뉴스 크롤 → AI 분석"
curl -sf -X POST $PYWORKER/scrape/naver_news -d '{"limit":5}'
# Kafka topic news.analyzed.v1 까지 도달 확인

# === 시나리오 4: 차트 캡처 클라이언트 → api → MinIO ===
echo "[4/5] 차트 캡처 업로드"
# tiny PNG base64로 POST /api/journal/{tradeId}/snapshots
# MinIO bucket에 객체 존재 확인

# === 시나리오 5: 서버사이드 캡처 (pyworker Playwright) ===
echo "[5/5] 서버사이드 캡처"
curl -sf -X POST $PYWORKER/screenshot \
  -d '{"url":"https://example.com","viewport":{"width":1280,"height":720}}'
# 응답 publicUrl로 MinIO 객체 fetch + 200 확인

echo "✅ 5/5 시나리오 통과"
```

→ 시나리오별 보조 스크립트는 `scripts/smoke/` 아래.

---

## 4. P3 작업 보강

### 4-A. pyworker `uv.lock` 생성

`cd pyworker && uv sync` 호스트 환경에서 실행 → `uv.lock` 생성 → commit.

### 4-B. ADR 0017 author 키 정정

P3-A pyworker plan에서 `bot+ai-engineer@magicjar.local`로 잘못 표기됨. 실제 화이트리스트는 `bot+ai@magicjar.local`. P3 plan + spec 등에서 표기 정정. 단순 docs sed.

### 4-C. P2 deferred 게이트 (bootRun) 검증

P2에서 deferred됐던 `:api:bootRun`·`:batch:bootRun`·`:consumer:bootRun`을 docker compose 인프라 기동 후 검증. infra mode로 인프라만 띄우고 IDE/터미널에서 bootRun 시도. `/actuator/health` UP 확인.

---

## 5. .env.example 갱신

기존 `.env.example`에 추가:

```bash
# Kafka
KAFKA_BOOTSTRAP=localhost:9092

# MinIO
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=magicjar
MINIO_SECRET_KEY=magicjar123

# pyworker
PYWORKER_URL=http://localhost:8000

# 신규 (사용자 통보 항목)
OPENDART_API_KEY=          # DART 공시 (pyworker)
NAVER_NEWS_RSS_URL=        # 네이버 뉴스 RSS (pyworker)
```

→ 기존 `.env.example`의 KIS·OpenAI 키는 유지.

---

## 6. README.md + CLAUDE.md 갱신

- README "빌드 · 실행 방법" 섹션 — IntelliJ 부분 제거하고 docker compose 3 모드 + scripts/smoke.sh 사용법 추가
- CLAUDE.md 변경 이력 1줄 (Phase D-P4 진입)

---

## 7. QA 풀 게이트 (ADR 0016 트리거 5/7)

- 도메인 모델 깨는 변경 ✓ (com.mj 전환)
- 새 UI 흐름 ✓ (React 전환)
- 회귀 영역 ✓ (Phase B 코드 대거 이전)
- Phase 통합 ✓ (Phase D 종료)
- 명시 요청 ✓

→ qa 에이전트 1회 호출. boundary-qa 스킬로 API↔DB↔UI↔AI 출력 정합 검증. 결과 PR 본문에 첨부.

---

## 8. 종료 게이트

- [ ] `docker compose up -d` 5 앱 + 4 인프라 모두 healthy
- [ ] `./scripts/smoke.sh` 5 시나리오 모두 PASS
- [ ] `:api:bootRun` `:batch:bootRun` `:consumer:bootRun` 모두 `/actuator/health` UP
- [ ] frontend 컨테이너 nginx 정상 (curl localhost:3000 → React index.html)
- [ ] pyworker 컨테이너 `/health` 200
- [ ] QA 1회 통과 (APPROVED 또는 issue 명시)
- [ ] `_workspace/_harness-state.json` Phase D 종료 기록 + 종료 시각 + 다음 Phase 안내
- [ ] PR `agent/architect/p4-docker-smoke` → main 머지

---

## 9. 통보 경계 (자율 vs 사용자)

- compose 통합에서 발견되는 spec/plan 모순은 architect 자율 hotfix
- QA 결과가 P1~P4 범위 밖 critical issue를 발견하면 사용자 통보
- 신규 ADR 발행이 필요하면 사용자 통보

---

## 10. 후속 단계 (Phase E+)

- 실제 KIS 모의 키 연동 + 1종목 1전략 전체 파이프라인 검증
- CI/CD 파이프라인 (GitHub Actions matrix build·test·push to ghcr.io)
- k8s/ECS 배포 manifest
- 실시간 STOMP 부하 테스트
- ADR 0027 (필요 시 Advanced Charts 도입)
