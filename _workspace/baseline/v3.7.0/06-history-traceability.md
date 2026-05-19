# 06 — PR · ADR · 기능 추적성 매트릭스 (v3.6.0 → v3.7.0)

> **작성자**: architect (PR-36 baseline v3.7.0)
> **시점**: 2026-05-19 v3.7.0 태그 컷 직후 (release commit `<TBD>`, `release/v3.7.0` 이미지 immutable)
> **범위**: v3.6.0 (2026-05-13 KST) → v3.7.0 (2026-05-19 KST 시점) 사이 **19 patch 릴리즈(v3.6.1~v3.6.19) + 200+ PR + ADR 신규 22건 + ADR amend 5건 + ADR Superseded 2건 + 운영 헌법 §1-A-extended 신설 + 운영 헌법 §2 4단계 → 6단계 + 8번째 sub-agent chart-curator 영입 + 페르소나 매트릭스 2건**.

본 문서는 **PR·ADR·기능 3축 추적성 매트릭스**다. raw 출처:
- `git log v3.6.0..main --oneline` (worktree 직접 실행)
- `git tag --list "v3.6.*"` 결과 인용 (v3.6.1 ~ v3.6.19 19건)
- `CHANGELOG.md` 시계열 row
- `_workspace/arch/adr/README.md` 인덱스 (65 → 90, 0085~0090 신규 6건 + 0085·0086 amend + 0035·0073 Superseded)
- `_workspace/backlog.md` § 단위 (시스템 점검 매트릭스 + 라운드 1·2·3·4·5)

---

## §1. v3.6.x patch 19 cut 시계열 (v3.6.0 → v3.7.0 사이 진화)

| 버전 | 일자 | 핵심 미러스톤 | 주요 ADR | 주요 PR | 사용자 점검 흡수 |
|------|------|---------------|----------|---------|------------------|
| v3.6.0 | 2026-05-13 | T-O W3 네 번째 트랙 마감 (자동매매 본체 종착 노드 가동) | 0068 Accepted | #370~#376 | — |
| v3.6.1 | 2026-05-13 | SettingsModal vs SetupWizard 책임 분리 | 0069 Accepted | — | "설정 UX 정합 개선" |
| v3.6.2 | 2026-05-13 | T-RECON + T-NOTIFY 묶음 spec/plan (W3 자본 보호 prereq) | 0070 Accepted | — | "자동 reconcile 필요" |
| v3.6.3 | 2026-05-14 | T-SC Screener spec/plan (W3 종목 발굴 자동화) | 0071 Accepted | — | "스크리너 핵심 UX" |
| v3.6.4 | 2026-05-14 | T-J + T-PERF 묶음 spec/plan (W3 마지막 사이클) | 0072 Accepted | — | "매매일지 + 성과" |
| v3.6.5 | 2026-05-14 | SettingsModal 풀세트 (KIS·KillSwitch·AI) | 0074 Accepted | — | "환경설정 통합" |
| v3.6.7 | 2026-05-15 | T-NEWS 뉴스·공시 실시간 인입 트랙 (DART + Naver) | 0075 Accepted | — | "뉴스 속보·공시 핵심" |
| v3.6.9 | 2026-05-15·16 | KIS WS NXT/시간외 + 내부 자산 추출 spec | 0076·0078 Proposed | — | "NXT 정합" |
| v3.6.10 | 2026-05-17 | 마스터 시각화 HTML 표준화 (`/master-index` slash + release.sh 통합) | 0079 Accepted | #461·#462 | "프로젝트 인덱스 매트릭스 시각화" |
| v3.6.11 | 2026-05-17 | OPSP8996 hotfix (재발 86건/1시간 deep dive) | 0077 Proposed | — | "재발 차단" |
| v3.6.12·13 | 2026-05-17·18 | T-O 본 트랙 마감 격상 + dogfooding 케이스 5 GREEN | 0068 격상 | #481·#485·#486 | "T-O 마감 검증" |
| v3.6.14 | 2026-05-18 | T-ACCT 신규 트랙 발행 + indicator-impl 결함 3 fix | 0081 Proposed · 0083 Proposed | #492 | "잔액·예수금 부재" + "GET /api/indicators 400" |
| v3.6.15 | 2026-05-18 | T-MAN 신규 트랙 발행 + indicator-impl Accepted | 0082 Proposed · 0083 Accepted | — | "주문 화면 없음" |
| v3.6.16 | 2026-05-18 | 외부 코드 리뷰 13 코멘트 흡수 + 주문 path P0 4건 마감 (전체 에이전트 opus 4.7) | 0080 Accepted · 0084 P0 baseline | — | "외부 검증 흡수" |
| v3.6.17 | 2026-05-19 | T-ACCT Phase 4 + chart-curator 영입 + T-CC Phase 1 + Phase 2-A 햇징 룰 10건 | 0081·0085·0086 Accepted | #499·#509·#514·#525 | "차트·캔들 단일 owner 영입" |
| v3.6.18 | 2026-05-19 | T-CC Phase 2-B + StompEventPersister jsonb cast + Outbox audit + 햇징 룰 §11·§12·§13 amend | 0086 amend | #523·#537·#538·#540·#541 | "한국석유 dogfooding 결함 fix" |
| v3.6.19 | 2026-05-19 | chart-curator 페르소나 amend + backend-core 페르소나 부여 | 0085·0087 | #544·#546 | "8 sub-agent 통일 운영 룰" |
| **v3.7.0** | **2026-05-19** | **시스템 점검 + Phase 보강 + 운영 헌법 진화 baseline (W4 진입 0)** | **0088·0089·0090 + §1-A-extended + §2 6단계** | **#548~#575+** | **8 sub-agent system audit + Outbox wiring 결함 영구 차단** |

---

## §2. v3.7.0 사이클 라운드 1·2·3·4·5 PR 매트릭스 (시스템 점검 ~ Phase 보강)

### 2-1. 사이클 트리거 — 시스템 점검 8 sub-agent audit (2026-05-19 KST · 8 PR 병렬)

사용자 명시 정합("v3.6.19 마감 후 시스템 점검부터 차근차근 + W4 진입 안 함"). orchestrator가 8 sub-agent 병렬 디스패치 + audit report 일괄 도출.

| PR | sub-agent | 영역 | 핵심 결함·의제 |
|----|-----------|------|----------------|
| #548 | architect | 헌법·ADR·모듈·sub-agent 매트릭스 | extensions.worktreeConfig 결함 root cause 도출 + ADR 0088 신설 candidates(Outbox wiring) + 페르소나 매트릭스 evidence |
| #549 | frontend | frontend 전체 영역 | userId string ↔ number cross-cutting drift 5+ 영역 evidence(P2-F1) + RuntimeFlags display 부재 + STOMP unread/resync 부재 |
| #550 | qa | 경계면 교차 검증 | cross-validation evidence + Outbox 운영 흐름 작동률 0% 검증 |
| #551 | backend-core | 인프라 + Spring + Flyway · DTO · port | persistence-shared 모듈 결함 evidence + DRY refactor candidates + Signal.HOLD 죽은 ref + !! 9 자본 path 가드 ★ 첫 진보 페르소나 dogfooding |
| #552 | ai-engineer | prompt-catalog + AI 영역 | prompt-catalog 드리프트 + dead config 정리 candidates(H1·H2·H3·H4 묶음) |
| #553 | strategy-engine | DSL · indicator · pattern · backtest | spec drift evidence + 잔여 의제 |
| #554 | chart-curator | LWC v4 차트 + 캔들 데이터 SLA | ADR 0085 §책임 영역 명확화 결함 + ADR 0090 candidates(instrument_status_history) + active gap detection axis 2·3 시점 부재 한계 ★ 첫 보수 페르소나 dogfooding |
| #555 | market-data | KIS·DART·Naver 어댑터 | Bucket4j stub DEGRADED 상태 + ADR 0037 spec drift "20 req/s" vs "초당 10건" 15+ 위치 + KIS raw 사전 ASC 정규화 결함(api `CandleRefreshService.parseKisDailyOutput2`) |

→ **하나로 누적 26+ hotfix 의제 도출 + ADR 신설 candidates(0088·0089·0090) 도출 + 페르소나 운영 evidence 첫 사이클**

### 2-2. 라운드 1 — P0 hotfix + spec 발행 (2026-05-19 KST · 6 PR 시계열)

| PR | head ref | 종류 | 요약 |
|----|----------|------|------|
| #557 | `hotfix/api-asc-normalization` | hotfix·api | **hotfix(api): CandleRefreshService.parseKisDailyOutput2 ASC 정규화 누락 fix** — market-data PR #555 evidence + ADR 0086 §11 정합. KIS `inquire-daily-itemchartprice` DESC 응답 → adapter parse 직전 `.sortedBy { it.ts.epochSeconds }` 의무 LOCK |
| #558 | `hotfix/arch/p0-worktreeconfig-and-persistence-shared-spec` | hotfix·architect | **hotfix(arch): P0-C extensions.worktreeConfig + P0-A persistence-shared 모듈 spec (ADR 0088 Proposed)** — 헌법 §1-A-extended 신설 + ADR 0088 발행 + setup-worktree-config.sh 헬퍼 신설 + 8 sub-agent author 정합 영구 LOCK |
| #559 | `agent/architect/p0-worktreeconfig-and-persistence-shared-spec` | architect spec | 위 hotfix #558의 정식 PR 화 (동일 산출물) |
| #561 | `agent/chart-curator/p1-5-lastcandlemonitor-kdoc` | chart-curator P1-5 | **docs(chart-curator): P1-5 LastCandleAtMonitor KDoc 보강** — §11·§12·§13 cross-ref + §12 폐기 룰 vs SLA watchdog 임계치 혼동 차단 (보수 페르소나 운영 evidence #2) |
| #562 | `agent/ai/p2-3-prompt-catalog-cleanup` | ai-engineer P2-3 | **chore(ai): P2-3 prompt-catalog 드리프트 정정 + dead config 정리** — H1·H2·H3·H4 묶음. AI 호출 0 LOCK 유지(W4 진입 0 정합) |
| #564 | `agent/architect/p1-4-bucket4j-spec-amend` | architect spec | **spec(arch): P1-4 ADR 0037 amend — Bucket4j 실 구현 + KisRateLimiterPort + EGW00201 backoff** — 옵션 A 채택 · 5분 Circuit Breaker · spec drift 정정 |
| #565 | `agent/backend-core/p0-a-persistence-shared-module` | backend-core 본격 P0-A | **feat(persistence-shared): P0-A 신규 모듈 + Outbox 어댑터 이관** — 라이브러리 모듈 신설 + 어댑터 9 파일 git mv + 패키지 rename + Gradle 4 파일 변경 + 운영 흐름 작동률 0% → 100% 회복 |

### 2-3. 라운드 2·3·4·5 — Phase 보강 + ADR 0089·0090 (2026-05-19 KST · 12+ PR 시계열)

| PR | head ref | 종류 | 요약 |
|----|----------|------|------|
| #567 | (sysconfig refactor) | backend-core | InfrastructureBeans 모듈화 정합 |
| #568 | `agent/backend-core/p1-4-phase1-bucket4j-impl` | backend-core P1-4 Phase 1 | **feat(api): P1-4 Phase 1 — Bucket4j-Redis KisRateLimiterPort 실 구현** — spec PR #564 정합 구현 + Bucket4j 8.10.1 + Redis backend 단일 bucket LOCK + EGW00201/429 backoff (진보 페르소나 dogfooding #2) |
| #570 | `agent/backend-core/p2-4-progressive-persona-refactor` | backend-core P2-4 | **refactor(backend-core): P2-4 진보 페르소나 — StrategyJpaAdapter DRY 추출 + Signal.HOLD 죽은 ref + !! 9 자본 path 가드** (진보 페르소나 dogfooding #3) |
| #571 | `agent/market-data/p1-4-phase2-kis-rate-limit-adr-0090-phase1` | market-data P1-4 Phase 2 + ADR 0090 Phase 1 | **feat(market-data): P1-4 Phase 2 KIS rate limit 통합 + ADR 0090 Phase 1 instrument_status_history (Flyway V42)** — ~20 어댑터에 `kisRateLimiterPort.acquire()` 합류 + spec drift 정정 "20 req/s" → "초당 10건" + `InstrumentStatusHistoryPort` + adapter + `InstrumentMasterRefreshJob` cron 보강 + backfill 1회 + audit_trail `INSTRUMENT_STATUS_CHANGED` |
| #573 | (P1-3 STOMP unread + P2-2 RuntimeFlags backend) | backend-core 본격 | **feat(api): P1-3 STOMP unread endpoint + P2-2 RuntimeFlags REST (frontend hook prereq)** — `GET /api/stomp/events/unread`·`GET /api/stomp/events`·`GET /api/runtime/flags` 3 endpoint 신설 + `StompEventPersister` + `RuntimeFlagsController`. backend Phase 1 contract LOCK (진보 페르소나 dogfooding #4) |
| #574 | `agent/chart-curator/adr-0090-phase2-active-gap-axes-history` | chart-curator P2-5 Phase 2 | **feat(chart-curator): ADR 0090 Phase 2 — passesActiveGapAxes history snapshot 조회 (axis 2·3 시점별 정밀화)** — `CandleGapDetectorAdapter.passesActiveGapAxes()` history at ts lens 통합 + 한국석유 dogfooding cycle cross-ref + unit test 5건 GREEN (보수 페르소나 dogfooding #3) |
| #575 | `agent/frontend/adr-0089-userid-p2-2-flags-p1-3-stomp-hook` | frontend 본격 (3 묶음) | **feat(frontend): ADR 0089 userId 통일 + P2-2 RuntimeFlags display + P1-3 useStompReconnect hook** — Part A ADR 0089 Phase 2 userId number 통일(`useOnboardingStore.registeredUserId` zustand persist v1→v2 migrate + 5+ API 모듈 + 12+ TanStack Query hook + 7 컴포넌트 props + MSW handler/테스트 mock 정합) / Part B P2-2 RuntimeFlagsIndicator(AppHeader 로고 옆 dot LED + 라벨 + decideDisplayState 우선순위) / Part C P1-3 useStompReconnect hook(STOMP client.onConnect chain 후킹 + localStorage anchor + 24h fallback + count>0 backfill + 분기 토스트 + 중복 회피 in-flight flag). typecheck/build/test 209 tests GREEN |

### 2-4. v3.7.0 cut + baseline init (2026-05-19 KST)

| PR | 종류 | 요약 |
|----|------|------|
| (가칭 #future-release) | release | **release: v3.7.0 — 시스템 점검 + Phase 보강 + 운영 헌법 진화 baseline (W4 진입 0)** — orchestrator release commit + CHANGELOG row + master §10 ADR 인덱스 0085·0086·0087·0088·0089·0090 갱신 + release.sh 풍부 합성 (ADR 0052 §X 정합) + README 매트릭스 자동 갱신 sync-readme-releases.mjs (PR #369 정합) |
| (가칭 PR-36) | chore(baseline) | **chore(baseline): v3.7.0 init — 폴더 + CHANGELOG 4 slot prepend (PR-36)** — orchestrator init (`_workspace/baseline/v3.7.0/` 폴더 + README skeleton + 7 파일 인덱스 + CHANGELOG sub-agent slot 4 anchor 사전 prepend — architect·backend-core·market-data·frontend) |
| (현 PR) | architect | **docs(baseline): PR-36 architect — v3.7.0 baseline 4 파일 (README + 01-architecture + 06-history + 07-policy)** — 본 PR |

---

## §3. ADR 시계열 매트릭스 (65 → 90 신규 25건 + amend 2건 + Superseded 2건)

`_workspace/arch/adr/README.md` 인덱스 정합.

### 3-1. v3.6.x patch 신규 ADR 16건 (v3.6.0 cut 직후 → v3.6.19)

| ADR | 상태 | 사이클 | 핵심 결정 |
|-----|------|--------|----------|
| 0069 | Accepted | v3.6.1 | SettingsModal vs SetupWizard 책임 분리 + 공통 입력 컴포넌트 추출 |
| 0070 | Accepted | v3.6.2 | T-RECON + T-NOTIFY 묶음 spec(Flyway V25·V26·V27 + Kafka `reconcile.drift.v1`·`reconcile.run.v1`·`killswitch.engaged.v1` 신규) |
| 0071 | Accepted | v3.6.3 | T-SC Screener (multi-criteria 4기준 fixed weights + Redis cache 30m + 옵션 영속 V28) |
| 0072 | Accepted | v3.6.4 | T-J + T-PERF 묶음 (trading_journal V29 + performance_summary·pnl_daily V30 + DailyPerformanceJob 00:30 KST cron) |
| ~~0073~~ | **Superseded by 0075** | v3.6.5 analysis | T-NEWS 뉴스·공시 실시간 인입 트랙 feasibility 분석 |
| 0074 | Accepted | v3.6.5 (재발행) | SettingsModal 풀세트 (KIS 키 변경 + KillSwitch 수동 토글 + AI 키 카테고리) |
| 0075 | Accepted | v3.6.7 | T-NEWS 트랙 진입 spec/plan (DART + Naver + V32·V33 + Kafka 2 토픽). ADR 0073 supersede |
| 0076 | Proposed | v3.6.9 patch | KIS WS NXT/시간외 시세 sync 표준 정합 (Phase 1·2·3 채택 완료 — 분석 사이클 후속 별도 트랙) |
| 0077 | Proposed | v3.6.11 hotfix | OPSP8996 ERROR 재발 deep dive (86건/1시간) — 자격증명 미등록 + MOCK type 시세 connect 정합 결함 + retry counter logic 결함 |
| 0078 | Proposed | v3.6.9 | 내부 자산 추출 전략 + Plugin Independence 제약 |
| 0079 | Accepted | v3.6.10 | 마스터 시각화 HTML 표준화 (`scripts/master-index.mjs` + `/master-index` slash + release.sh 통합) |
| ~~0035~~ | **Superseded by 0080** | v3.6.16 | 에이전트별 모델 차등 + 에스컬레이션 |
| 0080 | Accepted | v3.6.16 | 전체 에이전트 Opus 4.7 통일 (W3 종착 + W4 진입 직전 단계 sonnet 패턴화 추론 risk 회피). ADR 0035 supersede |
| 0081 | Accepted (v3.6.17 격상) | v3.6.14 Proposed → v3.6.17 | T-ACCT 계좌 잔고·예수금 endpoint + UI |
| 0082 | Proposed | v3.6.15 | T-MAN 사용자 수동 주문 발행 트랙 (자본 path 단일 LOCK 재사용) |
| 0083 | Accepted (v3.6.15 격상) | v3.6.14 Proposed → v3.6.15 | indicator-impl 라이브러리 모듈 분리. ADR 0054 패턴 1:1 |
| 0084 | Accepted (v3.6.16 P0 baseline) | v3.6.16 Proposed → v3.6.16 P0 마감 | 주문 path 안정성 P0 4건 마감 (hashkey + PENDING_RECONCILE + WS race + DLQ). P1·P2 잔류 |

### 3-2. v3.6.17·18·19 사이클 페르소나·차트 영역 신규 ADR 3건

| ADR | 상태 | 사이클 | 핵심 결정 |
|-----|------|--------|----------|
| 0085 | **Accepted (v3.6.17 격상 + v3.6.19 §Persona amend)** | v3.6.17 Proposed → v3.6.17 Accepted + v3.6.19 amend | **chart-curator 신규 에이전트 영입 (8번째 sub-agent) — TradingView LWC v4 차트 + 캔들 데이터 SLA·정합성 단일 owner · 보수적 시니어 백엔드 개발자 "안정빌드" 페르소나** |
| 0086 | **Accepted (v3.6.17 + v3.6.18 §11·§12·§13 amend)** | v3.6.17 Proposed → v3.6.17 Accepted + v3.6.18 amend | **T-CC Phase 2 — 자동 backfill 워커 + 갭 검출 + 데이터 햇징 룰 매트릭스 13건. Phase 2-A 10건 + Phase 2-B §11(KIS raw 사전 ASC 정규화) + §12(4축 active gap detection) + §13(LWC dispose race fix). 27 단위 테스트 ALL GREEN** |
| 0087 | **Proposed** | v3.6.19 | **Sub-agent 페르소나 매트릭스 — chart-curator(보수, 🔴/🟡/🔵) ↔ backend-core(진보, 💡/🔄/✨) 짝 시스템. Phase 1 dogfooding evidence 누적 후 Accepted 격상 예정** |

### 3-3. v3.7.0 사이클 신규 ADR 3건 (시스템 점검 → Phase 보강)

| ADR | 상태 | 발행 PR | 결정자 | 종류 | 핵심 결정 |
|-----|------|---------|--------|------|----------|
| **0088** | **Proposed (Phase 1 backend-core 합류 마감 — Accepted 격상 예정 v3.7.x 후속)** | #558·#559 plan / #565 backend-core 본격 | architect + backend-core | persistence-shared 모듈 baseline | **persistence-shared 라이브러리 모듈 신설 — Outbox Repository wiring 결함 fix.** 옵션 B 채택(root 직속 Kotlin + Spring Context + JPA, Boot main 0, 컨테이너 0 — ADR 0054·0083 패턴 1:1). 어댑터 9 파일 git mv(`api/persistence/{order,notification,stomp}` → `persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/{order/outbox,notification/outbox,stomp}/`) + 패키지 rename + Gradle 4 파일(`settings.gradle.kts` include row + `persistence-shared/build.gradle.kts` 신설 + `api/build.gradle.kts`·`consumer/build.gradle.kts` 의존 row). Flyway 권위 api 단독 유지 LOCK. **운영 흐름 작동률 0% → 100% 회복** (consumer ObjectProvider always empty 결함 영구 차단). qa cross-validation evidence + 8 sub-agent system audit 5 evidence 통합 매트릭스 |
| **0089** | **Proposed (Phase 2 frontend 머지 합류 마감 — Accepted 격상 예정 v3.7.x 후속)** | #549 frontend audit evidence / #573 backend / #575 frontend | architect + backend-core + frontend | userId 타입 정합 baseline | **userId 타입 정합 — backend·frontend cross-cutting string ↔ number 영구 차단.** 옵션 A 채택(backend·frontend 모두 `number`(Long → JS number) 통일). DB BIGINT(`user_account.id`) → JS number 자연 정합 + `Number.MAX_SAFE_INTEGER` 2^53-1 안전권 + MCP future spec(ADR 0027) JSON schema int64 baseline. Phase 1 backend-core DTO 1:1 정합 검증 + Phase 2 frontend 5+ 파일 type only edit + zustand persist v1→v2 migrate + 12+ TanStack Query hook + 7 컴포넌트 props + MSW handler/테스트 mock 정합 |
| **0090** | **Proposed (Phase 1 market-data + Phase 2 chart-curator 머지 합류 마감 — Accepted 격상 예정 v3.7.x 후속)** | #554 chart-curator audit evidence / #571 market-data Phase 1 / #574 chart-curator Phase 2 | architect + market-data + chart-curator | `instrument_status_history` baseline | **`instrument_status_history` 테이블 신설 — chart-curator §12 4축 axis 2·3 시점 정밀도 향상.** Flyway V42 `instrument_status_history(instrument_code, status, effective_from, effective_until, reason, recorded_at)` + 인덱스 2(`(code, from DESC)` + 활성 partial) + CHECK constraint(`NORMAL/TRADING_HALT/LIQUIDATION/DELISTED`). 추가만 LOCK(history immutability). 운영 path market-data Phase 1(`InstrumentStatusHistoryPort` + adapter + `InstrumentMasterRefreshJob` cron 보강 + backfill 1회 + audit_trail `INSTRUMENT_STATUS_CHANGED`) + chart-curator Phase 2(`CandleGapDetectorAdapter.passesActiveGapAxes()` history at ts lens 통합 + unit test 5건). KIS endpoint 신규 호출 0 LOCK + AI 호출 0 + mock 합성 0 |

### 3-4. v3.7.0 사이클 ADR amend 2건

| ADR | 상태 | amend 사유 |
|-----|------|-----------|
| **0037** | **Accepted (amend)** | P1-4 spec drift "20 req/s" vs "초당 10건" 15+ 위치 정정 + Bucket4j 8.x + Redis backend 단일 bucket 채택(옵션 A) + EGW00201/429 backoff 매트릭스 정밀화 + Circuit Breaker 5분 OPEN |
| **0085** | **Accepted (§2-1-extended amend)** | chart-curator vs frontend 책임 영역 명확화 매트릭스 LOCK. 파일 경로별 단일 owner 매트릭스 LOCK (`frontend/src/components/chart/*` + `SymbolChart`·`LightweightChart`·`IndicatorOverlay`·`PatternMarker` + `EquityCurveChart` + `*Chart*` performance) |

### 3-5. v3.7.0 사이클 운영 헌법 진화 (architect 단독 영역)

| § | 상태 | 사유 |
|---|------|-----|
| **§1-A-extended** | **신설** | extensions.worktreeConfig=true 의무 + worktree-local config 발행 의무 + `scripts/setup-worktree-config.sh` Step 2 헬퍼 + 8 sub-agent author 정합 영구 LOCK. root cause: git default `extensions.worktreeConfig=false` 상태에서 worktree `.git/config` 공유 → 한 worktree config 호출이 main 흡수 + 휘발성 전파 → author 결함 PR 10+건 누적 evidence |
| **§2-3** | **신설** | 빈 wrapper 디렉토리 `rmdir .claude/worktrees/agent-*/` 명시 (cache 잔재 회수) |
| **§2-4** | **신설** | `worktree-agent-*` ref 별도 force delete 명시 (`git update-ref -d refs/heads/worktree-agent-<id>`) |
| **§2-5** | **신설** | 사이클 시작 자동 점검 4 항목 (task 파일·worktree list·빈 wrapper·임시 브랜치) |

---

## §4. PR ↔ ADR ↔ 기능 매핑 매트릭스 (v3.7.0 사이클 핵심)

| 영역 | PR | ADR | 기능 | 매핑 |
|------|----|-----|------|-----|
| 시스템 점검 | #548·#549·#550·#551·#552·#553·#554·#555 | 0088·0089·0090 candidates 도출 | 8 sub-agent audit 매트릭스 | 8 sub-agent audit → 26+ 의제 → ADR 신설 3건 + amend 2건 baseline |
| P0-A persistence-shared | #558·#559·#565 | **0088 Proposed** | persistence-shared 모듈 + Outbox 어댑터 git mv | 자본 흐름 작동률 0% → 100% 회복 |
| P0-B (hotfix) | #557 | ADR 0086 §11 정합 | api `parseKisDailyOutput2` ASC 정규화 | KIS raw 사전 ASC 정규화 (헌법 §3) |
| P0-C 운영 헌법 §1-A-extended | #558·#559 | — | 헌법 §1-A-extended + `setup-worktree-config.sh` | 8 sub-agent author 정합 영구 LOCK |
| P1-3 STOMP unread/resync | #573·#575 | — | `GET /api/stomp/events/unread`·`GET /api/stomp/events` + `useStompReconnect` hook + `localStorage stomp:lastSeenAt:${userId}` + 24h fallback + 100건 경고 + in-flight flag | 사용자 재접속 시 누락 이벤트 backfill 영구 LOCK |
| P1-4 Bucket4j-Redis | #564·#568·#571 | **0037 amend (Accepted)** | `KisRateLimiterPort` + Bucket4j 8.10.1 + Redis backend 단일 bucket LOCK + EGW00201/429 backoff + Circuit Breaker 5분 OPEN | KIS API Rate Limit 강제 (초당 10건) 실 구현 정착 |
| P1-5 LastCandleAtMonitor KDoc | #561 | ADR 0086 §11·§12·§13 cross-ref | §11·§12·§13 cross-ref + §12 폐기 룰 vs SLA watchdog 임계치 혼동 차단 | 보수 페르소나 lens — 운영자 혼동 영구 차단 |
| P1-5 MDC traceId | #573 | — | `OrderRouterKafkaListener` MDC traceId injection + Kafka header `X-Trace-Id` | 자본 path 12 step 전체 추적성 LOCK |
| P2-1 userId 통일 | #549·#573·#575 | **0089 Proposed** | backend Kotlin Long 유지 + frontend 5+ API 모듈 + 12+ TanStack Query hook + 7 컴포넌트 props 정합 + zustand persist v1→v2 migrate + MSW handler/테스트 mock 정합 | DB BIGINT → JS number 자연 정합 + MCP future spec int64 baseline |
| P2-2 RuntimeFlags | #573·#575 | — | `GET /api/runtime/flags` + `RuntimeFlagsDto` + `useRuntimeFlags` hook + `RuntimeFlagsIndicator` 컴포넌트 + `decideDisplayState` 우선순위 | 사용자 현재 모드(MOCK·REAL·KillSwitch) 즉시 인지 baseline |
| P2-3 prompt-catalog cleanup | #562 | — | H1·H2·H3·H4 묶음 prompt-catalog 드리프트 정정 + dead config 정리 | AI 호출 0 LOCK 유지 (W4 진입 0 정합) |
| P2-4 backend-core 진보 페르소나 dogfooding | #570 | **0087 Proposed evidence #3** | `StrategyJpaAdapter` DRY 추출 + `Signal.HOLD` 죽은 ref + `!!` 9 자본 path 가드 | 페르소나 cross-review 운영 evidence + Phase 1 dogfooding |
| P2-5 instrument_status_history | #554·#571·#574 | **0090 Proposed** | Flyway V42 + `InstrumentStatusHistoryPort` + adapter + `InstrumentMasterRefreshJob` cron 보강 + `passesActiveGapAxes()` history at ts | chart-curator §12 4축 axis 2·3 시점 정밀도 향상 |
| 페르소나 운영 evidence | #551·#561·#568·#570·#573·#574 | 0085·0087 dogfooding | backend-core 진보 4 cycle + chart-curator 보수 3 cycle | Phase 1 dogfooding 누적 → ADR 0087 Accepted 격상 candidates |

---

## §5. 사용자 결정 누적 매트릭스 (v3.7.0 사이클 신규)

| 의제 | 사용자 결정 | 정합 ADR/헌법 |
|------|------------|---------------|
| W4 진입 시점 | "W4 진입 안 함" 명시 (시스템 점검부터 차근차근) | T-AI·T-MCP 트랙 v3.7.0 진입 0 LOCK |
| persistence-shared 모듈 결정 | 옵션 B 채택 (라이브러리 jar 신설) | ADR 0088 Proposed |
| userId 타입 통일 결정 | 옵션 A 채택 (backend·frontend 모두 number) | ADR 0089 Proposed |
| Bucket4j 옵션 결정 | 옵션 A 채택 (Bucket4j-Redis 단일 bucket) | ADR 0037 amend Accepted |
| chart-curator 페르소나 | 보수적 시니어 백엔드 개발자 "안정빌드" | ADR 0085 §Persona amend |
| backend-core 페르소나 | 옵션 A 채택 (진보적 시니어 "리팩토링맨") | ADR 0087 Proposed |
| 다른 6 sub-agent 페르소나 | 보류 (architect·market-data·strategy-engine·ai-engineer·frontend·qa) | ADR 0087 §13 후속 예정 |
| 운영 헌법 §2 4단계 보강 | "worktree 잔재 + 로컬 브랜치 잔뜩" 비판(2026-05-19 KST) 정합 | §2-3·§2-4·§2-5 신설 |
| extensions.worktreeConfig 의무 | 라운드 3 evidence 정합 | §1-A-extended 신설 |

---

## §6. v3.6.0 → v3.7.0 누적 통계 매트릭스

| 지표 | v3.6.0 | v3.7.0 | 증감 |
|------|--------|--------|------|
| PR # 누적 | #376 | #575+ (cut 시점) | +199+ |
| Patch 누적 | 0 | 19 (v3.6.1~v3.6.19) | +19 |
| Minor 누적 | 1 (v3.6.0) | 1 (v3.7.0 cut) | +1 |
| ADR 수 | 65 | 90 | +25 |
| ADR 신규 (v3.6.x patch + v3.7.0) | — | 22 | +22 |
| ADR amend (v3.7.0) | — | 5 (0037·0085·0086 v3.6.18·v3.6.19 + 0085 v3.6.19) | +5 |
| ADR Superseded | 0 | 2 (0035 → 0080 · 0073 → 0075) | +2 |
| JVM 모듈 | 5 | **7** | +2 (indicator-impl + persistence-shared) |
| sub-agent 수 | 7 | **8** | +1 (chart-curator) |
| sub-agent 페르소나 | 0 | **2** | +2 (chart-curator 보수 + backend-core 진보) |
| Flyway 버전 | V24 | V42+ | +18+ |
| Kafka 토픽 (활성) | 19 | 23+ | +4+ |
| STOMP 토픽 | 6+ | 8+ | +2+ |
| Redis namespace | + idem:order | + 5+ namespace | +5+ |
| REST endpoint | 약 59 | 약 80+ | +20+ |
| Frontend 라우트 | 12 | 16+ | +4+ |
| Domain port | 27 | 35+ | +8 |
| W3 트랙 마감 | 4 (T-BT·T-SIG·T-RISK·T-O) | 9+ (+ T-RECON·T-NOTIFY·T-SC·T-J·T-PERF + 기타 W3 보강) | +5+ |
| Domain port 신규 | — | 8 (KisRateLimiterPort·KisBalanceQueryPort·CandleGapDetectorPort·InstrumentStatusHistoryPort·Screener·Journal·Performance·News) | +8 |
| 운영 헌법 § | 7 | **7 + §1-A-extended** | +1 § |
| 운영 헌법 §2 단계 | 4 | **6** (§2-3·§2-4·§2-5 신설) | +2 |
| 시스템 점검 audit cycle | 0 | **1 (8 sub-agent 병렬)** | +1 |
| 페르소나 dogfooding cycle | 0 | **7** (backend-core 4 + chart-curator 3) | +7 |
| **운영 흐름 작동률** | 100% (T-O 마감) | **0% → 100% 회복** (P0-A persistence-shared) | LOCK |
| AI 호출 (자동매매 본체) | 0 | **0 LOCK 유지** (W4 진입 0) | 0 |
| GitHub Actions workflow | 0 + release.sh | 0 + release.sh + master-index.mjs + setup-worktree-config.sh | +2 헬퍼 |
| 외부 코드 리뷰 흡수 | — | 13 코멘트 → ADR 0084 P0 마감 v3.6.16 | LOCK |
| 외부 통합 (활성) | 5 (KIS REST·WS + DART + Anthropic + Yahoo dep) | 6 (+ Naver Open API) | +1 |
| dogfooding 결함 fix cycle | 1 (V18 ADR 0042) | 0 (정밀 명세 LOCK 효과 지속) | -1 LOCK |
| hotfix PR | 1 | 1+ (#557 api ASC 정규화) | +1 |

---

## §7. v3.7.0 baseline 사이클 핵심 메시지

**시스템 점검 + Phase 보강 + 운영 헌법 진화 baseline (W4 진입 0)** — v3.6.0 T-O 마감 → v3.6.x 19 patch 누적(T-RECON·T-NOTIFY·T-SC·T-J·T-PERF·T-NEWS·T-MAN·T-ACCT·T-CC·indicator-impl·OPSP8996·외부 코드 리뷰·master HTML·페르소나) → v3.7.0 (시스템 점검 8 sub-agent audit + persistence-shared 모듈 + userId 정합 + instrument_status_history + 운영 헌법 §1-A-extended·§2 4단계 → 6단계). **W4(T-AI) 진입 0** — 사용자 명시 정합. v3.7.0 = W3 자동매매 본체 안정화 LOCK + 8 sub-agent hierarchy + 7 모듈 매트릭스 + 페르소나 짝 시스템 + 운영 헌법 진화 종합 LOCK 사이클. 다음 v3.8.0 minor 후보 = W4(T-AI·T-MCP) 진입 마감 또는 v3.7.x patch 누적 후 W5 진입. AI 호출 0 LOCK 지속.
