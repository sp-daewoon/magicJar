# 07 — MD 룰 정책 진화 (v3.6.0 → v3.7.0)

> **작성자**: architect (PR-36 baseline v3.7.0)
> **시점**: 2026-05-19 v3.7.0 태그 컷 직후
> **사용자 명시 의도**: "우리 md 룰 정책의 변화로 더 나아지고 있다는 점을 어필 할 수 있는 목록" (v3.1.0 baseline 도입 — v3.7.0까지 누적 갱신)

본 문서는 **v3.6.0 → v3.7.0 정책 진화**를 추적한다. 추측 0 — 모든 룰은 운영 헌법(`_workspace/arch/agent-charter.md`) 7 § + **§1-A-extended (v3.7.0 신설)** + Living Policy(`docs/architecture/policies/`) + ADR 0030·0036·0049(Superseded)·0050·0051·0052·0053·0079·0080·0085·0086·0087·0088·0089·0090 + backlog 처리 매트릭스로 추적 가능.

각 § 본문은 **도입 전 / 도입 후 / 효과 / 사례 PR** 4단 구조다 (v3.1.0~v3.6.0 baseline 정합).

**v3.6.0 baseline 보존**: §1~§5 정책 진화 (v3.6.0 4건 + 누적 v3.1.0~v3.6.0 25건) + §6~§7 다음 Wave + 누적 매트릭스는 v3.7.0 사이클에서 **갱신 일부** (W3 보강 트랙 + 시스템 점검 + Phase 진화 매트릭스 보강).

**v3.7.0 사이클 정책 진화 7건** (v3.6.x patch 누적 + v3.7.0 baseline cut):
1. **운영 헌법 §1-A-extended 신설** — extensions.worktreeConfig=true 의무 + worktree-local config 발행 의무 + `scripts/setup-worktree-config.sh` 헬퍼 Step 2. 8 sub-agent author 정합 영구 LOCK.
2. **운영 헌법 §2 4단계 → 6단계 진화** — §2-3 빈 wrapper rmdir + §2-4 worktree-agent-* force delete + §2-5 사이클 시작 자동 점검 4 항목. 사용자 명시 비판("worktree 잔재 + 로컬 브랜치 잔뜩") 정합.
3. **ADR 0080 — 전체 에이전트 Opus 4.7 통일 (ADR 0035 supersede)** — W3 종착 + W4 진입 직전 단계 sub-agent 코드 한 줄 수정도 룰 본체·자본 흐름 결합으로 진화 → sonnet 패턴화 추론 risk 회피.
4. **ADR 0085 chart-curator 영입 (8번째 sub-agent) + 보수적 페르소나 amend** — TradingView LWC v4 + 캔들 데이터 SLA·정합성 단일 owner. 영입 직후 2 사이클 결함 5건 누적 evidence → 보수적 페르소나 LOCK으로 root cause 영구 차단.
5. **ADR 0087 짝 페르소나 매트릭스 신설** — backend-core 진보적 페르소나(리팩토링맨) 부여 + chart-curator(보수) ↔ backend-core(진보) 짝 시스템 + cross-review 운영 룰(자본 path 직결 → chart-curator 1차 lens / 인프라 일반 → backend-core 1차 lens).
6. **ADR 0088 persistence-shared 모듈 신설** — Outbox Repository wiring 결함 fix(consumer ObjectProvider always empty). 7 모듈 매트릭스 + 운영 흐름 작동률 0% → 100% 회복.
7. **ADR 0079 마스터 시각화 HTML 표준화 (v3.6.10)** — `_workspace/master.html` + `scripts/master-index.mjs` + `/master-index` slash + `scripts/release.sh` 통합. 12 카테고리 두 축 + banner.

추가로 **AI 호출 0 LOCK 유지 정책 진화** — W4 진입 0 LOCK(사용자 명시 정합). T-NEWS도 룰 기반 키워드 매칭만(AI 호출 0 LOCK §B-3). prompt-catalog 드리프트 정정(PR #562) + dead config 정리. 자동매매 본체 영역 AI 호출 0 grep 검증 6 디렉토리 의무 LOCK 유지.

---

## §0. 운영 헌법 8 § (v3.7.0 진화) — §1-A-extended 신설

운영 헌법 7 §는 v3.1.0 PR-26(#224)에서 통합 발행된 후 v3.2.0·v3.2.2·v3.3.0·v3.4.0·v3.5.0·v3.6.0 사이클에서 보존 + **v3.7.0 사이클에서 §1-A-extended 신설** (worktreeConfig=true 의무 + worktree-local config 발행 의무).

| § | 제목 | v3.7.0 사이클 검증 |
|---|------|---------------------|
| §0 | architect=조정자, 결정 책임 | 모든 ADR 0085·0086·0087·0088·0089·0090 발행 사이클 architect 단독 |
| §1 | task.mjs 4단계 의무 | 모든 sub-agent 디스패치 사이클(시스템 점검 8 + 라운드 1·2·3·4·5 12+ PR) start·check·done 4단계 호출 — 누적 task archive 정합 |
| **§1-A-extended** | **신설 — worktreeConfig=true 의무** | **v3.7.0 사이클 신설** — root cause(라운드 3 evidence): git default `extensions.worktreeConfig=false` 상태에서 worktree `.git/config` 공유 → 한 worktree config 호출이 main 흡수 → 8 sub-agent author 결함 PR 10+건 누적(PR #514·#525·#535·#537·#541·#546·#551·#552·#553·#555). LOCK: (1) main `.git/config` `extensions.worktreeConfig=true` 적용 (`scripts/setup-worktree-config.sh` Step 2 헬퍼) (2) worktree 진입 즉시 4 명령 LOCK(`git config user.email` + `git config user.name` + `git config --worktree user.email` + `git config --worktree user.name`). 헬퍼 idempotent + 검증 GREEN/FAIL 출력 |
| §2 (4단계 → 6단계) | 머지 후 정리 | v3.7.0 사이클 §2-3·§2-4·§2-5 신설. 사용자 명시 비판(2026-05-19 KST "지금 또 헌법 무시한거발견했다 !!! worktree 잔재 + 로컬 브랜치 잔뜩") 정합. (1) PR 머지 후 즉시 `git worktree remove --force` (2) `git branch -D agent/<agent>/<task>` 정리 (3) §2-3 빈 wrapper rmdir `.claude/worktrees/agent-*/` (cache 잔재 회수) (4) §2-4 `worktree-agent-*` ref force delete (`git update-ref -d refs/heads/worktree-agent-<id>`) (5) §2-5 사이클 시작 자동 점검 4 항목(task 파일·worktree list·빈 wrapper·임시 브랜치) (6) task.mjs done |
| §3 | KIS-first raw fetch 의무 | **v3.7.0 사이클 강화 영역** — KIS REST 사전 ASC 정규화(§11 ADR 0086 amend) + Bucket4j-Redis acquire 합류(P1-4 ADR 0037 amend) + ADR 0086 §12 4축 active gap detection raw 인용 + ADR 0090 instrument_status_history 정합. Living Policy `docs/architecture/policies/kis-first-raw-fetch.md` 5단계 의무 절차 정합 |
| §4 | CHANGELOG 슬롯 채번룰 | PR-36 baseline 사이클 anchor 4건 사전 prepend (architect/backend-core/market-data/frontend). 시스템 점검 8 sub-agent + 라운드 1·2·3·4·5 모든 anchor in-place edit 정합 |
| §5 | PR 생성 직전 rebase 의무 | 본 PR-36 포함 모든 사이클 적용 — 통합 빌드 fail 0건 |
| §6 | JPA Repository default fun 함정 | ADR 0088 persistence-shared 어댑터 9 파일 git mv 시점 — `OrderEventOutboxRepository`·`NotificationOutboxRepository`·`StompEventRepository` 모두 abstract만 유지 LOCK |
| §7 | sub-agent fail 분기 룰 | 시스템 점검 8 sub-agent + 라운드 1·2·3·4·5 본격 사이클 — 재디스패치 0건. **hotfix 1건만 발생(#557 api `parseKisDailyOutput2` ASC 정규화)**. modify-shared 충돌 0건 + sub-agent fail 분기 0건. 사용자 메모리 `feedback_conflict_resolution_autonomy.md` 정합 |

**baseline 정합**: v3.2.0 통합 헌법이 v3.2.2·v3.3.0·v3.4.0·v3.5.0·v3.6.0 사이클까지 완주 후 v3.7.0 사이클에서 **§1-A-extended 신설 + §2 4단계 → 6단계 진화** — 사용자 명시 비판 정합. 8 sub-agent 운영 author 정합 영구 LOCK + worktree 잔재 영구 차단.

---

## §1. ADR 0080 — 전체 에이전트 Opus 4.7 통일 (ADR 0035 supersede)

### 도입 전

v3.6.0 baseline 시점 sub-agent 모델 차등 정책 baseline:
- architect·strategy-engine·ai-engineer 3명 opus
- backend-core·market-data·frontend·qa 4명 sonnet
- 에스컬레이션 룰: 복잡도·결합 위험 발견 시 architect 또는 사용자가 opus 승격 (ADR 0035)

**드리프트 risk 누적**:
- W3 종착 + W4 진입 직전 단계에서 sub-agent 코드 한 줄 수정도 룰 본체·자본 흐름 결합으로 진화
- sonnet 패턴화 추론 risk — 학습 데이터 빈 패턴으로 회귀
- backend-core·market-data·frontend 영역에서 자본 path 인접 코드 수정 빈도 증가
- 모델 차등으로 인한 sub-agent 간 보고 품질 격차

### 도입 후

ADR 0080 Accepted (v3.6.16 — 2026-05-18) — **전체 에이전트 Opus 4.7 통일** + ADR 0035 supersede:

- architect · backend-core · market-data · strategy-engine · ai-engineer · frontend · qa 7명 모두 **opus 4.7 1M**
- v3.6.17 ADR 0085 chart-curator 영입 시 chart-curator도 opus 4.7 1M로 영입 → 8 sub-agent 모두 opus 4.7 통일
- 토큰 비용 약 2배 증가 사용자 명시 수용

### 효과

- W3·W4 사이클 sub-agent 보고 품질 매트릭스 균질화
- 자본 path 인접 코드 수정 시 모든 sub-agent가 동일 수준 추론 → 결합 위험 영구 차단
- 페르소나 매트릭스 도입 시(ADR 0085·0087) 보수·진보 lens 모두 opus 4.7 baseline 위에서 운영 가능 → 페르소나 cross-review 실효성 LOCK

### 사례

- v3.6.16 PR(외부 코드 리뷰 13 코멘트 흡수) — backend-core opus 4.7 1M로 13 코멘트 매트릭스 정리
- v3.7.0 시스템 점검 8 sub-agent 병렬 dispatch — 8 sub-agent 모두 opus 4.7 baseline 위에서 26+ 의제 도출

---

## §2. ADR 0085 — chart-curator 영입 (8번째 sub-agent) + 보수적 페르소나 amend

### 도입 전

v3.6.0 baseline 시점 7 sub-agent hierarchy (architect·backend-core·market-data·strategy-engine·ai-engineer·frontend·qa). 차트·캔들 영역은 3 영역(market-data·strategy-engine·frontend) 교차 점유.

**드리프트 결함 evidence**:
- 한국석유(004090) 일봉 last=2025-12-24 ≈5개월 stale vs 1분봉 last=2026-05-18 15:19 정상 — 차트·캔들 단일 owner 부재
- 차트 컴포넌트 결함 책임 분기 모호 (frontend? market-data? strategy-engine?)
- 캔들 적재 워커·갭 검출·자동 backfill·MTF 정합성·corporate action adjustment 단일 owner 부재

### 도입 후

ADR 0085 Accepted (v3.6.17 — 2026-05-19) — **chart-curator 신규 에이전트 영입 (8번째 sub-agent)** + v3.6.19 §Persona amend:

- TradingView LWC v4 차트 컴포넌트(`frontend/src/components/chart/*`) + 캔들 layer 단일 owner
- 영역 매트릭스 LOCK: chart-curator(`frontend/src/components/chart/*` + `SymbolChart`·`LightweightChart`·`IndicatorOverlay`·`PatternMarker` + `EquityCurveChart` + `*Chart*` performance + `CandleGapDetector` + `CandleBackfillWorker` + `LastCandleAtMonitor` + `InstrumentStatusHistory` Phase 2 lens) ↔ frontend(차트 외 일반 UI)
- **v3.6.19 §Persona amend** — 보수적 시니어 백엔드 개발자 "안정빌드" (15년차 베테랑 · 좌우명 "동작하는 코드를 함부로 건드리지 마라" · 핵심 가치 5건: 안정성 최우선·검증된 패턴·하위호환성·점진적 변경·방어적 프로그래밍 · 출력 형식 🔴/🟡/🔵 3 등급 · Kotlin require/check/try-catch 방어 패턴)
- 페르소나 LOCK 사유: 영입 직후 첫 2 사이클 결함 5건 누적 evidence(PR #514 commit author attribution · PR #525 audit_trail JSON syntax error · PR #525 KillSwitch FK violation · PR #535 §12 "5+ 영업일 임의 임계치" 잘못된 제안 · PR #537·#541 author 재발) → 신입 자율 추론 부작용 → 보수적 페르소나 LOCK으로 root cause 영구 차단

### 효과

- 차트·캔들 영역 결함 책임 분기 영구 LOCK (single owner ADR 0085 §책임 영역 정합)
- 자본 흐름 직결 영역 보수 lens 영구 LOCK (페르소나 매트릭스 cross-review)
- 한국석유 dogfooding cycle evidence 누적 → ADR 0086 햇징 룰 13건 + ADR 0090 instrument_status_history 후속 발행

### 사례

- v3.6.17 PR #514 T-CC Phase 1 — `LastCandleAtMonitor` + `LastCandleAtBootstrapRunner` + admin endpoint
- v3.6.17 PR #525 T-CC Phase 2-A — `CandleGapDetectorPort`·`CandleGapDetectorAdapter` + `CandleBackfillWorker` + 햇징 룰 10건 LOCK + 27 단위 테스트 GREEN
- v3.6.18 PR #538·#540·#541 — 햇징 룰 §11·§12·§13 amend
- v3.7.0 시스템 점검 PR #554 — chart-curator audit + ADR 0090 candidates 도출
- v3.7.0 라운드 5 PR #574 — ADR 0090 Phase 2 `passesActiveGapAxes()` history at ts 통합

---

## §3. ADR 0087 — 짝 페르소나 매트릭스 (chart-curator 보수 ↔ backend-core 진보)

### 도입 전

v3.6.17~v3.6.18 사이클 시점 chart-curator만 보수 페르소나 (ADR 0085 §Persona amend). 다른 7 sub-agent 페르소나 부여 0. backend-core 영역에서 자본 path 인접 코드(`OrderRouterKafkaListener`·`KisOrderHttpAdapter`·`StrategyJpaAdapter`·`KillSwitchRule`) 보호 lens는 chart-curator 단독 cross-review.

**부담 evidence**:
- chart-curator는 자본 흐름 직결 single owner — 인프라 일반 영역(Spring·controller·port·Flyway·DTO·일반 endpoint)까지 cross-review 부담 분산 필요
- backend-core 운영 evidence(PR #510 InfrastructureBeans 모듈화 · #524 AccountSummaryDto 함수형 매핑 · #532 KillSwitchRule strategy pattern 리팩토링) — 개선 지향·최신 패턴 fit

### 도입 후

ADR 0087 Proposed (v3.6.19 — 2026-05-19) — **Sub-agent 페르소나 매트릭스 — 짝 시스템**:

- **backend-core 진보 페르소나** — 시니어 백엔드 개발자 "리팩토링맨" (15년차 · 좌우명 "레거시는 빚이다. 이자가 붙기 전에 갚아라" · 핵심 가치 5건: 코드 품질 최우선·최신 패턴 적용·DRY 원칙·테스트 주도·자동화 지향 · 출력 형식 💡 개선 제안 / 🔄 리팩토링 / ✨ 모던 패턴 적용 3 등급 · Kotlin `?.let`·`?.takeIf`·`?.also` 함수형 체이닝 + `sealed interface` 컴파일 타임 안전성)
- **chart-curator 보수 페르소나** (ADR 0085 amend) ↔ **backend-core 진보 페르소나** (ADR 0087) 짝 시스템 LOCK
- **cross-review 운영 룰**:
  - **자본 path 직결 영역** (캔들·주문·시그널·KIS 어댑터 · `OrderRouterKafkaListener`·`KisOrderHttpAdapter`·`CandleGapDetector`·`CandleBackfillWorker`) → **chart-curator 1차 lens** (보수, 🔴/🟡/🔵, require/check 방어 패턴)
  - **인프라 일반** (Spring·controller·port·Flyway·DTO·일반 endpoint) → **backend-core 1차 lens** (진보, 💡/🔄/✨, `?.let`·`?.takeIf`·sealed class·DRY·함수형 체이닝)
- 다른 6 sub-agent(architect·market-data·strategy-engine·ai-engineer·frontend·qa) 페르소나 부여 보류 (사용자 결정 의제)

### 효과

- 자본 path 보호 lens + 인프라 진화 lens 짝 시스템 LOCK → 자본 흐름 안정 + 코드 품질 진화 동시 추구
- Phase 1 dogfooding evidence 누적 7 cycle GREEN:
  - backend-core 진보 4 cycle (PR #551 audit · #568 Bucket4j 실 구현 · #570 DRY refactor · #573 STOMP unread/resync 신설)
  - chart-curator 보수 3 cycle (PR #554 audit · #561 LastCandleAtMonitor KDoc 보강 · #574 ADR 0090 Phase 2 history at ts)
- Phase 1 dogfooding evidence 누적 후 Accepted 격상 path 보존 (v3.7.x 후속)

### 사례

- v3.7.0 시스템 점검 PR #551 backend-core 진보 페르소나 첫 dogfooding — audit + DRY refactor candidates 도출
- v3.7.0 PR #570 — `StrategyJpaAdapter` DRY 추출 + `Signal.HOLD` 죽은 ref 정리 + `!!` 9 자본 path 가드 (진보 페르소나 패턴 + 보수 lens 혼합)
- v3.7.0 PR #573 — `StompEventPersister` + `RuntimeFlagsController` 신설 (진보 페르소나 모던 패턴)

---

## §4. ADR 0088 — persistence-shared 모듈 신설 (운영 흐름 작동률 0% → 100% 회복)

### 도입 전

v3.6.x patch 사이클 시점 Outbox 어댑터 baseline:
- `api/persistence/order/OrderEventOutboxRepositoryAdapter`·`JpaRepository`·`JpaEntity` 3 파일
- `api/persistence/notification/NotificationOutbox...` 3 파일
- `api/persistence/stomp/StompEvent...` 3 파일
- 총 9 파일 api 모듈 단독 거주

**root cause 결함 evidence** (qa cross-validation + 8 sub-agent system audit 5 evidence 통합):
- consumer `ObjectProvider<OrderEventOutboxRepository>` always empty
- consumer `ObjectProvider<NotificationOutboxRepository>` always empty
- drain·retry worker 매 schedule cycle `log.warn` + skip → Kafka `order.requested` 토픽 발행 0건
- **주문 path 운영 흐름 작동률 0%**
- root cause: port(domain) GREEN + adapter(api/persistence 단독) FAIL + consumer→api 의존 0 LOCK(ADR 0004 §의존 방향) → consumer ComponentScan에 api 패키지 부재 → ObjectProvider always null
- ObjectProvider graceful skip 패턴이 모듈 경계 결함을 silent 은폐

### 도입 후

ADR 0088 Proposed (v3.7.0 — 2026-05-19) — **persistence-shared 라이브러리 모듈 신설**:

- **옵션 B 채택** (persistence-shared 라이브러리 모듈 신설) — ADR 0054(pattern-engine) + ADR 0083(indicator-impl) 패턴 1:1 재사용
- root 직속 Kotlin + Spring Context + JPA, Boot main 0, 컨테이너 0
- 운영 인지 비용 0 (sub-agent + 사용자 학습 완료 evidence v3.6.15 cut)
- **모듈 매트릭스 6 → 7 갱신** (domain · pattern-engine · indicator-impl · **persistence-shared** · api · batch · consumer)
- **어댑터 9 파일 git mv** (`api/persistence/{order,notification,stomp}` → `persistence-shared/src/main/kotlin/kr/spdaewoon/magicjar/persistence/shared/{order/outbox,notification/outbox,stomp}/`) + 패키지 rename
- **Gradle 4 파일 변경**: `settings.gradle.kts` include + `persistence-shared/build.gradle.kts` 신설 + `api/build.gradle.kts`·`consumer/build.gradle.kts` 의존 row 추가
- **잔류 api 단독 LOCK**: `SystemConfigSlackAdapter`(Settings 모달 종속) + `StompEventPersister`(@MessageMapping 정합) + `StompEventBackfillController`(REST endpoint)
- **Flyway 권위 api 단독 유지 LOCK** (ADR 0006 §마이그레이션 정합 — V 시퀀스 충돌 차단)

### 효과

- **운영 흐름 작동률 0% → 100% 회복** (주문 path 자본 흐름 차단 해소)
- consumer ObjectProvider always empty 결함 영구 차단
- Hexagonal port·adapter·use case 3계층 분리 정석 보존
- v3.7.x 후속 사이클 dogfooding evidence(주문 1건 issue → outbox row insert → drain 1 cycle ≤30s → SENT 갱신 → Kafka 발행 GREEN) 누적 후 Accepted 격상 path

### 사례

- v3.7.0 시스템 점검 PR #550·#551 — qa cross-validation evidence + backend-core 자가 진단(PR #524·#523) 운영 흐름 작동률 0% root cause 도출
- v3.7.0 PR #558·#559 architect spec — ADR 0088 Proposed + 헌법 §1-A-extended 신설
- v3.7.0 PR #565 backend-core 본격 — 어댑터 9 파일 git mv + Gradle 4 파일 + 패키지 rename + 테스트 3 파일 import 갱신 + ktlintFormat 자동 정렬 정합

---

## §5. ADR 0089 — userId 타입 정합 (backend·frontend cross-cutting string ↔ number 영구 차단)

### 도입 전

v3.6.x patch 누적 시점 cross-cutting drift 5+ 영역:
- backend-core API path: `account.ts`·`order.ts`·`watchlist.ts`·`risk.ts` → `userId: string`
- backend-core API path: `journal.ts`·`performance.ts`·`screener.ts` → `userId: number`
- frontend zustand store: `useOnboardingStore.registeredUserId: string|null`
- frontend 페이지: TradingJournalPage·PerformanceDashboardPage 기존 `Number(registeredUserId)` 캐스팅 + StrategyBuilderPage `parseInt` + CompleteStep `substring` 마스킹

**evidence**:
- 자체 추론 risk
- userId 영역별 불일치 → MCP future spec(ADR 0027) JSON schema int64 baseline 부재

### 도입 후

ADR 0089 Proposed (v3.7.0 — 2026-05-19) — **userId 타입 통일**:

- **옵션 A 채택** (backend·frontend 모두 `number`(Long → JS number) 통일)
- DB BIGINT(`user_account.id`) → JS number 자연 정합
- `Number.MAX_SAFE_INTEGER` 2^53-1 안전권
- MCP future spec(ADR 0027) JSON schema int64 baseline
- backend Kotlin Long 유지 (Jackson default 라운드트립 GREEN)
- **frontend 5+ API 모듈 + 12+ TanStack Query hook + 7 컴포넌트 props + MSW handler/테스트 mock 정합**:
  - `useOnboardingStore.registeredUserId: string|null → number|null` + zustand persist `migrate` 콜백 v1→v2 (기존 string 영속값 `Number()` 변환 + NaN fallback null)
  - `account.ts`·`order.ts`·`watchlist.ts`·`risk.ts`·`position.ts`·`signal.ts`·`strategy.ts`·`backtest.ts`·`onboarding.ts`·`types/settings.ts` DTO·함수 시그니처 `userId: string → number`
  - 페이지 사용처 정리 (`Number(registeredUserId)` 캐스팅 제거 + parseInt 제거 + `substring` → `String(num).padStart(8,'0').substring(0,4)` 마스킹 + cooldown helper 시그니처 정합)
  - 7 컴포넌트 props 정합 (`CancelOrderButton`·`BacktestForm`·`BacktestList`·`BacktestDetail`·`RiskConfigForm`·`RiskEventList`·`StrategyList`·`SignalListPanel`·`HoldingsTable`·`AccountSummaryCard`·`BalanceCard`·`KisMockStep`)
  - MSW handler·테스트 mock 정합 (`watchlist-flow.test.tsx` userId `'74683223'` → `74683223` number · `KisRealCredentialBanner.test.tsx` `userId: 'thdetest'` → `userId: 74683223` 4건)

### 효과

- cross-cutting drift 영구 차단 (`git grep "userId: string" frontend/src/` 0건 + `@PathVariable.*userId.*String` 0건 검증 의무)
- MCP future spec(ADR 0027) int64 baseline 정합
- type only edit (logic 0 변경) → 회귀 risk 0

### 사례

- v3.7.0 시스템 점검 PR #549 frontend audit — P2-F1 cross-cutting evidence 도출
- v3.7.0 PR #573 backend Phase 1 contract LOCK (backend Kotlin Long 유지)
- v3.7.0 PR #575 frontend Phase 2 본격 — 30+ 파일 type 정정 (Part A) + `pnpm typecheck` GREEN + `pnpm build` GREEN + 209 tests GREEN

---

## §6. ADR 0090 — `instrument_status_history` 테이블 신설 (chart-curator §12 4축 axis 2·3 시점 정밀도)

### 도입 전

v3.6.18 사이클 시점 ADR 0086 §12 4축 active gap detection baseline:
- 4축 매트릭스: (1) `market_calendar.is_business_day=TRUE` (2) `instrument.trading_halt=FALSE` 또는 history 부재 (3) `instrument.liquidation=FALSE` (4) `instrument.listed_at <= ts`
- 4축 모두 TRUE인데 candle row 부재 = 명확한 결함 → 자동 backfill 의무

**드리프트 risk**:
- `instrument.trading_halt`·`liquidation` flag는 **현재 시점만** 추적 — history 0
- 한국석유 dogfooding cycle: 일봉 last=2025-12-24 stale인 시점 → 그 시점 `trading_halt` 상태가 무엇이었는지 추적 불가 → false positive backfill risk
- 신규 상장·재상장·정리매매 history 추적 0

### 도입 후

ADR 0090 Proposed (v3.7.0 — 2026-05-19) — **`instrument_status_history` 테이블 신설**:

- **Flyway V42** `instrument_status_history(instrument_code, status, effective_from, effective_until, reason, recorded_at)` + 인덱스 2 + CHECK constraint(`NORMAL/TRADING_HALT/LIQUIDATION/DELISTED`)
- 추가만 LOCK (history immutability)
- **Phase 1 market-data**: `InstrumentStatusHistoryPort` + adapter + `InstrumentMasterRefreshJob` cron 보강 + backfill 1회 + audit_trail `INSTRUMENT_STATUS_CHANGED`
- **Phase 2 chart-curator**: `CandleGapDetectorAdapter.passesActiveGapAxes()` history at ts lens 통합 + unit test 5건

### 효과

- ADR 0086 §12 axis 2·3 시점 정밀도 향상 → false positive backfill risk 영구 차단
- 한국석유 dogfooding cycle cross-ref 자동 검증 path 확보
- KIS endpoint 신규 호출 0 LOCK + AI 호출 0 + mock 합성 0

### 사례

- v3.7.0 시스템 점검 PR #554 — chart-curator audit + ADR 0090 candidates 도출
- v3.7.0 PR #571 market-data Phase 1 — Flyway V42 + `InstrumentStatusHistoryPort` adapter + `InstrumentMasterRefreshJob` cron 보강
- v3.7.0 PR #574 chart-curator Phase 2 — `passesActiveGapAxes()` history at ts 통합 + 한국석유 dogfooding evidence + unit test 5건 GREEN

---

## §7. ADR 0079 — 마스터 시각화 HTML 표준화 (v3.6.10)

### 도입 전

v3.6.9 사이클 시점 마스터 인덱스 baseline:
- `docs/architecture/master-architecture.md` v2 (ADR 0030 SoT) — 텍스트 본문만
- `_workspace/INDEX.md` — 의도별 진입 경로 매트릭스 (텍스트)
- ADR·spec·backlog는 각각 별도 README/index — 통합 시각화 0

**드리프트 risk**:
- GitHub 방문자 진입 매트릭스 부재
- baseline 7 md + html 풀세트는 발행되나 master-level 시각화 0
- 12 카테고리 두 축 인지 비용 누적

### 도입 후

ADR 0079 Accepted (v3.6.10 — 2026-05-17) — **마스터 시각화 HTML 표준화**:

- `_workspace/master.html` 자동 생성 — 12 카테고리 두 축 + banner + ADR/spec/backlog 추출 자동화
- `scripts/master-index.mjs` 헬퍼 스크립트
- `/master-index` slash command
- `scripts/release.sh` 통합 (release cut 시점 자동 갱신)
- Phase 1·2·3 PR #461·#462 직렬 머지 + brainstorming 흐름 사용자 결정 8회 종합

### 효과

- GitHub 방문자 진입 매트릭스 시각화 baseline LOCK
- baseline cut 자동 갱신 정합 (v3.6.11 cut에서 첫 자동 갱신 dogfooding 성공)
- 12 카테고리 두 축 인지 비용 ↓

### 사례

- v3.6.11 cut — master.html 자동 갱신 dogfooding 성공
- v3.6.16 cut README + master.html 갱신
- v3.7.0 cut — release.sh 통합 baseline 정합 (v3.6.10 ADR 0079 도입 후 시계열 1년 가까이 안정 운영)

---

## §8. v3.7.0 사이클 누적 통계 매트릭스 (v3.1.0 baseline 이후 누적 정책 진화 32건)

| 사이클 | 정책 진화 | ADR |
|--------|-----------|-----|
| v3.1.0 | 운영 헌법 7 § 통합 발행 | 0036 |
| v3.2.0 | ADR-0050·0051·0052·0053 인프라 4건 | 0050·0051·0052·0053 |
| v3.2.2 | KIS-only 데이터 정책 | 0057 |
| v3.3.0 | Boundary fanout | 0064 |
| v3.4.0 | T-SIG 시그널 + GHA 폐기 | 0066 + ~~0049~~ |
| v3.5.0 | T-RISK 리스크 게이트 | 0067 |
| v3.6.0 | T-O 주문 실행 + 3중 게이트 자본 보호 + KIS-first raw KDoc 의무 + release 절차 보강 | 0068 |
| v3.6.1 | SettingsModal vs SetupWizard 책임 분리 | 0069 |
| v3.6.2 | T-RECON + T-NOTIFY 묶음 spec | 0070 |
| v3.6.3 | T-SC Screener spec | 0071 |
| v3.6.4 | T-J + T-PERF 묶음 spec | 0072 |
| v3.6.5 | SettingsModal 풀세트 | 0074 |
| v3.6.7 | T-NEWS 트랙 진입 | 0075 |
| v3.6.10 | 마스터 시각화 HTML 표준화 | 0079 |
| v3.6.14 | T-ACCT + indicator-impl 결함 fix | 0081·0083 |
| v3.6.15 | T-MAN + indicator-impl Accepted | 0082·0083 Accepted |
| v3.6.16 | 외부 코드 리뷰 13 코멘트 흡수 + 전체 에이전트 Opus 4.7 통일 + 주문 path P0 4건 마감 | 0080·0084 P0 |
| v3.6.17 | T-ACCT Phase 4 + chart-curator 영입 + T-CC Phase 1 + Phase 2-A 햇징 룰 10건 | 0081·0085·0086 Accepted |
| v3.6.18 | T-CC Phase 2-B + 햇징 룰 §11·§12·§13 amend | 0086 amend |
| v3.6.19 | chart-curator 보수 페르소나 amend + backend-core 진보 페르소나 부여 | 0085·0087 |
| **v3.7.0** | **시스템 점검 + Phase 보강 + 운영 헌법 §1-A-extended·§2 4단계 → 6단계 + persistence-shared 모듈 + userId 정합 + instrument_status_history** | **0088·0089·0090 + 헌법 §1-A-extended·§2 진화** |

**누적 정책 진화 32건** (v3.1.0 ~ v3.7.0). magicJar md 룰 정책 진화의 핵심 5축:

1. **운영 헌법 진화** — 7 § baseline → §1-A-extended 신설 + §2 4단계 → 6단계 진화
2. **모듈 매트릭스 진화** — 4 → 5 (pattern-engine ADR 0054) → 6 (indicator-impl ADR 0083) → **7 (persistence-shared ADR 0088 v3.7.0)**
3. **sub-agent hierarchy 진화** — 7 → **8 (chart-curator ADR 0085 v3.6.17)**
4. **페르소나 매트릭스 진화** — 0 → **2 (chart-curator 보수 ADR 0085 amend + backend-core 진보 ADR 0087) + cross-review 룰 LOCK**
5. **자본 흐름 안정 진화** — T-O 종착 노드 가동(v3.6.0) → 3중 게이트 자본 보호 LOCK + 외부 코드 리뷰 13 코멘트 흡수(v3.6.16) + persistence-shared 모듈로 운영 흐름 작동률 0% → 100% 회복(v3.7.0)

---

## §9. 다음 Wave 정책 후보 (v3.7.0 baseline 직후 — v3.8.0 minor candidate)

### 9-1. ADR 0087 Accepted 격상 (Phase 1 dogfooding evidence 누적 후)

- backend-core 진보 페르소나 4 cycle GREEN (PR #551·#568·#570·#573)
- chart-curator 보수 페르소나 3 cycle GREEN (PR #554·#561·#574)
- → ADR 0087 Accepted 격상 candidates (v3.7.x 후속)

### 9-2. ADR 0088 Accepted 격상 (운영 흐름 작동률 회복 evidence 누적 후)

- `WARN drain worker skip` 0건 LOCK + `WARN retry worker skip` 0건 LOCK 검증
- 매수 시그널 1건 → outbox row insert → drain 1 cycle ≤30s → SENT → Kafka 발행 → consumer GREEN
- → ADR 0088 Accepted 격상 candidates (v3.7.x 후속)

### 9-3. ADR 0089·0090 Accepted 격상 (Phase 2 머지 evidence 누적 후)

- ADR 0089 — frontend Phase 2 머지 PR #575 evidence + backend Phase 1 contract LOCK
- ADR 0090 — market-data Phase 1 머지 PR #571 + chart-curator Phase 2 PR #574
- → 한국석유 dogfooding cycle false positive risk 영구 차단 검증

### 9-4. 다른 6 sub-agent 페르소나 부여 결정 (사용자 결정 의제)

- architect·market-data·strategy-engine·ai-engineer·frontend·qa 페르소나 부여 보류 → 사용자 결정 의제
- 후보: market-data(KIS-first 보수 lens) · strategy-engine(룰 본체 정합 lens) · ai-engineer(W4 진입 시점 결정) · frontend(UX 일관성 lens) · qa(on-demand 0 default 정합 lens)

### 9-5. W4 진입 정책 (T-AI · T-MCP)

- 사용자 명시 "W4 진입 안 함" 정합 — v3.7.0 사이클 0
- v3.8.0 minor 후보 = W4(T-AI·T-MCP) 진입 시점 결정 (사용자 의제)
- AI 트리거 게이트 사전 발행 후보: `MAGICJAR_AI_ENABLED=true` env + 명시 동의 + 트리거 endpoint + 분석 코멘트 prompt-catalog 정합 (ADR 0026 baseline)

### 9-6. W5 진입 정책 (T-LWC · T-LEARN)

- v3.7.0 baseline 시점 0 (사용자 명시 W4 우선 또는 W5 우선 결정 의제)
- T-LWC: TradingView LWC v4 차트 + 자체 도형/주석/패턴 (ADR 0025 baseline + chart-curator single owner)
- T-LEARN: 학습형 룰 추천 + 백테스트 자동 fine-tune (사용자 의제)

---

## §10. v3.7.0 baseline 사이클 핵심 정책 메시지

**시스템 점검 + Phase 보강 + 운영 헌법 §1-A-extended·§2 진화 baseline (W4 진입 0)** — v3.6.0 T-O 마감 후 v3.6.x 19 patch 누적(W3 보강 트랙 5건 + sub-agent 영입 + 페르소나 매트릭스 + 외부 코드 리뷰 흡수) → v3.7.0 (시스템 점검 8 sub-agent audit + persistence-shared 모듈 + userId 정합 + instrument_status_history + 운영 헌법 진화). **운영 흐름 작동률 0% → 100% 회복** (P0-A persistence-shared 모듈) + **8 sub-agent author 정합 영구 LOCK** (§1-A-extended) + **worktree 잔재 영구 차단** (§2 6단계) + **자본 path 보호·인프라 진화 짝 페르소나 매트릭스** (ADR 0085·0087). **W4(T-AI) 진입 0** — 사용자 명시 정합. magicJar 비전 "룰 기반 quant trading 본체"의 W3 baseline 안정화 LOCK + AI 호출 0 grep 검증 의무 지속(ADR 0023·0066·0067·0068·0080). v3.8.0 minor 후보 = W4(T-AI·T-MCP) 진입 마감 또는 v3.7.x patch 누적 후 W5(T-LWC·T-LEARN) 진입 결정 의제. 정책 진화는 사용자 결정·운영 dogfooding evidence·외부 검증 흡수 3축으로 누적되어 v3.7.0까지 **32건 정책 진화 + ADR 90건 + 운영 헌법 8 § + 8 sub-agent + 7 JVM 모듈 + 2 페르소나 + 1 cross-review 운영 룰** baseline에 도달.
