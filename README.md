# magicJar (마법의항아리)

> **부자될꼬야** — 돈이 알아서 차는 마법의 항아리, 개인 맞춤형 한국 주식 자동매매 시스템

Spring Boot 3.x + Spring AI + 한국투자증권 KIS OpenAPI 기반. **백테스트 → 모의투자 → 실전** 순서로 단계적 개발을 목표로 한다.

## 상태

**Phase D 완료** (MSA 재구성 4단계 — P1 Foundation · P2 JVM Apps · P3 pyworker+frontend · P4 Docker compose + 통합 스모크).
spec `docs/superpowers/specs/2026-04-27-msa-restructure-design.md` + plan `docs/superpowers/plans/2026-04-27-msa-restructure.md` + ADR 0019~0026 발행 완료.
**다음(Phase E)**: KIS 모의 키 연동 + 1종목 1전략 전체 파이프라인 검증, CI/CD(GitHub Actions), k8s/ECS 배포 manifest.

## 모듈 구조

```
magic_ch1/
├─ domain/         Kotlin java-library (순수 도메인 + port, Spring 미부착)
│  └─ com.mj.domain/{common, market, strategy, ai, order, journal}
├─ api/            Spring Boot — REST + @Scheduled + STOMP
├─ batch/          Spring Boot + Spring Batch
├─ consumer/       Spring Boot + Spring Kafka (KIS WS 브리지·전략·AI 분석·매매일지)
├─ pyworker/       Python 3.12 + FastAPI + Playwright + APScheduler
├─ frontend/       React + TS + Vite + TradingView Lightweight Charts v4
├─ docker/         docker-compose 3 모드 (전체·infra·dev override)
└─ scripts/        smoke.sh (5 시나리오 통합 검증) · task.mjs · render-status.mjs
```

## 핵심 스택

- **JVM**: Kotlin 2.0 (K2) + JDK 21 + Spring Boot 3.3 + Spring AI 1.0.0-M3 + Spring Kafka + Spring Batch + Gradle Kotlin DSL
- **Python**: 3.12 + FastAPI + Playwright + aiokafka + APScheduler
- **Frontend**: React 19 + TypeScript + Vite + shadcn/ui + TanStack Query + Zustand + TradingView Lightweight Charts v4 (Apache 2.0, 공식 API만 — 커스텀 지양)
- **Infra**: PostgreSQL 16, Kafka 3.7 (KRaft), Redis 7, MinIO, KIS OpenAPI (REST + WebSocket)
- **Docker**: 멀티 스테이지 + 비루트, MSA 기본 + compose 로컬 통합

## AI 기능 (예정)

1. 매매 전략 생성·자연어 설명
2. 뉴스·공시 감성 분석 (DART + 네이버 금융)
3. 포트폴리오 리밸런싱 조언
4. 자연어 → 차트 조건식 변환 + 종목 발굴

## 꼭 챙기는 기능

- **매매일지(Trading Journal)** — 모든 매매 자동 기록 + AI 복기 + 사용자 메모
- **하네스 운영 페이지(`/harness`)** — 지금 어떤 에이전트가 무엇을 하고 있는지 웹에서 직접 조회

## 빌드 · 실행 방법 (Docker compose 3 모드 + 통합 스모크)

### 0. 사전 준비 (1회만)

| 항목 | 비고 |
|------|------|
| **Docker Desktop** | 4 인프라(postgres·kafka·redis·minio) + 5 앱 컨테이너용. 실행 중이어야 함. |
| **JDK 21 (Temurin)** | infra 모드에서 호스트의 `./gradlew bootRun` 사용 시 필요. Gradle Toolchain 자동 다운로드 가능. |
| **Node.js 20 + pnpm** | frontend 호스트 dev 서버 사용 시. `corepack enable && corepack prepare pnpm@9 --activate` |
| **uv** | pyworker 호스트 dev 시. `pip install uv` 또는 brew. |
| `npm install` | 저장소 클론 직후 1회 — Husky 훅 자동 설치 (ADR 0010·0017) |

### 1. 환경변수 준비

```bash
cp .env.example .env
```

`.env` 편집 — 최소 셋업이면 키 다 비워둬도 OK (해당 기능만 자동 비활성):

```bash
SPRING_PROFILES_ACTIVE=mock          # 또는 backtest
MAGICJAR_MODE=MOCK                   # 또는 BACKTEST · REAL
MAGICJAR_ALLOW_REAL=false            # 실계좌 가드, 평소 false 유지

KIS_MOCK_APP_KEY=...                 # 비우면 KIS 호출 비활성
KIS_MOCK_APP_SECRET=...
OPENAI_API_KEY=sk-...                # 비우면 AI 분석 자동 비활성 (consumer-app)
ANTHROPIC_API_KEY=sk-ant-...         # 옵션
OPENDART_API_KEY=...                 # 비우면 DART 공시 수집 비활성 (pyworker)
NAVER_NEWS_RSS_URL=...               # 네이버 금융 뉴스 RSS (pyworker)
```

> WARNING: `MAGICJAR_MODE=REAL` + `MAGICJAR_ALLOW_REAL=true` **둘 다 true**일 때만 실계좌 모드 (ADR 0006 이중 스위치).

### 2. Docker compose — 3 모드

| 모드 | 명령 | 용도 |
|------|------|------|
| **전체 스택** | `docker compose -f docker/docker-compose.yml up -d --build` | 인프라 4 + 앱 5 통합 기동. 머지 전 검증·CI |
| **인프라만** | `docker compose -f docker/docker-compose.infra.yml up -d` | postgres·kafka·redis·minio만. 호스트 `./gradlew bootRun`으로 디버깅 |
| **dev override** | `docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up -d` | 위 1 + volume mount + uvicorn `--reload` |

상태 확인:

```bash
docker compose -f docker/docker-compose.yml ps        # 9개 모두 (healthy) 여야 정상
docker compose -f docker/docker-compose.yml logs -f api-app
docker compose -f docker/docker-compose.yml down      # 끄기
docker compose -f docker/docker-compose.yml down -v   # 데이터까지 삭제 (주의)
```

| 서비스 | 호스트 포트 | 비고 |
|--------|-----------|------|
| PostgreSQL | `5432` | DB/user/pwd 모두 `magicjar` |
| Kafka | `9092` | KRaft 단일 노드, auto-create-topics |
| Redis | `6379` | |
| MinIO | `9000` (S3) / `9001` (콘솔) | user `magicjar` / pwd `magicjar123` |
| api-app | `8080` | REST + STOMP. `/actuator/health` |
| batch-app | `8081` | Spring Batch (job.enabled=false, 명시 트리거) |
| consumer-app | `8082` | Spring Kafka 컨슈머 + AI 분석 + KIS 브리지 |
| pyworker | `8000` | FastAPI + Playwright. `/health` `/scrape/{source}` `/screenshot` |
| frontend | `3000` | nginx 정적 + React SPA (포트 80 → 3000 매핑) |

### 3. 통합 스모크 — `scripts/smoke.sh`

5 시나리오를 한 번에 검증 (spec §15-2):

```bash
./scripts/smoke.sh
```

| # | 시나리오 | 의존 |
|---|---------|------|
| 1 | 백테스트 트리거 → 매매일지 | api-app `/api/backtest` (Phase E 정식 구현 예정 — 현재 SKIP) |
| 2 | 시세 → 신호 → STOMP fan-out | kafka 컨테이너 + api-app health |
| 3 | 뉴스 크롤(pyworker) → AI 분석(consumer) | pyworker `/scrape/naver` + consumer `news.analyzed.v1` |
| 4 | 차트 캡처 클라이언트 → MinIO | api-app `/api/journal/{tradeId}/snapshots` (Phase E — SKIP) |
| 5 | 서버사이드 캡처(pyworker Playwright) | pyworker `/screenshot` + MinIO public URL |

종료 코드 `0`=PASS/SKIP만, `1`=FAIL 1건 이상.
환경변수 override: `API_URL=http://localhost:8080 PYWORKER_URL=http://localhost:8000 ./scripts/smoke.sh`

### 4. 호스트 직접 실행 (infra 모드 + bootRun)

호스트에서 앱을 직접 띄우고 싶을 때 (디버거 attach 등):

```bash
# 1) 인프라만 띄우기 — kafka가 localhost:9092로 광고됨 (compose-infra 전용)
docker compose -f docker/docker-compose.infra.yml up -d

# 2) 환경변수 export
export $(grep -v '^#' .env | xargs)

# 3) 각 앱 bootRun (각 터미널에서)
./gradlew :api:bootRun
./gradlew :batch:bootRun
./gradlew :consumer:bootRun

# 4) frontend dev 서버 (호스트)
cd frontend && pnpm install && pnpm dev      # http://localhost:5173

# 5) pyworker dev (호스트)
cd pyworker && uv sync && uv run uvicorn app.main:app --reload --port 8000
```

각 앱의 `/actuator/health` (JVM) 또는 `/health` (pyworker)가 200 UP이면 정상.

### 5. 빌드만

```bash
./gradlew clean build                 # 4 Gradle 모듈 (domain·api·batch·consumer)
./gradlew :api:bootJar                # 실행 가능한 jar
docker compose -f docker/docker-compose.yml build   # 5 이미지 빌드
```

### 프로파일 / 모드

| 프로파일 | `magicjar.mode` | 용도 |
|---------|-----------------|------|
| `mock` (기본) | `MOCK` | KIS 모의계좌 경로 |
| `backtest` | `BACKTEST` | 가상 체결, 과거 데이터 기반 |
| `real` | `REAL` + `allow-real=true` | 실계좌 (ADR 0006 이중 스위치) |

### 트러블슈팅

| 증상 | 원인 / 조치 |
|------|------------|
| `docker compose ps`에 `unhealthy` | `docker compose logs <service>`. kafka는 start_period 20s 필요. |
| `Connection refused 5432/9092/9000` | infra 미기동 또는 healthcheck 미통과. `docker compose ps`로 health 확인. |
| Kafka 호스트 vs 컨테이너 광고 차이 | infra 모드 yml은 `localhost:9092`로 광고, 전체 모드 yml은 `kafka:9092`로 광고. 두 yml은 의도적으로 분리. |
| 포트 충돌 (8080·8081·8082·8000·9092·5432·6379·9000·9001·3000) | `lsof -iTCP -sTCP:LISTEN -P`로 점유 확인 → kill, 또는 yml의 host 포트만 변경. |
| pyworker 빌드 시 `uv sync --frozen` 실패 | `uv.lock` 부재. `docker compose run --user root --no-deps --entrypoint sh -v $(pwd)/pyworker:/host pyworker -c "cp /host/pyproject.toml /tmp/ && cp /host/README.md /tmp/ && cp -r /host/app /tmp/ && cd /tmp && pip install -q uv && uv lock && cp uv.lock /host/uv.lock"` |
| Husky 훅 차단 | 시크릿·ktlint·author 화이트리스트 위반. **`--no-verify`는 금지** (ADR 0010·0017). |

## 개발 워크플로우 요약 (v2 — ADR 0013~0018)

- **단일 메인 worktree** + 브랜치 전환 순차 작업 (ADR 0013)
- **머지 1단**: `agent/<agent>/<task> → main` 직행 (ADR 0015 — qa 영속 브랜치 폐기)
- **모델 차등** (ADR 0014): architect / ai-engineer / strategy-engine = **opus**, 나머지 4명 = **sonnet**. 부족 시 1회 opus 에스컬레이션
- **Git author 분리** (ADR 0017): 에이전트별 `magicjar-<agent>` author로 commit → `git log`에서 누가 했는지 즉시 식별
- **Task 파일 시스템** (ADR 0018): 작업 시작 시 `_workspace/tasks/<id>.md` 생성. `node scripts/task.mjs <start|check|touch|block|done|archive|list>` CLI
- **QA On-Demand** (ADR 0016): 7개 트리거(돈 흐름·도메인 변경·새 UI 흐름·보안·회귀·Phase 통합·명시 판단) 시에만. 평소엔 셀프 + 자동 검사
- 커밋: Conventional Commits. 훅: Husky(`pre-commit` 시크릿·ktlint, `pre-push` main 차단·`./gradlew check`)
- 권한: `.claude/settings.json`이 일상 자동 허용, `gh pr merge`·main push·`npm publish`만 사용자 승인
- 상세: `_workspace/arch/branching-and-qa-gates.md`, ADR 0009·0013·0015·0016·0018

## Claude Code 하네스

본 저장소는 Claude Code 하네스로 자동화된다. `CLAUDE.md` 참고.
