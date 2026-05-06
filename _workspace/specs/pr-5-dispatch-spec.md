# PR-5 sub-agent 분배 명세 + 충돌 행렬

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 정합 SoT: `_workspace/specs/pr-5-w1-bootstrap-design.md` v1
- 본 문서: orchestrator가 backend-core·frontend·architect 디스패치 시 사용하는 작업 명세 + 직렬/병렬 결정 근거.
- ADR 0036 §2-3 hierarchy 정합 — architect는 설계만, 코드는 sub-agent.

---

## 1. PR 시리즈 개요

| PR | 담당 | 분량 | 브랜치 | 우선순위 |
|---|---|---|---|---|
| **PR-5a** | backend-core | M~L | `agent/backend-core/pr-5a-jvm-modules` | 1순위 (단독) |
| **PR-5b** | frontend | M | `agent/frontend/pr-5b-vite-spa` | 2순위 (PR-5a 머지 후) |
| **PR-5c** | architect | S | `agent/architect/pr-5c-ci-workflow` | 3순위 (선택 — CI 워크플로 정상화 필요 시) |

**디스패치 모드**: 직렬 (병렬 불가 — §3 충돌 행렬 정합).

---

## 2. backend-core 위임 (PR-5a)

### 2-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | backend-core (sonnet) |
| 브랜치 | `agent/backend-core/pr-5a-jvm-modules` |
| base | `main` |
| 작업 author | `bot+backend-core@magicjar.local` (pre-commit 강제) |
| 작업 author name | `magicjar-backend-core` |
| Task 파일 | `node scripts/task.mjs start --agent backend-core --branch agent/backend-core/pr-5a-jvm-modules ...` |
| 머지 정책 | rebase (CLAUDE.md 정합) |
| 분량 | M~L (4 모듈 × Application + build.gradle + Dockerfile + application.yml + V1 SQL) |

### 2-2. 작업 범위 (Done 정의)

다음 모두 완료해야 PR-5a Done:

#### A. domain 모듈 골격
- [ ] `domain/build.gradle.kts` — `java-library` plugin + `bootJar` 비활성 + Spring 의존 0
- [ ] `domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/` 패키지 13종 디렉토리 (위 §2 트리)
  - `common/`·`instrument/`·`market/`·`watchlist/`·`order/`·`position/`·`risk/`·`user/`·`secret/`·`notification/`·`audit/`·`indicator/`(빈)·`signal/`(빈)
- [ ] 핵심 도메인 클래스 + port 인터페이스 stub (`UserAccount`·`SecretCipher`·`Instrument`·`Watchlist`·`Candle`·`Position`·`Order`·`KillSwitchState` + 각 port — 총 ~20개 파일)
- [ ] `grep -r "import org.springframework" domain/src/main` → 0건 의무

#### B. api 모듈 골격
- [ ] `api/build.gradle.kts` — Spring Boot 3.3 + spring-web + websocket + data-jpa + data-redis + kafka + flyway + springdoc + actuator + project(":domain")
- [ ] `api/src/main/kotlin/kr/spdaewoon/magicjar/api/ApiApplication.kt` — `@SpringBootApplication(scanBasePackages = ["kr.spdaewoon.magicjar"])` main
- [ ] `api/src/main/resources/application.yml` (default profile dev,real-kis + Flyway enabled + actuator health)
- [ ] `api/src/main/resources/application-dev.yml` · `application-real-kis.yml` (빈 + 주석 — PR-6+ 활성화)
- [ ] `api/Dockerfile` — eclipse-temurin:21-jre-alpine + 비루트 + healthcheck + COPY build/libs/*-SNAPSHOT.jar
- [ ] 빈 디렉토리: `controller/`·`adapter/{kis,yahoo,dart,naver}/`·`kafka/`·`stomp/`·`persistence/`·`config/` (각 디렉토리 `.gitkeep` 또는 placeholder kt 파일)
- [ ] `api/src/main/resources/db/migration/V1__baseline.sql` — 11 테이블 (위 design §5 정합)
- [ ] `:api:bootJar` GREEN

#### C. batch 모듈 골격
- [ ] `batch/build.gradle.kts` — Spring Boot Batch + data-jpa + redis + kafka + project(":domain") (Flyway 미포함)
- [ ] `batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/BatchApplication.kt`
- [ ] `batch/src/main/resources/application.yml` (port 8081 + flyway.enabled=false + spring.batch.job.enabled=false)
- [ ] `batch/Dockerfile`
- [ ] 빈 `job/` 디렉토리
- [ ] `:batch:bootJar` GREEN

#### D. consumer 모듈 골격
- [ ] `consumer/build.gradle.kts` — Spring Boot + data-jpa + redis + kafka + webflux + project(":domain")
- [ ] `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ConsumerApplication.kt`
- [ ] `consumer/src/main/resources/application.yml` (port 8082 + flyway.enabled=false)
- [ ] `consumer/Dockerfile`
- [ ] 빈 `kafka/`·`ws/`·`ingestion/`·`signal/`·`order/`·`notification/`
- [ ] `:consumer:bootJar` GREEN

#### E. settings.gradle.kts · build.gradle.kts 정합
- [ ] 현행 `settings.gradle.kts` 4 모듈 include 검증 — 추가 변경 권장 0건 (이미 정합)
- [ ] 루트 `build.gradle.kts` subprojects 블록 검증 — Spring Boot 3.3 / Kotlin 2.0.21 / JDK 21 정합
- [ ] `bootJar` 산출물 이름 명시 — `archiveClassifier.set("")` 명시 (Dockerfile glob 정합)

#### F. docker-compose.yml 정합 검증 (변경 최소)
- [ ] `docker compose -f docker/docker-compose.yml config` 통과 (구문 정합)
- [ ] `docker compose build api-app batch-app consumer-app` 통과 (jar 산출 후)
- [ ] **frontend 서비스 정의는 손대지 말 것** (PR-5b에서 frontend가 검증)

#### G. 검증 게이트 통과
설계 가이드 §9-1 모든 항목 GREEN.

### 2-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(domain): 도메인 모듈 골격 + port 인터페이스 stub` | `domain/build.gradle.kts` + `domain/src/main/kotlin/...` 모든 파일 |
| 2 | `feat(api): api 모듈 골격 + Application + application.yml + Dockerfile` | `api/build.gradle.kts` + `api/src/main/kotlin/.../ApiApplication.kt` + `api/src/main/resources/application*.yml` + `api/Dockerfile` |
| 3 | `feat(batch): batch 모듈 골격 + Application + Dockerfile` | `batch/...` 동일 패턴 |
| 4 | `feat(consumer): consumer 모듈 골격 + Application + Dockerfile` | `consumer/...` 동일 패턴 |
| 5 | `feat(db): Flyway V1 baseline — 11 테이블 (system_config·user_account·user_kis_credential·instrument·watchlist·candle·position·orders·kill_switch_state·notification_outbox·audit_trail)` | `api/src/main/resources/db/migration/V1__baseline.sql` |
| 6 | `chore(gradle): bootJar 산출물 이름 + Dockerfile glob 정합` | 루트 `build.gradle.kts` + 모듈별 build.gradle.kts |

(commit 1·2·3·4 묶기 OK — 그러나 git rebase 시 conflict 발생 시 분할 권장.)

### 2-4. PR 본문 템플릿

```markdown
## Summary
- PR-5a: W1 모듈 부트스트랩 — 4 JVM 모듈(domain/api/batch/consumer) 골격 + Flyway V1 baseline 11 테이블 + Dockerfile 4종.
- 비즈니스 로직 0건 (Application stub + 빈 디렉토리). PR-6+ 채움.
- 정합: `_workspace/specs/pr-5-w1-bootstrap-design.md` + ADR 0001~0047.

## Scope
- domain: 13 패키지 + 도메인 클래스 ~20개 + port 인터페이스 ~15개 (Spring 의존 0)
- api: ApiApplication + application.yml + Dockerfile + V1__baseline.sql (11 테이블)
- batch: BatchApplication + Dockerfile (Flyway 미실행)
- consumer: ConsumerApplication + Dockerfile (Flyway 미실행)
- 변경 외: docker-compose.yml(검증만) · frontend(미손댐 — PR-5b)

## Test plan
- [x] `./gradlew build` GREEN (4 모듈)
- [x] `./gradlew :api:bootJar :batch:bootJar :consumer:bootJar` GREEN
- [x] `./gradlew :domain:bootJar` 비활성 명시
- [x] `./gradlew ktlintCheck` 0 violation
- [x] `grep -r "import org.springframework" domain/src/main` 0건
- [x] `grep -rE "spring-ai|anthropic|openai" {domain,api,batch,consumer}/src/main` 0건 (W4 한정)
- [x] `scripts/build-and-deploy.sh --gradle-only` 통과
- [x] `docker compose -f docker/docker-compose.infra.yml up -d` 인프라 3 healthy
- [x] `docker compose -f docker/docker-compose.yml up -d api-app` Flyway V1 적용 + 11 테이블 생성 확인 (`psql -c "\dt" | grep -c -E "..."` = 11)
- [x] `psql -c "SELECT id FROM system_config"` → 1 row, id=1

## 정합 ADR
- 0002·0004·0005·0006·0008 — 모듈 구조·Docker·DB
- 0011·0015·0017·0019·0024·0028·0040·0042·0043·0044·0046 — 11 테이블 정합
- 0030 — master-architecture SoT 정합
- 0036 — author whitelist · task 파일 의무
```

### 2-5. backend-core 의존성·정합 의무

- ADR 0046 (Onboarding) 테이블 3종 — system_config singleton + user_account + user_kis_credential. **AES 암호화 컬럼은 BYTEA만 정의, 실제 cipher 구현은 PR-6**.
- ADR 0042 (Position Bootstrap) — position 테이블만 정의, ApplicationRunner는 PR-7+.
- ADR 0019 (Candle) — `interval` CHECK constraint 11종 정합 ('1m'·'2m'·'5m'·'15m'·'30m'·'90m'·'1h'·'1d'·'1wk'·'1mo'·'1y').
- ADR 0011 (Order Executor) — orders 테이블 status enum 5종 정합.
- ADR 0015 (REJECTED Audit) — orders.reject_reason·reject_detail·reject_source 컬럼 의무. 별도 audit 테이블은 audit_trail로 통합 (V1 단순화).
- ADR 0024·0043 (KillSwitch) — kill_switch_state 테이블 (default false).
- ADR 0044 (Notification) — notification_outbox 테이블.
- ADR 0017 (Instrument) — instrument 테이블 + market/type CHECK.
- ADR 0028·0040 (Watchlist) — watchlist 테이블 (user_id, code) UNIQUE.

**KIS endpoint 직접 호출 X** — KIS 모듈 패키지 골격(`api/adapter/kis/`)만 만들고 비어 둠. 실 호출은 PR-6 (T-ONBOARD).

**KIS-first 의무**: PR-5a는 KIS 호출 0건이라 MCP 검증 불필요. PR-6 진입 시 backend-core가 KIS MCP server 검증 후 endpoint 명세에 출처 표기.

---

## 3. frontend 위임 (PR-5b)

### 3-1. 메타

| 항목 | 값 |
|---|---|
| 담당 에이전트 | frontend (sonnet) |
| 브랜치 | `agent/frontend/pr-5b-vite-spa` |
| base | `main` (PR-5a 머지 후 base가 PR-5a 포함된 main) |
| 작업 author | `bot+frontend@magicjar.local` |
| 작업 author name | `magicjar-frontend` |
| Task 파일 | `node scripts/task.mjs start --agent frontend --branch agent/frontend/pr-5b-vite-spa ...` |
| 머지 정책 | rebase |
| 분량 | M (Vite scaffold + 라우터 + 2 placeholder 페이지 + Dockerfile) |

### 3-2. 작업 범위 (Done 정의)

#### A. Vite SPA 스캐폴드
- [ ] `frontend/package.json` — pnpm + React 19 + TS 5.x + Vite 5.x + react-router-dom + @tanstack/react-query + zustand + lightweight-charts ^4.x + tailwindcss ^3.x
- [ ] `frontend/pnpm-lock.yaml` — `pnpm install` 후 commit (frozen-lockfile 의무)
- [ ] `frontend/vite.config.ts` — React plugin + proxy `/api` → `http://localhost:8080` (dev 편의)
- [ ] `frontend/tsconfig.json` + `tsconfig.node.json` (Vite TS 표준)
- [ ] `frontend/tailwind.config.js` + `postcss.config.js`
- [ ] `frontend/index.html` — id=root + 한글 lang + KST timezone meta
- [ ] `frontend/.gitignore` — node_modules, dist, .env.local 등

#### B. React 19 + 라우터 + placeholder
- [ ] `src/main.tsx` — React 19 createRoot + BrowserRouter + QueryClientProvider
- [ ] `src/App.tsx` — Routes (/ → Dashboard placeholder, /setup → SetupWizard placeholder, * → Navigate /)
- [ ] `src/index.css` — `@tailwind base/components/utilities`
- [ ] `src/pages/SetupWizardPlaceholder.tsx` — Tailwind + "magicJar 셋업 마법사 — PR-6에서 구현 예정" stub
- [ ] `src/pages/DashboardPlaceholder.tsx` — Tailwind + "magicJar 대시보드 stub" stub
- [ ] 빈 디렉토리 + `.gitkeep`: `src/components/`·`src/api/`·`src/ws/`·`src/stores/`·`src/lib/`

#### C. Dockerfile + nginx
- [ ] `frontend/Dockerfile` — multi-stage (node:20-alpine build → nginx:alpine serve)
- [ ] `frontend/nginx.conf` — SPA history mode fallback (try_files)

#### D. 검증 게이트 통과
설계 가이드 §9-2 모든 항목 GREEN.

### 3-3. commit 분할 권장

| Commit | 메시지 | 변경 파일 |
|---|---|---|
| 1 | `feat(frontend): Vite SPA 스캐폴드 — React 19 + TS + pnpm + Tailwind + LWC v4` | package.json + pnpm-lock.yaml + vite.config.ts + tsconfig*.json + tailwind.config.js + postcss.config.js + index.html + .gitignore |
| 2 | `feat(frontend): React Router + 2 placeholder 페이지 (Dashboard·SetupWizard)` | src/main.tsx + src/App.tsx + src/index.css + src/pages/* + .gitkeep 빈 디렉토리 |
| 3 | `feat(frontend): Dockerfile + nginx SPA fallback` | Dockerfile + nginx.conf |

### 3-4. PR 본문 템플릿

```markdown
## Summary
- PR-5b: frontend Vite SPA 스캐폴드 — React 19 + TS + pnpm + Tailwind + LWC v4 + 라우터 + 2 placeholder 페이지 + Dockerfile.
- 셋업 마법사·차트·STOMP 등 실제 구현은 PR-6+ (T-ONBOARD 등).
- 정합: `_workspace/specs/pr-5-w1-bootstrap-design.md` + ADR 0009·0040.

## Scope
- frontend/ 디렉토리 신설
- React 19 + TypeScript + Vite 5 + pnpm + Tailwind 3 + lightweight-charts 4 + react-router-dom 6 + @tanstack/react-query 5 + zustand 4
- 2 placeholder 페이지 (/ + /setup)
- Dockerfile multi-stage (node:20 build → nginx:alpine serve) + SPA history fallback
- docker-compose.yml frontend 서비스 build 정합 검증 (변경 0)

## Test plan
- [x] `cd frontend && pnpm install --frozen-lockfile` 종료 코드 0
- [x] `pnpm tsc --noEmit` 0 error
- [x] `pnpm build` GREEN, dist/ 생성
- [x] `docker compose -f docker/docker-compose.yml build frontend` 종료 코드 0
- [x] `docker compose up -d frontend` 후 `curl http://localhost:3000` → 200 + `<html>` (placeholder)
- [x] `grep "lightweight-charts" frontend/package.json` → ^4.x 매칭

## 정합 ADR
- 0009 — frontend SPA (React + TS + Vite + LWC v4)
- 0040 — 관심종목 우선 UI 흐름 (Dashboard placeholder는 향후 Watchlist 진입점)
```

### 3-5. frontend 의존성·정합 의무

- **ADR 0009 §LWC**: LWC 공식 API만 사용. 자체 canvas 오버레이·private 필드 조작 금지. PR-5b는 placeholder만이라 LWC 호출 0건 — 의존성만 추가.
- **ADR 0040 §UI 흐름**: Dashboard는 향후 Watchlist 진입점. PR-5b 스플래시는 "관심종목 → 차트 → 주문" 흐름 안내 stub만.
- **TradingView Advanced 0 의존**: master §9 폐기 정합 — Advanced 라이브러리 import 절대 금지.
- **pnpm lockfile commit 의무**: CLAUDE.md 명시 — `pnpm install --frozen-lockfile` 통과 의무 → CI 정합.

---

## 4. PR-5c — CI 워크플로 정상화 (architect, 선택)

### 4-1. 발생 조건

PR-5a + PR-5b 머지 후 `.github/workflows/ci.yml` 검증 시 다음 중 하나 fail:
- 4 모듈 matrix `:check` + `:bootJar` 미정의
- frontend `pnpm test/build` job 부재
- Flyway 검증 step 부재

### 4-2. 작업 범위 (필요 시)

- [ ] `.github/workflows/ci.yml` — JVM 4 모듈 matrix `:check` + `:bootJar`(domain 제외) + frontend `pnpm install/build`
- [ ] `.github/workflows/release.yml` — main push 시 ghcr.io 자동 발행 (4 서비스)
- [ ] `.github/workflows/release-tag.yml` — `v*.*.*` 태그 → semver 이미지 + Release draft

### 4-3. 분량

S (선택) — 기존 워크플로 정합이면 변경 0. 본 PR은 PR-5a·PR-5b 머지 후 CI fail 검증 보고에 따라 발행.

### 4-4. 디스패치

architect가 직접 작성 (orchestrator의 별도 dispatch). 본 명세에선 **선택 PR**로 표기 — orchestrator가 PR-5a·PR-5b 머지 후 CI 상태 확인하여 결정.

---

## 5. 충돌 행렬 (직렬 vs 병렬 결정 근거)

### 5-1. 파일 단위 충돌 표

| 파일 | PR-5a (backend) | PR-5b (frontend) | PR-5c (architect) | 결정 |
|---|---|---|---|---|
| `settings.gradle.kts` | 변경 가능 (검증) | 무관 | 무관 | backend 단독 |
| `build.gradle.kts` (root) | 변경 가능 (subprojects 블록) | 무관 | 무관 | backend 단독 |
| `domain/**` | 신규 | 무관 | 무관 | backend 단독 |
| `api/**` | 신규 | 무관 | 무관 | backend 단독 |
| `batch/**` | 신규 | 무관 | 무관 | backend 단독 |
| `consumer/**` | 신규 | 무관 | 무관 | backend 단독 |
| `frontend/**` | 무관 | 신규 | 무관 | frontend 단독 |
| `docker/docker-compose.yml` | **변경 가능** (api/batch/consumer 정합 검증·env_file 정합) | **변경 가능** (frontend service 정합 검증·build context) | 무관 | **직렬 — backend 먼저, frontend는 backend 머지 후 base rebase** |
| `docker/docker-compose.dev.yml` | 변경 가능 | 변경 가능 | 무관 | **직렬 (위와 동) — 단 PR-5에선 변경 권장 0** |
| `docker/docker-compose.infra.yml` | 무관 | 무관 | 무관 | 변경 0 |
| `scripts/build-and-deploy.sh` | 검증만 | 검증만 | 무관 | 변경 0 |
| `.github/workflows/*` | **손대지 말 것** | **손대지 말 것** | 변경 가능 | PR-5c 단독 |
| `CHANGELOG.md` | 변경 가능 | 변경 가능 | 변경 가능 | **세 PR 모두 자기 영역 1줄씩 추가 — 충돌 가능성 있음. PR rebase 시 수동 정렬** |
| `README.md` (루트) | 변경 0 | 변경 0 | 변경 0 | 손대지 말 것 |
| `docs/architecture/*` | 변경 0 | 변경 0 | 변경 0 | 손대지 말 것 (PR-5는 부트스트랩 — SoT 변경 무관) |
| `_workspace/arch/adr/*` | 변경 0 | 변경 0 | 변경 0 | 손대지 말 것 (PR-5는 ADR 신규 발행 0) |
| `_workspace/specs/pr-5-*.md` | 변경 0 | 변경 0 | 변경 0 | 본 명세 자체 — 손대지 말 것 |
| `_workspace/tasks/T-*-pr-5a-*.md` | 신규 (자기 task) | 무관 | 무관 | backend 단독 |
| `_workspace/tasks/T-*-pr-5b-*.md` | 무관 | 신규 (자기 task) | 무관 | frontend 단독 |
| `.env.example` | 변경 0 | 변경 0 | 변경 0 | 손대지 말 것 (PR-5에 신규 env 0) |

### 5-2. 결론 — 권장 디스패치 순서

**1단계 — PR-5a (backend-core 단독)**
- backend는 docker-compose.yml의 api/batch/consumer 서비스 정의만 검증 (변경 권장 0). 만약 변경이 필요하면 frontend 서비스 부분은 손대지 말 것.

**2단계 — PR-5b (frontend 단독, PR-5a 머지 후)**
- frontend는 docker-compose.yml의 frontend 서비스 build context (`../frontend`) 정합만 검증.
- PR-5a가 docker-compose.yml을 수정했으면 frontend는 머지된 main을 rebase 후 작업.

**3단계 (선택) — PR-5c (architect 단독)**
- CI 워크플로 정합 검증 후 필요 시 발행.

### 5-3. 완전 병렬 불가 사유

| 사유 | 영향 |
|---|---|
| docker-compose.yml 양쪽 손댈 가능성 | merge conflict 가능 |
| PR-5a의 jar 산출물 명세에 frontend Docker가 의존 안 함이지만, `scripts/build-and-deploy.sh` 통합 검증 시 frontend는 PR-5a jar 정합 필요 | 빌드 종속성 |
| pre-push hook이 `./gradlew check` 자동 실행 — frontend가 backend 모듈 골격 없는 main에서 push 시 Gradle 실패 | hook 종속성 |
| ktlint pre-commit — frontend 작업 중 backend `.kt` 변경 0이라 직접 충돌은 없으나, gradle 모듈 미존재 시 hook 실패 가능 | hook 종속성 |

**예외 — 완전 병렬 가능 시나리오**: backend가 `docker-compose.yml` 절대 미손대 + frontend가 `pre-push` 우회 (`SKIP_GRADLE_CHECK=1`) 사용. **권장하지 않음** — 직렬이 단순.

---

## 6. 검증 게이트 통합 (PR-5 시리즈 머지 전)

설계 가이드 §9 정합. 본 명세는 통합 게이트만 재정리:

### 6-1. PR-5a 단독 게이트
| # | 명령 | 결과 |
|---|---|---|
| 1 | `./gradlew build` | GREEN |
| 2 | `./gradlew ktlintCheck` | 0 violation |
| 3 | `./gradlew :api:bootJar :batch:bootJar :consumer:bootJar` | 3 jar 생성 |
| 4 | `grep -r "import org.springframework" domain/src/main` | 0건 |
| 5 | `scripts/build-and-deploy.sh --gradle-only` | 종료 0 |
| 6 | `docker compose -f docker/docker-compose.infra.yml up -d` | 인프라 3 healthy |
| 7 | api-app 기동 후 `psql -c "\dt"` | 11 테이블 |
| 8 | `psql -c "SELECT id FROM system_config"` | 1 row |

### 6-2. PR-5b 단독 게이트 (PR-5a 머지 후)
| # | 명령 | 결과 |
|---|---|---|
| 1 | `cd frontend && pnpm install --frozen-lockfile` | 종료 0 |
| 2 | `pnpm tsc --noEmit` | 0 error |
| 3 | `pnpm build` | dist/ 생성 |
| 4 | `docker compose build frontend` | 종료 0 |
| 5 | `docker compose up -d frontend` 후 `curl localhost:3000` | 200 + `<html>` |

### 6-3. 통합 게이트 (PR-5a + PR-5b 모두 머지 후)
| # | 명령 | 결과 |
|---|---|---|
| 1 | `scripts/build-and-deploy.sh` | 7 컨테이너 healthy |
| 2 | `curl localhost:8080/actuator/health` | UP |
| 3 | `curl localhost:8081/actuator/health` | UP |
| 4 | `curl localhost:8082/actuator/health` | UP |
| 5 | `curl localhost:3000` | 200 |

### 6-4. CHANGELOG.md 의무
- PR-5a: "feat(bootstrap): 4 JVM 모듈 골격 + Flyway V1 baseline 11 테이블"
- PR-5b: "feat(frontend): Vite SPA 스캐폴드 + React 19 + LWC v4 + Tailwind"
- PR-5c (선택): "ci(workflow): JVM 4 모듈 matrix + frontend pnpm build job"

---

## 7. 운영·실패 대응

### 7-1. backend-core가 PR-5a 분량 초과 인지 시
- domain 모듈만 PR-5a-1, 나머지 모듈 PR-5a-2로 분할 가능 (orchestrator 사전 동의 필요)
- Flyway V1 SQL을 별도 PR-5a-3로 분할 가능 — 그러나 api 모듈 부팅 검증 불가 → 권장하지 않음

### 7-2. frontend가 PR-5b 진행 중 PR-5a 미머지 발견 시
- 즉시 중단 → orchestrator에 보고 → PR-5a 머지 후 재개

### 7-3. CI fail 시
- backend-core·frontend 본인 영역 fail → 본인 PR에서 수정
- `_workspace/specs/pr-5-*.md` 자체 결함 발견 → architect 호출 → SoT 갱신 + sub-agent 작업 재개

### 7-4. ADR 정합 누락 발견 시
- backend-core·frontend는 직접 ADR 변경 금지
- architect 호출 → 별도 PR로 ADR 정정 → main 머지 후 sub-agent 작업 재개

---

## 8. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-5 sub-agent 분배 명세 v1 발행. PR-5a (backend-core) → PR-5b (frontend) → PR-5c (architect 선택) 직렬 순서 + 충돌 행렬 + 검증 게이트 통합 (architect, opus 4.7 1M). |
