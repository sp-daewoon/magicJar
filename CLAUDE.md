# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트: magicJar (마법의항아리)

Spring Boot 3.3 + Spring AI + 한국투자증권 KIS OpenAPI 기반 **개인 맞춤형 한국 주식 자동매매 시스템**. **룰 기반 quant trading 본체** + AI는 사용자 트리거 옵션 분석 보조(매매 결정권 0) + 미래에 MCP 서버 노출. 자세한 비전은 `docs/architecture/master-architecture.md` §1.

## 하네스: magicJar 자동매매 팀

사용자가 **최고 관리자**, `magicjar-orchestrator` 스킬이 그 위임을 받아 **7명** 전문 에이전트(architect · backend-core · market-data · strategy-engine · ai-engineer · frontend · qa)를 통솔합니다. **2026-05-21 ADR 0094 amend** — 운영 단계 진입 (개발 단계 종착 + 운영·점진 고도화 단계) + chart-curator 해고 (캔들 SLA → market-data / LWC v4 → frontend 책임 이관) + **qa Visual "품질 헌터" 격상** (Playwright + 스크린샷 + 시각 회귀 + E2E + DR drill 자동 재현) + sre 영입 거절 (P0 OPS 6건 backend-core 인수).

**트리거:** magicJar 관련 모든 비트리비얼 작업은 `magicjar-orchestrator` 스킬 사용. 단순 질문(설명·조회) 또는 단일 파일 소소한 편집은 직접 응답 가능. **모듈 추가·전략 추가·AI 기능·화면 추가·차트/캔들 작업·frontend 핵심 화면 변경(헌법 §9-2 qa Visual 자동 의무)·부분 재실행은 반드시 오케스트레이터.**

**위치:** `.claude/agents/*.md`, `.claude/skills/magicjar-*/SKILL.md`. `.claude/agents/_archive/chart-curator.md`는 역사 보존(dispatch 대상 X). **전체 에이전트 opus 4.8 통일** (ADR 0080, 2026-05-18 — ADR 0035 supersede + ADR 0094 amend 2026-05-21 8→7 actor + **2026-06-05 사용자 직접 지시로 opus 4.7 → 4.8 상향, 사용자가 내리기 전까지 유지**. frontmatter `model: opus` 별칭이 최신 opus 추종). 사유: 운영 단계에서 코드 한 줄 수정도 룰 본체·자본 흐름 결합으로 진화하므로 하위 모델 패턴화 추론 risk 회피.

## 방향성 보존 가드 (최고 관리자 지시)

코드 완성도가 올라간 상태에서는 새 변경이 기존 방향을 갑자기 틀어버리는 것이 가장 큰 리스크다. **모든 비트리비얼 코드 변경·설계 변경·리팩터링·캔들/오더블록/자동매매 흐름 변경 전에는 `magicjar-direction-guard` 스킬을 먼저 적용**한다.

- 기존 SoT/ADR/정책/현재 구현을 먼저 읽고, "왜 지금 이렇게 되어 있는가"를 파악한다.
- 변경 전/후 방향 차이, 데이터 흐름, 자본 흐름, API 계약, 사이트/화면 UX, 운영 스케줄, 검증 범위를 함께 비교한다.
- 기존 방향을 바꾸는 변경은 작은 버그 수정처럼 처리하지 않는다. 대안·위험·마이그레이션·롤백 가능성을 정리하고 사용자 확인 또는 ADR/spec 갱신을 거친다.
- 불필요한 대규모 재작성보다 현 구조 위에 보수적으로 보강하는 경로를 우선한다.
- 실시간 시세 수요를 watchlist 단순 합집합으로 다루지 않는다. watchlist는 사용자가 고정해서 보는 목록이고, 종목 상세 진입은 `DETAIL`, 스크리너/전략 후보는 `STRATEGY` owner로 TTL·우선순위가 분리된 KIS WS 수요여야 한다. 전략 후보를 watchlist에 자동 삽입하지 않는다.
- 종목 상세 화면은 watchlist 등록 여부와 무관하게 KIS WS 현재가·진행 중 캔들 close를 요청해야 한다. REST는 bootstrap/backfill/fallback이며 장중 실시간 권위는 KIS WS tick이다. **호가(H0STASP0)는 ① 전략 ACTIVE 매매 경로(STRATEGY owner, marketable limit 진입가 산정) ② 종목 상세 화면 표시 중(DETAIL owner — 진입 시 구독, 이탈 즉시 해제) 두 경로에서만 구독한다**(2026-06-12 재결정, anchor `detail-orderbook-live-only`). DETAIL 호가는 보기용 합집합/LRU 잔류로 슬롯을 누적 잠식하지 않고 현재 보는 종목만 점유하며, EXIT 누락(브라우저 강제 종료) 대비 15분 idle 안전망을 둔다. 같은 종목을 두 owner가 요구하면 단일 H0STASP0 구독을 공유한다.

### FVG / OrderBlock 작업 가드

- FVG/OrderBlock은 계산기만 추가하면 완료로 보지 않는다. 전략 후보, 스크리너 기준봉, 차트 overlay 검증 흐름까지 사용자가 이어서 확인할 수 있어야 한다.
- 차트 박스/overlay는 실제 indicator 결과와 실제 candle DB를 기준으로 그린다. 데이터가 없거나 부족하면 mock 박스 대신 오류·준비·재수집 path를 노출한다.
- 전략 후보 프리셋은 자동매매 결정을 대신하지 않는다. 사용자가 검증·저장·스크리닝을 실행하는 후보 출발점으로 취급한다.

## 핵심 명령

빌드 도구는 호스트 Gradle wrapper 기반. `package.json`은 husky 호스트 + 상태 스크립트 전용이며 앱 코드와 무관.

```bash
# 전체 검증 (모든 모듈)
./gradlew check

# 단일 모듈 검증·빌드
./gradlew :api:check
./gradlew :api:bootJar

# 단일 테스트 클래스 / 메서드
./gradlew :api:test --tests "kr.spdaewoon.magicjar.SomeTest"
./gradlew :api:test --tests "kr.spdaewoon.magicjar.SomeTest.someMethod"

# Kotlin 린트 (pre-commit이 자동 실행)
./gradlew ktlintCheck
./gradlew ktlintFormat            # 자동 정정

# 전체 통합 기동 (gradle bootJar + docker compose build/up — 한 줄 헬퍼)
scripts/build-and-deploy.sh                # 전체
scripts/build-and-deploy.sh api-app        # 특정 서비스만
scripts/build-and-deploy.sh --gradle-only  # jar만 갱신
scripts/build-and-deploy.sh --no-gradle    # docker만 (jar 최신 가정)

# 인프라(postgres·kafka·redis)만 컨테이너로 띄우고 IDE bootRun
docker compose -f docker/docker-compose.infra.yml up -d

# 통합 스모크 (3 시나리오 — 백테스트·시그널·AI 분석)
scripts/smoke.sh

# Task 파일 시스템 (ADR 0036 — agent/* 브랜치 작업 시 의무)
node scripts/task.mjs start --agent <a> --goal "..." --deliverables "..."
node scripts/task.mjs check "<항목>"        # 결과물 1개 완료
node scripts/task.mjs done --pr <N>         # PR 생성 후
```

## 운영 함정 가드 (반드시 인지)

1. **stale jar 함정** — `api/batch/consumer` Dockerfile은 `COPY build/libs/*.jar`로 호스트 산출물을 가져옴. 코드 변경 후 `bootJar` 미실행이면 컨테이너에 옛 jar가 들어감. `scripts/smoke.sh`가 src mtime > jar mtime이면 abort(우회: `SKIP_FRESHNESS_CHECK=1`). `scripts/build-and-deploy.sh`가 정석 경로.

2. **docker compose 호출 시 `--env-file` 절대경로 + `--project-directory` 미사용** — compose 작업디렉토리가 `docker/`로 바뀌면 `.env`를 못 찾고, `--project-directory` 사용 시 `build.context: ../batch`가 root 기준으로 잘못 해석됨.

3. **default profile = `dev,real-kis`** — mock 부팅 path 폐기. mock은 jvm test fixture 전용이며 사용자 흐름에서 사용 금지.

4. **실계좌 이중 게이트** — `MAGICJAR_MODE=REAL` + `MAGICJAR_ALLOW_REAL=true` 둘 다여야 실주문. dev/staging은 항상 MOCK.

## Git hook 강제 사항 (`.husky/`)

### pre-commit (ADR 0036)
- **author email 화이트리스트 강제**. 다음 외에는 커밋 거부:
  - `sp-daewoon@gmail.com`, `seonjupark94@gmail.com` (사용자 본인)
  - `bot+<agent>@magicjar.local` — `<agent>` ∈ {architect, backend-core, market-data, strategy, ai, frontend, qa, orchestrator, chart-curator(archived)}
  - **chart-curator는 2026-05-21 ADR 0094 amend로 해고**되었으나, 역사 evidence 보존 + 사용자 force commit 시점 회피를 위해 `.husky/pre-commit` allowed list는 유지. dispatch는 0 (오케스트라가 대상 매트릭스에서 제외).
- 따라서 에이전트 모자를 쓸 때는 매 커밋에 `git -c user.name="magicjar-<agent>" -c user.email="bot+<agent>@magicjar.local" commit ...` 또는 작업 시작 시 `git config user.email` 갱신.
- 시크릿 추정 파일(`.env`, `application-local.*`, `*.p12`, `*.pem` 등) staged 시 차단.
- 변경된 `.kt`/`.kts` 있으면 `ktlintCheck` 자동 실행.

### pre-push (ADR 0031)
- `main` 직접 push 차단 — agent/* 브랜치 + PR만.
- `release/*` 기존 ref update/delete 차단 (immutable 베이스라인). 신규 생성만 허용.
- agent/* 브랜치는 `_workspace/tasks/*.md` 존재 권고.
- `./gradlew check` 자동 실행.

브랜치명 규약: `agent/<agent>/<task>` | `chore/<slug>` | `docs/<slug>` | `hotfix/<slug>`.

**머지 정책: `--rebase`** (squash가 author를 web user로 덮어쓰는 것 회피 — pre-commit 화이트리스트와 충돌 방지).

## 아키텍처

```
JVM 7 모듈 (Kotlin 2.0 K2 / Spring Boot 3.3 / JDK 21 / Gradle KTS) — 2026-05-21 baseline
├─ domain/              순수 도메인 + port (Spring·JPA 의존 0)
├─ api/                 REST/STOMP + 외부 어댑터(KIS·Yahoo·DART·Naver) + Kafka producer
├─ batch/               Spring Batch — 일봉 backfill·전략 백테스트 잡
├─ consumer/            Kafka 소비 + KIS WS + 시그널·주문 dispatch + AI 분석
├─ persistence-shared/  Outbox Repository + JPA 어댑터 공유 (ADR 0088 · api·consumer 모듈 의존 회피 — 2026-05-19 모듈 분리)
├─ indicator-impl/      Indicator 계산 lib (ADR 0083 · api·consumer 공유)
└─ strategy-engine/     DSL parser + StrategyEvaluator 공유 (ADR 0092 · api·consumer 공유)

frontend/    React 19 + TS + Vite + Tailwind + LWC v4 (TradingView Lightweight Charts) — pnpm
docker/      docker compose 통합(인프라 3 + 앱 4 = 7 컨테이너)
observability/  Prometheus + Grafana SLO dashboard (P0-OPS-1·2·6 — ADR 0095 alert routing)
```

데이터(A) → 분석·전략(B) → 의사결정·실행(C) → 운영(D) 4영역 19컴포넌트 맵은 `docs/architecture/master-architecture.md` §2.

## 개발 룰 5건 (`docs/architecture/development-rules.md`)

1. **QA 게이트 배제** — MVP 단계는 on-demand 0회 default. `MAGICJAR_MODE=REAL` 진입 시 sunset.
2. **단일 책임 코드** — 한 PR = 한 영역. 2영역 이상이면 PR 본문에 사유.
3. **API 빌딩 한 스텝씩 순차** — 라운드 분할 (R1 도메인+시그니처 → R2 어댑터+영속 → R3 UI+일괄 테스트). 한 PR 4 layer 동시 변경 금지.
4. **코드 먼저, 테스트 마지막 일괄** — R1·R2 PR은 테스트 0건 OK. R3에서 데이터 흐름 시나리오 일괄.
5. **AI 호출 자제** — W1·W2·W3 트랙 코드에 LLM provider import 발견 시 reject. AI는 W4 한정.

## 단일 진실원 (SoT)

> **★ 모든 자원의 단일 진입점**: [`_workspace/INDEX.md`](_workspace/INDEX.md) — 의도별 진입 경로(현행 규약 / 결정 이력 / 진행 중 / snapshot / task)
> **★ 현행 권위 정책 (Living Policy)**: [`docs/architecture/policies/`](docs/architecture/policies/README.md) — "지금 무엇이 권위인가"의 단일 위치 (ADR-0053)

| 주제 | 위치 |
|------|------|
| **단일 진입점 인덱스** | [`_workspace/INDEX.md`](_workspace/INDEX.md) **★ 첫 번째 lookup** |
| **현행 권위 정책** | [`docs/architecture/policies/`](docs/architecture/policies/README.md) (KIS credential·로그 레벨·KIS-first·task cycle·mermaid 등) |
| 마스터 아키텍처 | `docs/architecture/master-architecture.md` (ADR 0030 SoT) |
| 트랙 로드맵 | `docs/architecture/tracks-roadmap.md` |
| 개발 룰북 | `docs/architecture/development-rules.md` |
| 코드 컨벤션 | `docs/architecture/code-convention.md` |
| 외부 API 정책(KIS 호출 빈도·토큰) | `docs/architecture/external-api-policy.md` |
| 캔들 데이터 정책 | `docs/architecture/candle-data-policy-v2.md` |
| ADR 인덱스 (결정 이력) | `_workspace/arch/adr/README.md` (총 93+ ADR · Accepted ~70 + Proposed ~15 + Superseded 3 · 파일명 = 본문 헤더 번호) |
| 진행 중 의제 (backlog) | `_workspace/backlog.md` (처리 완료 §은 minor release마다 `done/v3.X.x.md`로 archive) |
| baseline 시점 snapshot | `_workspace/baseline/<version>/` (immutable, `/baseline init <ver>` 명령) |
| 브랜칭·머지·author 정책 | `_workspace/arch/branching-rules.md` |
| KIS API 레퍼런스 | `docs/operations/kis-api-reference.md` |
| 초기 셋업 | `docs/operations/initial-setup.md` |
| 카탈로그 (KIS 80지표·57패턴·AI 프롬프트) | `_workspace/strategy/`, `_workspace/ai/` |

**모듈 경계**는 별도 SoT 문서 없음 — `master-architecture.md` §5 + `settings.gradle.kts` + ADR 0004가 권위.

## 트랙 ID

새 ID 체계만 사용. 옛 ID(T1~T18)는 사용 금지.

| Wave | 트랙 ID |
|------|---------|
| W1 | T-W1·T-W2·T-S1 (최우선) · T-MS1·T-IM1·T-CA1 |
| W2 | T-IND·T-PAT·T-DSL·T-SB |
| W3 | T-BT·T-SC·T-SIG·T-RISK·T-O·T-PERF·T-J |
| W4 | T-AI·T-MCP |
| W5 | T-LWC·T-LEARN |

전체 정의는 `tracks-roadmap.md` §1·§2.

## 환경변수

`.env.example` 복사 후 `.env`로 채워서 사용. KIS 키(prod=조회 / vps=주문 분리), Anthropic/OpenAI 키, DART, Postgres·Redis·Kafka 좌표, `MAGICJAR_AI_*` 옵션 게이트가 핵심. AI는 `MAGICJAR_AI_ENABLED=false` default + `MAGICJAR_AI_MOCK_MODE=auto`(키 없으면 자동 mock).

## CI/CD

- PR 검증: `.husky/pre-push`가 `./gradlew check` + frontend `pnpm typecheck/build` 단독 책임. GitHub Actions 폐기(`feedback_no_github_actions.md`).
- release 절차 (ADR 0052 §X 정합, 순서 LOCK):
  1. `/baseline init vX.Y.0` — 4 sub-agent 병렬 → 7 md + 8 html 풀세트
  2. baseline 8 PR 머지 완료 후 → `git tag vX.Y.Z` + `release/vX.Y.Z` brunch push (ADR 0031 immutable)
  3. `scripts/release.sh vX.Y.Z` — release notes 풍부 합성(baseline html 인용) + `gh release create` + `sync-readme-releases.mjs` 자동 갱신

## 변경 이력

[CHANGELOG.md](CHANGELOG.md) — 의미 있는 변경마다 한 줄 추가.
