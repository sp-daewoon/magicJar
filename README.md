# magicJar (마법의항아리)

> **부자될꼬야** — 돈이 알아서 차는 마법의 항아리, 개인 맞춤형 한국 주식 자동매매 시스템

Spring Boot 3.x + Spring AI + 한국투자증권 KIS OpenAPI 기반. **백테스트 → 모의투자 → 실전** 순서로 단계적 개발을 목표로 한다.

## 상태

**Phase D 완료** (MSA 토대) + **Phase E-P1·E-P2·E-P3 완료** (REST 5종 + KIS stub/real 분기 + e2e backtest + GitHub Actions CI/CD).
spec `docs/superpowers/specs/2026-04-27-msa-restructure-design.md` + plans `docs/superpowers/plans/` + ADR 0019~0027 발행 완료.

## 베이스라인 (ADR 0027·0028 + v2 마스터)

| 베이스라인 | 태그 | 보존 브랜치 | 시점 | 단계 |
|-----------|------|------------|------|------|
| v0 (껍데기) | `v0.1.0` | `release/v0` (immutable) | 2026-04-30 | MSA + CI/CD 골격 |
| v1 (데모 가시성) | `v1.0.1` | `release/v1` (immutable) | 2026-05-01 | 트레이딩뷰·시세·차트·AI 카드 mock 흐름 |
| v1.1 | (태그) | `release/v1.1.0` (immutable) | 2026-05-01 | 1번 트랙 KIS 주문 어댑터 직전 |
| v1.2_notwork | (태그) | `release/v1.2.0_notwork` (immutable) | 2026-05-02 | 1번 트랙 dogfooding 중단 시점 |
| **v2 (베이스라인 확정 — 룰 본체 + AI 옵션 + MCP 미래)** | (W3 종료 후 컷 예정) | (`release/v2.0.0` 후보) | 2026-05-03 | v2 마스터 아키텍처 + ADR 0031~0047 17건 발행 |

- 살 붙이기·버그 수정은 **main 트렁크에서** 진행 (ADR 0015 단일 머지 흐름 유지)
- 기존 `release/v*` update·delete 금지 — pre-push hook 차단 (ADR 0028로 신규 ref 생성은 허용)
- v2 마스터는 `docs/architecture/master-architecture.md` 단일 진실원 (ADR 0047)
- 다음 안정판 컷: W1·W2·W3 종료 후 `v2.0.0` + `release/v2.0` 신설 예정
- 시점 코드 체크아웃: `git checkout v1.0.1` 또는 `git checkout release/v1`

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

`.env` 편집 — 최소 셋업이면 키 다 비워둬도 OK. 사용자 화면 ⚙️ SettingsModal에서 입력 가능 (memory `project_kis_key_management.md`):

```bash
SPRING_PROFILES_ACTIVE=dev,real-kis  # default. 데이터 조회=실전 키 (memory feedback_no_mock_data.md)

KIS_APP_KEY=...                      # 실전 KIS — 데이터 조회 (시세·캔들·종목)
KIS_APP_SECRET=...
KIS_HTS_ID=...                       # 8자리 HTS ID (체결통보)
KIS_ACCOUNT_CANO=...                 # 계좌번호 8자리
KIS_ACCOUNT_PRDT=01

KIS_MOCK_APP_KEY=...                 # (선택) 모의투자 — 주문 전용
KIS_MOCK_APP_SECRET=...

MAGICJAR_SECRET_MASTER_KEY=...       # 32 bytes hex — KIS 키 AES 암호화 master key
ANTHROPIC_API_KEY=sk-ant-...         # (선택) AI 분석
OPENDART_API_KEY=...                 # (선택) DART 공시
NAVER_CLIENT_ID=...                  # (선택) 네이버 뉴스
NAVER_CLIENT_SECRET=...
```

> **mock 부팅 path 폐기 (2026-05-04 PR #144·#147)** — `mock` profile은 jvm test fixture 전용. 운영/사용자 흐름은 `dev,real-kis`만 사용. `--mock` 옵션 + `MAGICJAR_MARKET_MOCKTICK_ENABLED` 환경변수 폐기.

### 2. Docker compose — 3 모드

> ⚠️ **중요 운영 함정 (2026-05-01 추가)**
>
> `api`/`batch`/`consumer` Dockerfile은 호스트의 `<module>/build/libs/*.jar`를 그대로 복사한다. **즉 `docker compose build`만으로는 새 코드가 컨테이너에 안 들어간다.** `./gradlew bootJar`를 먼저 돌려야 함.
>
> 이걸 한 줄로 자동화한 헬퍼:
> ```bash
> ./scripts/build-and-deploy.sh                # 전체 (gradle + docker build + up)
> ./scripts/build-and-deploy.sh api-app frontend  # 일부만
> ```
> `scripts/smoke.sh`는 시작 시 jar staleness를 자동 검사하고 stale 감지 시 abort한다.

| 모드 | 명령 | 용도 |
|------|------|------|
| **전체 스택 (권장)** | `./scripts/build-and-deploy.sh` | gradle bootJar + docker build + up -d 한 번에 |
| **수동 전체** | `./gradlew :api:bootJar :batch:bootJar :consumer:bootJar`<br>`docker compose -f docker/docker-compose.yml up -d --build` | 명시적으로 단계 분리 |
| **인프라만** | `docker compose -f docker/docker-compose.infra.yml up -d` | postgres·kafka·redis·minio만. 호스트 `./gradlew bootRun`으로 디버깅 |
| **dev override** | `docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up -d` | 위 1 + volume mount + uvicorn `--reload` |

상태 확인:

```bash
docker compose -f docker/docker-compose.yml ps        # 11개 모두 (healthy) 여야 정상
docker compose -f docker/docker-compose.yml logs -f api-app
docker compose -f docker/docker-compose.yml down      # 끄기
docker compose -f docker/docker-compose.yml down -v   # 데이터까지 삭제 (주의)
```

### 디버깅 전용 web UI 4종 (한 페이지에서 다 가능)

| URL | 용도 |
|---|---|
| **http://localhost:8888** | **dozzle** — 모든 컨테이너 로그 실시간 (색상·검색·multi-pane). IntelliJ Console 대체 |
| http://localhost:8090 | kafka-ui — 토픽·메시지·consumer lag |
| http://localhost:9001 | MinIO 콘솔 (`magicjar`/`magicjar123`) |
| http://localhost:18080/swagger-ui/index.html | api Swagger |
| http://localhost:8000/docs | pyworker Swagger |

### 서비스 포트

| 서비스 | 호스트 포트 | 비고 |
|--------|-----------|------|
| PostgreSQL | `5432` | DB/user/pwd 모두 `magicjar` |
| Kafka | `9092` | KRaft 단일 노드, auto-create-topics |
| Redis | `6379` | |
| MinIO | `9000` (S3) / `9001` (콘솔) | user `magicjar` / pwd `magicjar123` |
| api-app | `18080` (호스트) → `8080` (컨테이너) | REST + STOMP. `/actuator/health` |
| batch-app | `8081` | Spring Batch (job.enabled=false, 명시 트리거) |
| consumer-app | `8082` | Spring Kafka 컨슈머 + AI 분석 + KIS 브리지 |
| pyworker | `8000` | FastAPI + Playwright. `/health` `/scrape/{source}` `/screenshot` |
| frontend | `3000` | nginx 정적 + React SPA (포트 80 → 3000 매핑) |
| dozzle | `8888` | **컨테이너 로그 web viewer** (PR #150) |
| kafka-ui | `8090` | provectuslabs/kafka-ui — 토픽·메시지·consumer lag GUI |

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

### 4-1. IntelliJ에서 실행 (`.run/` 공유 Run Config)

`.run/` 디렉토리에 IntelliJ가 자동 인식하는 Run Configuration 7종이 commit돼있다 — 누구나 같은 셋업으로 띄울 수 있다.

| Run Config | 종류 | 설명 |
|---|---|---|
| **Infra-Up** | Shell | `docker-compose.infra.yml up -d` + healthy 폴링 (수동, JVM 앞에 1회) |
| **MagicJarApi** | Spring Boot | api-app 8080 |
| **MagicJarBatch** | Spring Boot | batch-app 8081 (`spring.batch.job.enabled=false` — 자동 잡 실행 없음) |
| **MagicJarConsumer** | Spring Boot | consumer-app 8082 |
| **Pyworker** | Python (uvicorn) | pyworker 8000 — env 5종 주입(KAFKA·S3 등). `.venv` 인터프리터 필요 |
| **Job-Backfill** | Spring Boot | batch jar로 `dailyCandleBackfillJob`만 단발 실행 (web 서버 미부팅) |
| **Job-Backtest** | Spring Boot | batch jar로 `backtestRunJob`만 단발 실행 |

#### 셋업 절차 (1회)

```bash
# 1) pyworker 호스트 환경 준비
brew install uv                              # 또는 https://astral.sh/uv/install.sh
cd pyworker && bash scripts/setup.sh         # uv sync + playwright chromium 설치
cd ..

# 2) frontend 호스트 환경 준비 (터미널 실행용)
cd frontend && npm install
cd ..
```

#### IntelliJ 측 1회 설정

1. **Project Structure → SDK** → JDK 21 선택
2. **Settings → Plugins** → "Python" 설치 (Community/Ultimate 둘 다 무료)
3. **Settings → Project → Python Interpreter** → "Add Local Interpreter" → Existing → `<project>/pyworker/.venv/bin/python`
4. Gradle 모듈 reload (`./gradlew --refresh-dependencies` 또는 IntelliJ 우상단 코끼리 아이콘)

#### 평소 실행 흐름

```
1. Infra-Up         ▶  (인프라 4개 healthy까지 기다림)
2. MagicJarApi      ▶  (Flyway가 api에서 돌아 스키마 적용)
3. MagicJarBatch    ▶  / MagicJarConsumer ▶
4. Pyworker         ▶
5. (터미널)         cd frontend && npm run dev    # http://localhost:5173
```

#### 잡 단발 실행

`Job-Backfill` 또는 `Job-Backtest`를 ▶ 누르면 batch jar가 잡만 실행하고 종료한다. 파라미터(symbol/from/to/runId 등)는 Run Config의 Program Arguments에서 직접 수정. web 서버는 안 띄우므로 **MagicJarBatch가 떠있어도 8081 포트 충돌 없음**.

#### 주의

- **frontend는 터미널 권장** (`npm run dev`) — vite HMR 출력이 IntelliJ Run Window보다 터미널에서 보기 편함
- pyworker를 IntelliJ에서 띄우는 동안 docker compose에서 pyworker도 띄우지 말 것 (8000 포트 충돌). `docker-compose.infra.yml`에는 pyworker가 없으니 문제 없음
- Active profile 옵션은 비워뒀음(application.yml의 default `dev` / `stub-kis` 사용). local/dev/prd 분리는 향후 별도 작업

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

## CI/CD (Phase E-P3)

GitHub Actions로 PR 검증 + main 머지 시 컨테이너 자동 발행을 수행한다.

| 워크플로 | 트리거 | 역할 |
|---------|--------|------|
| `.github/workflows/ci.yml` | PR(→main) + main push | JVM 4 모듈 `:check` + `:bootJar` (matrix), pyworker `pytest`, frontend `pnpm test/build`. main push 시 5 서비스(api·batch·consumer·pyworker·frontend) 컨테이너를 ghcr.io에 push. |
| `.github/workflows/release.yml` | `v*.*.*` 태그 push | semver 태그 컨테이너 push + GitHub Release draft 생성 (skeleton, Phase E-P4 이후 정식 사용). |

**이미지 식별자 규칙** — `ghcr.io/<owner>/magicjar-<service>:<tag>`

| tag | 의미 |
|-----|------|
| `sha-<short>` | 모든 main push에서 자동 생성. 정확한 commit pin용. |
| `latest` | main의 최신 이미지. dev/staging compose에서 기본 사용. |
| `vX.Y.Z` / `vX.Y` | release 워크플로 (semver 태그 전용). |

**Pull 예시:**

```bash
docker pull ghcr.io/sp-daewoon/magicjar-api:latest
docker pull ghcr.io/sp-daewoon/magicjar-frontend:sha-abc1234
```

> ghcr.io visibility는 기본 private. public 공개를 원하면 GitHub UI → Packages → Package settings → Change visibility에서 변경. private 유지 시 pull 전에 `echo $GITHUB_TOKEN | docker login ghcr.io -u <user> --password-stdin` 필요.

**1회 수동 설정 (저장소 owner):**

1. Settings → Actions → General → Workflow permissions → "Read and write permissions" 활성화
2. Settings → Actions → General → "Allow GitHub Actions to create and approve pull requests" (선택)
3. (선택) ghcr.io 패키지 visibility를 public으로 변경하면 외부에서 인증 없이 pull 가능

**CODEOWNERS** — `.github/CODEOWNERS`로 1인 owner 등록 (향후 팀 확장 대비).

## Claude Code 하네스

본 저장소는 Claude Code 하네스로 자동화된다. `CLAUDE.md` 참고.
