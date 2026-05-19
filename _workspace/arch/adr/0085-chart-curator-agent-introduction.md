# ADR 0085: chart-curator 신규 에이전트 영입 — TradingView LWC v4 + 캔들 데이터 SLA·정합성 single owner

- 상태: **Accepted (2026-05-19 격상 — T-CC Phase 1 + Phase 2-A 머지 evidence · v3.6.17 release 포함 · 2026-05-19 §Persona amend — 보수적 시니어 백엔드 개발자 페르소나 · 2026-05-19 §2-1-extended amend — 책임 영역 명확화 chart-curator vs frontend 매트릭스 LOCK)**
- 작성일: 2026-05-19
- 결정자: 사용자(최고 관리자) — orchestrator 위임 수행
- 영역: 메타·하네스 (§9)
- 관련: ADR 0019(캔들 저장 정책) · ADR 0025(LWC 확장) · ADR 0028(Watchlist 기반 Ingestion Worker) · ADR 0029(당일 진행 캔들 Redis 캐시) · ADR 0036(Orchestrator Meta Rules) · ADR 0057(KIS-only) · ADR 0062(합산 가공 5종) · ADR 0063(NXT/통합거래 매트릭스) · ADR 0080(전체 opus 4.7)

## 1. 맥락

### 1-1. dogfooding evidence — 캔들 SLA 분기 결함

사용자 dogfooding(2026-05-19 KST) 발굴 — **한국석유(004090) timeframe 간 SLA 분기 발생**:

| timeframe | 마지막 캔들 | 상태 |
|----------|------------|------|
| 일봉(D)   | 2025-12-24 | **≈5개월 stale** ★ |
| 1분봉(1m) | 2026-05-18 15:19 | 정상 |

단일 종목 내에서 timeframe 간 SLA가 분기됨 — 일봉 적재 파이프라인 broken / 분봉 파이프라인 정상. 사용자 채팅 비판 risk + 자동매매 룰 본체(일봉 기반 long-period 지표) 무력화 risk.

### 1-2. 단일 owner 부재로 인한 결함 누적 책임 분기

캔들 데이터는 3 영역 교차 점유 상태:

| 영역 | 현행 책임 |
|------|----------|
| market-data | KIS REST/WS 어댑터 + 캔들 적재 워커 + 정합성 검증 (모두 묶음) |
| strategy-engine | 캔들 소비 (지표 입력) — 정합성 보장은 market-data 위임 |
| frontend | TradingView LWC v4 컴포넌트 (`frontend/src/components/chart/*`) — 차트 그림 |

결과:
- **"내 영역 아님"** 분기 발생 — 한국석유 일봉 stale은 market-data·strategy-engine·frontend 어디 결함인지 불분명 (Watchlist Ingestion Worker는 market-data, 그러나 일봉만 누락이라 데이터 정합성 lens 부재)
- 캔들 정합성 lens가 어디에도 없음 — `last_candle_at` 모니터링·갭 검출 워커 부재
- TradingView LWC v4 깊이 (series·overlay·marker·priceLine·crossSign·panes·multipane MTF)가 frontend의 "1 컴포넌트"로 압축 — 차트 인터랙션·플러그인이 확장될 때 owner 부재

### 1-3. 기존 7 에이전트 모델 정합 (ADR 0080)

전체 7명 sub-agent opus 4.7 통일 후 — 신규 영입도 opus 4.7 동일 정합. 차등 매트릭스 없음.

## 2. 결정

**8번째 sub-agent `chart-curator` 영입.** TradingView LWC v4 차트 + 캔들 데이터 SLA·정합성 단일 owner.

### 2-1. 영역 경계 매트릭스 (LOCK)

| 영역 | 책임 | chart-curator | 다른 에이전트 |
|------|------|---------------|---------------|
| **TradingView LWC v4 차트 컴포넌트** | series·overlay·marker·priceLine·crossSign·panes·multipane MTF | ✅ **소유** (`frontend/src/components/chart/*` 전체) | frontend 이관 |
| **캔들 적재 워커 (KIS endpoint 호출 + 영속)** | timeframe별 backfill·재시도·휴장 처리 | ✅ **소유** | market-data 이관 |
| **캔들 갭 검출 + 자동 backfill 워커** | 종목별 `last_candle_at` 모니터링 + stale 검증 + 갭 메우기 | ✅ **소유** (신규) | 신규 책임 |
| **MTF 정합성 검증** | 1분봉 → 5m·15m·30m·1h·일봉 집계 정합 검증 | ✅ **소유** (신규) | 신규 책임 |
| **Corporate action candle adjustment** | 액면분할·배당락·합병 시 캔들 retroactive 정정 정책 | ✅ **소유** | market-data와 협력 (raw은 market-data 어댑터, 정합은 chart-curator) |
| **휴장일·시간외·NXT 매트릭스** (ADR 0063) | 캔들 timeframe별 처리 정책 | ✅ **소유** | market-data와 협력 |
| KIS REST/WS HTTP 인프라 | 토큰·Bucket4j·서킷 브레이커·tr_id wiring | ❌ | **market-data 잔류** |
| KIS REST/WS raw 응답 → 도메인 변환 | raw mapper (output1·output2 필드 보존) | ❌ | **market-data 잔류** (chart-curator는 캔들 도메인 인터페이스 소비자) |
| 뉴스·공시·시세 스냅샷·호가·잔고 | DART · 네이버 · KIS inquire-balance · 호가창 | ❌ | **market-data 잔류** |
| 지표·DSL·백테스트·주문 로직 | RuleEngine · BacktestEngine · OrderDispatcher | ❌ | **strategy-engine 잔류** (캔들 소비 client만 유지) |
| Dashboard·Watchlist·매매일지·일반 UI | 차트 외 React 컴포넌트 | ❌ | **frontend 잔류** |
| Spring Boot 뼈대·도메인·공용 인프라 | domain port·api 부트스트랩·Flyway 마이그레이션 | ❌ | **backend-core 잔류** |
| AI 옵션 보조 | LLM 호출 · 프롬프트 | ❌ | **ai-engineer 잔류** |

### 2-2. 영역 경계 원칙

1. **chart-curator = 캔들 데이터 layer + 차트 시각화 layer**.
2. **market-data = KIS API 인프라 + raw 데이터 수집** (캔들 정합성은 chart-curator).
3. **frontend = 차트 외 모든 UI** (차트 컴포넌트는 chart-curator).
4. 캔들 적재 워커가 KIS REST/WS를 호출할 때 — **market-data 어댑터 인터페이스 소비** (자체 KIS HTTP 클라이언트 발행 금지).
5. 차트 컴포넌트가 시그널·주문 마커를 표시할 때 — **strategy-engine·backend-core 도메인 인터페이스 소비** (자체 도메인 결정 금지).

### 2-1-extended. chart-curator vs frontend 책임 영역 명확화 매트릭스 (★ 2026-05-19 amend — chart-curator PR #554 P2-2 evidence)

> **권위**: chart-curator PR #554 P2-2 보고("ADR 0085 §책임 영역 본문 명확화 결함") + orchestrator 2026-05-19 KST dispatch 의제. 본 § amend는 chart-curator·frontend 영역 분기 ambiguous path 영구 차단.

**LOCK 매트릭스 — 파일 경로별 단일 owner**:

| 파일 경로 패턴 | owner | evidence |
|----------------|-------|----------|
| `frontend/src/components/chart/*` | ✅ **chart-curator** | TradingView LWC v4 series·overlay·marker·priceLine·crossSign·panes·multipane MTF 본체 |
| `frontend/src/pages/Symbol/SymbolChart.tsx` | ✅ **chart-curator** | 종목 상세 차트 메인 컴포넌트 (LWC v4 instance state lift §13 정합) |
| `frontend/src/pages/Symbol/LightweightChart.tsx` | ✅ **chart-curator** | LWC v4 wrapping 컴포넌트 (dispose race 가드 §13) |
| `frontend/src/pages/Symbol/IndicatorOverlay.tsx` | ✅ **chart-curator** | 지표 overlay (LWC v4 series add) |
| `frontend/src/pages/Symbol/PatternMarker.tsx` | ✅ **chart-curator** | 57 캔들 패턴 marker (LWC marker series) |
| `frontend/src/components/backtest/EquityCurveChart.tsx` | ✅ **chart-curator** | 백테스트 equity curve LWC v4 (dispose race 가드 §13) |
| `frontend/src/features/performance/components/*Chart*.tsx` | ✅ **chart-curator** | T-PERF 일별 P&L bar chart·equity curve 등 LWC v4 |
| `frontend/src/components/DashboardCard.tsx` | ❌ frontend | 대시보드 카드 (LWC 0건 — 일반 UI) |
| `frontend/src/components/WatchlistTable.tsx` | ❌ frontend | 관심종목 테이블 (LWC 0건) |
| `frontend/src/pages/Journal/*` | ❌ frontend | 매매일지 (LWC 0건 — text/table UI) |
| `frontend/src/pages/Onboarding/*` | ❌ frontend | 온보딩 wizard (LWC 0건) |
| `frontend/src/features/account/components/HoldingsTable.tsx` | ❌ frontend | 보유 종목 테이블 (LWC 0건) |
| `frontend/src/features/account/components/BalanceCard.tsx` | ❌ frontend | 잔고 카드 (LWC 0건) |
| `frontend/src/components/SettingsModal*` | ❌ frontend | 설정 모달 (LWC 0건) |
| `frontend/src/components/AppHeader*` | ❌ frontend | 헤더 메뉴 (LWC 0건) |

**판정 원칙**:
1. **LWC v4 instance를 직접 다루는 파일 → chart-curator** (TradingView Lightweight Charts API import 또는 chart.addSeries/remove/dispose 호출 포함)
2. **차트 외 모든 UI (테이블·카드·모달·페이지 wrapper) → frontend** (일반 React 컴포넌트)
3. **모호한 경우** (예: 차트 컨테이너 페이지에 일반 UI도 섞여 있는 경우) — 본 매트릭스의 파일 경로별 단일 owner 우선 + 한 컴포넌트가 LWC + 일반 UI 혼합 시 분리 의제(별도 ADR 또는 architect 결정)
4. **cross-review** — 자본 path 직결(차트 마커가 시그널·주문·체결 표시 시 → chart-curator 1차 lens) / 인프라 일반(props 타입·hook 패턴 → backend-core/frontend 1차 lens) (ADR 0085·0087 페르소나 매트릭스 정합)

**Phase 1·2·3 미래 분기**:
- 본 ADR 0085 영입 직후(2026-05-19) — 점진적 코드 이관 (별도 사이클, code git mv 0, 책임만 이관)
- 점진적 이관 후 — 본 §2-1-extended 매트릭스가 단일 권위 LOCK
- 신규 차트 컴포넌트 추가 시 — chart-curator 영역으로 자동 귀속 (frontend가 발행 시 PR 리뷰에서 chart-curator로 owner 이관 요청)

### 2-2-1. 페르소나 — 보수적 시니어 백엔드 개발자 "안정빌드" (★ 2026-05-19 amend)

> **권위**: 사용자(최고 관리자) 2026-05-19 KST 명시. 본 § 원문은 사용자 명시 그대로 보존 — architect·orchestrator 임의 수정 금지.

**페르소나 본문** — `.claude/agents/chart-curator.md` §페르소나 및 `.claude/skills/magicjar-chart-curator/SKILL.md` §페르소나 SoT 보존. 본 § 요약:

| 항목 | 값 |
|------|----|
| 역할 | 시니어 백엔드 개발자 |
| 경력 | 15년차 (대규모 운영 시스템 경험 다수) |
| 성향 | 보수적, 안정성 중시, 검증된 패턴 선호 |
| 별명 | "안정빌드" — 프로덕션 장애를 여러 번 경험한 베테랑 |
| 좌우명 | "동작하는 코드를 함부로 건드리지 마라" |

**핵심 가치 5건**: 안정성 최우선 · 검증된 패턴 · 하위호환성 · 점진적 변경 · 방어적 프로그래밍.

**코드 리뷰 7 lens**: 사이드 이펙트 · 예외 처리 · null safety · 트랜잭션 경계 · 롤백 시나리오 · 로깅 · 기존 테스트 보존.

**경계 패턴 5건**: 검증되지 않은 라이브러리 · 과도한 추상화(YAGNI) · 한 번에 많은 파일 변경 · 기존 API 계약 변경 · 복잡한 제네릭/리플렉션.

**코드 작성 스타일**: `require`·`check` 입력 검증 + `try-catch` + `logger.info/error` 의무 (Kotlin 예시 본문 SoT는 agents·SKILL 보존).

**피드백 스타일 5 인용**: "이 변경이 기존 XXX에 영향을 줄 수 있습니다." / "예외 처리가 빠져 있습니다. 프로덕션에서 NPE 발생 가능성이 있습니다." / "검증된 패턴이 있는데 굳이 새로운 방식을 도입할 필요가 있을까요?" / "운영 관점에서 이 구조는 장애 시 원인 파악이 어렵습니다." / "점진적으로 마이그레이션하는 것이 리스크가 적습니다."

**출력 형식**: `## 개발자1 (보수적) 의견` 헤더 + 안정성 관점(영향·리스크·롤백) + 코드 리뷰(🔴 필수 / 🟡 권장 / 🔵 참고) + 우려 사항 + 대안 제시.

#### 페르소나 재정의 사유 (운영 evidence)

영입 직후(2026-05-19 격상) 첫 2 사이클 결함 5건 누적:
- PR #514 — commit author attribution 결함 (frontend으로 잘못 부여)
- PR #525 — `audit_trail` JSON syntax error (서둘러 직렬화 실패)
- PR #525 — KillSwitch FK violation (system userId 미존재 검증 누락)
- PR #535 §12 — "5+ 영업일 임의 임계치" 잘못된 제안 (사용자 명시 차단)
- PR #537·#541 — author attribution 재발

신입 자율성 + 빠른 추론 부작용 패턴. **보수적 페르소나 = 안정성 최우선 + 검증된 패턴 + 방어적 프로그래밍**으로 전환 시 위 결함 root cause 영구 차단. chart-curator는 **자본 흐름에 직결되는 캔들 데이터 정합성 single owner** (자동매매 룰 본체 직결 — 일봉·분봉·MTF 정합성·corp action) — 보수적 페르소나가 본질적으로 합리.

#### 페르소나 적용 의무 영역

본 페르소나는 chart-curator가 발행하는 **모든 작업물**에 적용:
- 새 기능 발행 전 — "기존 동작 영향 / 리스크 포인트 / 롤백 가능성" 3 lens 평가
- 코드 발행 시 — `require`·`check`·`try-catch` + `logger.info/error` 의무
- ADR/spec 검토 시 — 5 경계 패턴 lens
- 데이터 햇징 룰 13건 (SKILL.md `데이터 햇징 룰 매트릭스`)은 본 페르소나의 운영 baseline
- PR 리뷰 응답 시 — 출력 형식(🔴/🟡/🔵 3 등급) 사용

### 2-3. chart-curator 모델 + git-author

- 모델: `opus` (ADR 0080 정합 — 8명 모두 opus 4.7)
- git-author: `magicjar-chart-curator <bot+chart-curator@magicjar.local>`
- pre-commit 화이트리스트(ADR 0036) `<agent>` 허용 값에 `chart-curator` 추가
- agent-charter.md §1-A `<agent>` 허용 값에 `chart-curator` 추가

### 2-4. 신규 산출물

| 파일 | 내용 |
|------|------|
| `_workspace/arch/adr/0085-chart-curator-agent-introduction.md` | 본 ADR |
| `.claude/agents/chart-curator.md` | 에이전트 frontmatter + 본문 (7 기존 패턴 정합) |
| `.claude/skills/magicjar-chart-curator/SKILL.md` | TradingView LWC v4 + 캔들 도메인 깊이 SKILL |
| `_workspace/arch/agent-charter.md` §0·§1-A·§8 | 8 actor 갱신 |
| `.claude/skills/magicjar-orchestrator/SKILL.md` | 에이전트 7명 → 8명 표 갱신 |
| `CLAUDE.md` | 7명 → 8명 표기 갱신 |
| `.claude/agents/market-data.md` | 캔들 영역 chart-curator 이관 표기 |
| `.claude/agents/frontend.md` | 차트 컴포넌트 chart-curator 이관 표기 |
| `_workspace/arch/adr/README.md` §9 + §14 | 0085 row 추가 + 변경 이력 |
| `_workspace/backlog.md` | T-CC 트랙 신설 §추가 (캔들 정합성 트랙) |
| `CHANGELOG.md` | row 추가 |

## 3. 결과

### 3-1. 긍정

- 캔들 SLA 결함(한국석유 일봉 5개월 stale) 책임 lens 단일화 — chart-curator가 즉시 dogfooding fix dispatch 가능
- TradingView LWC v4 깊이 확장 (multipane MTF·플러그인·인터랙션) 시 owner 명시 → frontend 영역 부담 분산
- MTF 정합성 검증(1m → 일봉 집계)이 신규 lens로 등장 → 백테스트·지표 신뢰성 향상
- ADR 0019·0025·0028·0029·0048·0057·0062·0063 (캔들 관련 8 ADR) single owner 발생 → 후속 결정 정합성 향상

### 3-2. 부정

- 사이클당 호출 sub-agent 1명 증가 (7 → 8) → 디스패치 매트릭스 복잡도 +14%
- 캔들 영역 이관 사이클 발생 (운영 코드 git mv·import 정정) — 본 ADR은 agent infra만, 코드 이관은 점진적 사이클로 별도 (비범위)
- 토큰 비용 8 opus → 사이클당 sub-agent 호출 시 추가 1명 토큰 누적

### 3-3. 후속

본 ADR 머지 후 즉시:
1. **chart-curator 첫 발화** — 한국석유(004090) 일봉 5개월 stale dogfooding fix + KIS daily candle backfill 워커 설계 + `last_candle_at` 모니터링 lens 신설
2. **T-CC 트랙 backlog 발행** — 캔들 정합성 트랙 (갭 검출·자동 backfill·MTF 정합성·corporate action adjustment)
3. **점진적 코드 이관** (별도 사이클) — `frontend/src/components/chart/*` → chart-curator 작업 영역으로 이관 (코드 git mv 0, 책임만 이관). KIS 캔들 워커도 책임만 이관 (market-data 어댑터 인터페이스는 그대로 소비).
4. **ADR 0030 SoT 정합** — `docs/architecture/master-architecture.md` §10·§B 캔들 영역 chart-curator 책임 row 추가 (별도 PR 또는 본 PR 옵션)

## 4. 대안 검토

### 4-1. 대안 A: market-data 영역 강화 — 캔들 lens만 추가

market-data sub-agent 내부에 캔들 정합성 lens 추가, TradingView LWC v4는 frontend 잔류.

**거부 사유**:
- 한국석유 결함 evidence가 정확히 "market-data 단일 영역 내 lens 부재"를 의미 — 동일 sub-agent 내 lens 추가만으로는 책임 분기 해결 안 됨 (sub-agent 자체가 KIS 어댑터·뉴스·캔들 적재 등 4~5 영역 압축)
- TradingView LWC v4 깊이가 W5(T-LWC) 진입 시 폭증 예상 — frontend 잔류는 1 컴포넌트가 sub-agent 전체 영역의 30%+ 점유 risk
- 캔들 데이터 + 차트 시각화는 의미상 결합 (MTF·overlay·multipane은 캔들 timeframe 매트릭스의 시각화) — 분산보다 결합 owner가 정합

### 4-2. 대안 B: chart-curator 신규 영입 (채택)

8번째 sub-agent 영입. 캔들 layer + 차트 layer 단일 owner.

**채택 사유**:
- 단일 owner lens 발생 → 결함 책임 분기 0
- TradingView LWC v4 깊이 확장 흡수 (W5 T-LWC 진입 직전 대비)
- ADR 0080 (8명 opus 4.7) 모델 정합 일관성 유지 — 차등 0
- 캔들 데이터 SLA·정합성이 자동매매 룰 본체 직결 (지표·백테스트·시그널 모두 캔들 소비) → single owner가 운영 안정성 향상

### 4-3. 대안 C: chart-curator를 임시 task 단위 sub-agent로 도입 (영입 없이)

특정 사이클(T-LWC·T-CC)에만 임시로 chart-curator 모자 호출, 평시는 market-data/frontend 분담.

**거부 사유**:
- 임시 sub-agent는 author 화이트리스트(ADR 0036)·worktree author 의무(헌법 §1-A) 정합 깨짐
- 캔들 SLA 결함은 임시가 아닌 **지속 모니터링 lens** 필요 (last_candle_at 데일리 검증·갭 자동 backfill·MTF 정합성 검증)
- 영입 vs 임시 차이: 사이클 진입 비용은 동일, 그러나 SoT(헌법·SKILL·git-author 화이트리스트) 정합 비용은 영입이 우월

## 5. 사용자 명시 인용

> "단일 종목에서 timeframe 간 SLA 분기 — 한국석유(004090) 일봉 last = 2025-12-24 (≈5개월 stale) vs 1분봉 last = 2026-05-18 15:19 (정상). 단일 owner 부재 → 결함 누적 책임 분기('내 영역 아님'). 8번째 에이전트 `chart-curator` 영입 — TradingView LWC v4 차트 전문성 + 캔들 데이터 SLA·정합성 단일 owner." (2026-05-19 KST 사용자 dispatch 의제)

## 6. 관련 결정

- ADR 0019 · 0028 · 0029 — 캔들 저장 정책 / Ingestion Worker / 당일 Redis 캐시 (모두 chart-curator 책임 영역으로 cross-ref)
- ADR 0025 — LWC 확장 (chart-curator 첫 SoT)
- ADR 0036 — Orchestrator Meta Rules (pre-commit author 화이트리스트 갱신 의무)
- ADR 0048 — KIS WebSocket 41건 구독 한도 (캔들 ingestion 우선순위 정책 cross-ref)
- ADR 0057 — KIS-only (캔들 출처 단일화 정합)
- ADR 0062 · 0063 — 합산 가공 5종 + NXT/통합거래 매트릭스 (MTF 정합성 lens 진입점)
- ADR 0080 — 전체 8명 opus 4.7 통일 정합

## 7. 변경 이력

| 일자 | 변경 | 사유 |
|------|------|------|
| 2026-05-19 | ADR 0085 신설 — Proposed | 사용자 dogfooding evidence(한국석유 일봉 5개월 stale) + 3 영역 교차 점유 단일 owner 부재 → 8번째 sub-agent chart-curator 영입 결정. (architect) |
| 2026-05-19 | Proposed → Accepted | T-CC Phase 1 dogfooding fix (PR #514 — `LastCandleAtMonitor` + `LastCandleAtBootstrapRunner` + `LastCandleAtAdminController` + 한국석유 일봉 stale fix) + Phase 2-A 햇징 룰 10건 LOCK (PR #525 — `CandleGapDetectorPort/Adapter` + `CandleBackfillWorker` + `CandleBackfillAdminController` + Flyway V39 + 27 단위 테스트 ALL GREEN) · v3.6.17 release 포함 · chart-curator skill 데이터 햇징 § 신설 영구 보호. (architect) |
| 2026-05-19 | Persona 재정의 — 신입 → 보수적 시니어 백엔드 개발자 (15년차 안정빌드) | 사용자(최고 관리자) 2026-05-19 KST 명시 + 신입 자율 추론 결함 5건 evidence (PR #514·#525·#535·#537·#541 — commit author attribution · audit_trail JSON syntax error · KillSwitch FK violation · 임의 임계치 잘못된 제안). chart-curator는 자본 흐름 직결 single owner → 보수적 페르소나가 본질적으로 합리. `.claude/agents/chart-curator.md` §페르소나 + `.claude/skills/magicjar-chart-curator/SKILL.md` §페르소나 신설 (사용자 명시 원문 100% 보존). 본 ADR §2-2-1 §페르소나 § 신설. (architect) |
| 2026-05-19 | §2-1-extended amend — chart-curator vs frontend 책임 영역 명확화 매트릭스 LOCK | chart-curator PR #554 P2-2 evidence ("ADR 0085 §책임 영역 본문 명확화 결함") + orchestrator 2026-05-19 KST dispatch 의제 정합. §2-1-extended 신설 — 파일 경로별 단일 owner 매트릭스 LOCK: chart-curator 영역(`frontend/src/components/chart/*` + `frontend/src/pages/Symbol/SymbolChart·LightweightChart·IndicatorOverlay·PatternMarker` + `frontend/src/components/backtest/EquityCurveChart` + `frontend/src/features/performance/components/*Chart*`) ↔ frontend 영역(차트 외 일반 UI — DashboardCard·WatchlistTable·Journal·Onboarding·HoldingsTable·BalanceCard·SettingsModal·AppHeader). 판정 원칙 4건(LWC v4 instance 직접 다루는 파일 → chart-curator / 일반 React 컴포넌트 → frontend / 모호 시 본 매트릭스 우선 + architect 결정 / cross-review 자본 path lens 직결 chart-curator + 인프라 일반 backend-core·frontend 페르소나 매트릭스 정합). 미래 점진적 이관 후 본 §2-1-extended 매트릭스가 단일 권위 LOCK. (architect) |
