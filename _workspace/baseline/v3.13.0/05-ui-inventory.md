# 05 — UI Inventory (frontend SPA · 화면·메뉴·버튼·상태 매트릭스)

> **시점**: v3.13.0 baseline (2026-06-12 cut)
> **권위 소스**: `frontend/src/App.tsx` (route 정의) · `frontend/src/components/layout/AppHeader.tsx` (메뉴 4 group) · `frontend/src/pages/Admin/RealtimeStatusPage.tsx` · `frontend/src/pages/Strategy/{DiscoveryScorecardPage,PaperDashboardPage,DaytradeDiscoverPage}.tsx` · `frontend/src/pages/SystemEvent/{SystemEventPage,SystemEventDetailModal}.tsx` · `frontend/src/components/ui/DetailCell.tsx` · `frontend/src/components/strategy/{SignalCard,SignalTracePanel}.tsx` · `frontend/src/components/order/OrderTriggerBadge.tsx` · `frontend/src/pages/Symbol/{SymbolDetailPage,SymbolOrderbook}.tsx` · `frontend/src/features/daytradePaper/**` · `frontend/src/features/screener/**` · `frontend/src/api/**`
> **작성자**: frontend (실시간 구독 상태 관리자 화면 · 발굴 스코어카드 · 모의 트레이딩 대시보드 + 발굴 다중선택 승격 모달 · 시스템 이벤트 Detail 툴팁+상세 모달 · 키 사슬 추적 드릴다운 · 종목 상세 호가창 "표시 중에만" 복원)
> **이전 판**: `_workspace/baseline/v3.12.0/05-ui-inventory.md`

본 문서는 **v3.13.0 시점에 화면이 무엇을 보여주고, 어떤 메뉴·버튼·상태를 가지며, 어떤 데이터 토픽을 구독하는지**를 SPA 구조 그대로 인벤토리화한다. 화면 추측·미구현 화면 묘사 금지 — `App.tsx` route 정의 + `AppHeader.tsx` nav를 raw 출처로 한다. 본 판은 **v3.12.0 인벤토리를 베이스로 증분**(신규 화면·신규 컴포넌트·신규 동작 패턴만 상세 기술, 무변경 영역은 5-2-A·5-9 안정 영역으로 압축)한다.

---

## 5-0. v3.13.0 변경 요약 (v3.12.0 대비)

| 영역 | v3.12.0 | v3.13.0 | 권위 |
|------|---------|---------|------|
| route count | 19 | **22** (`/admin/realtime`·`/strategy/scorecard`·`/strategy/paper` 신규 + `/strategy/daytrade-discover`·`/orders/new` 명시화) — `<Route>` element 총 27건(redirect alias 5 포함) | `App.tsx` L131~L178 |
| nav 구조 | 3 group dropdown (시장·전략·리스크) + 상시 2 | **4 group dropdown** (시장·전략·리스크 + **운영 신설**) + 상시 2 (대시보드·매매일지) — "운영" 그룹 신설(실시간 상태·시스템 이벤트) | `AppHeader.tsx` L43~L84·L248~L254 |
| 운영 영역 | (없음 — `/system`은 nav 미노출) | + **`/admin/realtime` (실시간 구독 상태 관리자)** + `/system`을 "운영" 그룹으로 명시 노출 | `pages/Admin/RealtimeStatusPage.tsx` (122 LOC) + `components/admin/Realtime*` 4종 |
| 전략 영역 | research·builder·list·backtest·scenario-replay (5) | + **`/strategy/scorecard` (발굴 스코어카드 · V77 forward-test)** + **`/strategy/paper` (모의 트레이딩 대시보드)** + 발굴 메뉴 노출(`/strategy/daytrade-discover`) → 8 item · **검증 퍼널 순 재배열**(발굴→리서치→빌더→백테스트→모의→스코어카드→목록→리플레이) | `pages/Strategy/{DiscoveryScorecardPage(339 LOC),PaperDashboardPage(346 LOC)}.tsx` · `AppHeader.tsx` L55~L67 |
| 발굴 → 모의 승격 | (단건 후보 → 빌더 draft만) | **발굴 후보 다중선택 + 일괄 승격 모달** (`PaperPromoteModal`) — 발굴 화면 체크박스 다중선택 → "모의로 굴려보기" → 페이퍼 전략 생성 (ADR 0122 §8-1) | `pages/Strategy/DaytradeDiscoverPage.tsx` L320~L567 · `features/daytradePaper/components/PaperPromoteModal.tsx` |
| 시스템 이벤트 Detail | truncate 텍스트만 (잘려 안 보임) | **hover ~1초(700ms) 툴팁(JSON pretty · portal) + 클릭 상세 모달(복사)** — `DetailCell` + `SystemEventDetailModal` | `components/ui/DetailCell.tsx` · `pages/SystemEvent/SystemEventDetailModal.tsx` (#942) |
| 키 사슬 추적 | (시그널·주문 분리 조회) | **OrderTrigger 발사 출처 색상 배지 + 시그널 행 확장 키 사슬 드릴다운**(사인→주문→체결→전략 보유 정합) — `OrderTriggerBadge` + `SignalTracePanel` | `components/order/OrderTriggerBadge.tsx` · `components/strategy/SignalTracePanel.tsx` (ADR 0115 §2-B, #906) |
| 종목 상세 호가창 | (제거됨 — #938로 DETAIL 구독 경로 제거) | **"표시 중에만" 방식 복원** — 상세 화면 mount → 구독, 이탈(unmount·종목전환·탭닫기) 즉시 해제(sendBeacon fallback) | `pages/Symbol/SymbolDetailPage.tsx` L226~L260 (#943) |

> v3.13.0 = **발굴 → 모의(페이퍼) → 스코어카드 검증 퍼널 화면 완결 + 운영 가시화(실시간 구독 상태) + 키 사슬 추적성 + 시스템 이벤트 Detail 가독성 베이스라인**. AI 매매 결정권은 여전히 0 (ADR 0026·0112 정합). 발굴·페이퍼·스코어카드 전부 read/조회·중단 전용 — 자동매매 결정권 0, watchlist write 0 (방향성 보존 가드 정합).

---

## 5-1. SPA 한 장 요약

- **단일 SPA** — React 19 + TS + Vite + Tailwind + TanStack Query + STOMP.js, pnpm, Vite dev `:5173`, 운영 docker `frontend` 컨테이너 (ADR 0009 · ADR 0049)
- **레이아웃** — `AppHeader` (전 화면 공통 · **4 nav group dropdown** + 상시 대시보드·매매일지 + WS/KIS WS indicator 2 + RuntimeFlagsIndicator + NotificationCenter + Settings) + page outlet
- **데이터 채널** — REST (`/api/**`, axios `apiClient`) + STOMP (`/topic/**`)
- **상태 관리** — TanStack Query (server state) + zustand (`frontend/src/stores`) + STOMP topic subscription
- **route count** — `<Route>` 27건 (실 path 22 + redirect alias 5: `/watchlist`·`/qa/scenario-replay`·`/performance`·`*` 등) · **nav group** — 4 (시장 5 · 전략 8 · 리스크 2 · 운영 2 = 17 item) + 상시 2 (대시보드·매매일지) · indicator 2 + RuntimeFlags + NotificationCenter + Settings
- **공통 모달 표준** — `useModalBehavior` (ESC + body scroll lock · IME·중첩 안전) — v3.12.0 유지(신규 모달 `PaperPromoteModal`·`SystemEventDetailModal` 적용)

---

## 5-2. 화면 매트릭스 (route × 컴포넌트 × hook × data source × 액션)

> **권위**: `frontend/src/App.tsx` L131~L178 의 `<Route>` 정의 그대로.

### 5-2-A. 안정 영역 (v3.12.0 무변경 — 압축 인용)

다음 화면은 v3.12.0 인벤토리 그대로 유지된다. 상세는 `_workspace/baseline/v3.12.0/05-ui-inventory.md` 참조.

| route | 페이지 컴포넌트 | 비고 |
|-------|-----------------|------|
| `/` | `pages/Dashboard/DashboardPage.tsx` | MarketIndexStrip + AccountSummary + Holdings + Balance + QuickLink (v3.12.0 동일) |
| `/symbol/:code` | `pages/Symbol/SymbolDetailPage.tsx` | LWC v4 차트 + 지표 + AI SYMBOL/CHART — **호가창 "표시 중에만" 복원(5-2-G)** |
| `/strategy/builder` | `pages/Strategy/StrategyBuilderPage.tsx` | AI 전략 생성 모달 (v3.12.0 동일) |
| `/strategy/backtest` | `pages/Strategy/BacktestPage.tsx` | v3.12.0 동일 |
| `/strategy/research` | `pages/Strategy/StrategyResearchPage.tsx` | AI 종목발굴 패널 (v3.12.0 동일) |
| `/strategy/daytrade-discover` | `pages/Strategy/DaytradeDiscoverPage.tsx` | **다중선택 → 페이퍼 승격 모달 추가(5-2-C)** · nav 노출 격상 |
| `/strategy/list` | `pages/Strategy/StrategyListPage.tsx` | **키 사슬 추적 드릴다운 추가(5-2-E)** — `SignalCard` 행 확장 |
| `/strategy/scenario-replay` | `pages/QA/ScenarioReplayPage.tsx` | v3.12.0 동일 |
| `/risk-config`·`/risk-events`·`/positions` | `pages/Risk/*` | v3.12.0 동일 |
| `/orders`·`/orders/new`·`/orders/:id` | `pages/orders/*` | 3중 게이트 + KillSwitch + **OrderTrigger 발사 출처 배지(5-2-E)** |
| `/journal` | `pages/TradingJournalPage.tsx` | v3.12.0 동일 |
| `/performance` | `pages/PerformanceDashboardPage.tsx` | `/performance` route + Dashboard `?view=performance` 병존 (v3.12.0 동일) |
| `/system` | `pages/SystemEvent/SystemEventPage.tsx` | **Detail 툴팁+상세 모달 추가(5-2-F)** + nav "운영" 그룹 노출 |
| `/market/trend`·`/market/calendar` | `features/market/pages/*` | v3.12.0 동일 |
| `/instruments` | `pages/Instruments/InstrumentUniversePage.tsx` | `?watchlist=1` 쿼리로 관심종목 필터 진입 (v3.12.0 동일) |
| `/setup` | `pages/SetupWizard.tsx` | 5 step (v3.12.0 동일) |
| `/watchlist` | `<Navigate to="/instruments?watchlist=1" replace />` | redirect (v3.12.0 동일) |
| `/qa/scenario-replay` | `<Navigate to="/strategy/scenario-replay" replace />` | redirect (v3.12.0 동일) |
| `*` | `<Navigate to="/" replace />` | 미정의 URL은 대시보드로 |

### 5-2-B. 운영 가시화 — 실시간 구독 상태 ⭐ v3.13.0 신설 (최고 관리자 장애 처리용)

| route | 페이지 컴포넌트 | 주요 child 컴포넌트 | 주요 hook | REST endpoint | 사용자 액션 |
|-------|-----------------|---------------------|-----------|---------------|------------|
| **`/admin/realtime`** ⭐ | **`pages/Admin/RealtimeStatusPage.tsx`** (122 LOC) | `RealtimeStatusBanner` · `RealtimeSlotGauge` · `RealtimeSubscriptionTable` · `RealtimeCandleFreshnessPanel` | `useRealtimeStatus` (REST polling 5s · `REALTIME_STATUS_POLL_MS`) | `GET /api/admin/realtime-status` → `RealtimeStatusDto` | 5초 auto-refresh + 수동 새로고침 + 마지막 fetch 시각 표시 |

#### 5-2-B-1. 5 섹션 구성 (권위: `RealtimeStatusPage.tsx` L7~L18·L100~L119)

- **① 상단 상태 배너** (`RealtimeStatusBanner`) — WS 연결 · stale · 체결통보. 셋 중 하나라도 비정상이면 빨간 강조 + 짧은 한국어 장애 판독 힌트("consumer가 상태를 못 쓰고 있음 — 컨테이너 점검" / "KIS WS 미연결 — 시세·체결 유입 정지" / "체결 통보 구독 끊김 — 주문 체결 알림 정지").
- **② 슬롯 게이지** (`RealtimeSlotGauge`) — `used/max` + trId 별 분해(`byTrId` 예: `{H0STCNT0:12, H0STASP0:4}`).
- **③ 구독 테이블** (`RealtimeSubscriptionTable`) — trId · 종목 · owner(색 배지) · since.
- **④ 캔들 신선도** (`RealtimeCandleFreshnessPanel`) — `ageSeconds > 180` 이면 경고색(시세 유입 정지 의심 · "15:20 이후는 마감 동시호가로 정상" 힌트).
- **⑤ 컨트롤** — 5초 auto-refresh + 수동 ↻ 새로고침 + 마지막 갱신 시각.

> 데이터 권위 = consumer 가 주기 갱신하는 KIS WS 런타임 상태 스냅샷(Redis). `stale=true` = consumer 상태 갱신 실패(컨테이너 점검 신호). **mock 합성 0 — 빈/에러 상태 정직 표기**(API 응답 없으면 "api/consumer 컨테이너 또는 네트워크 점검" 힌트 + 5초 뒤 자동 재시도). 권위: `api/adminRealtime.ts`(`RealtimeStatusDto` 계약 LOCK · market-data 병렬 구현) · `RealtimeStatusPage.tsx` L88~L98. AI 호출 0.

### 5-2-C. 발굴 → 모의(페이퍼) 트레이딩 ⭐ v3.13.0 신설 (ADR 0122)

| route | 페이지 컴포넌트 | 주요 child 컴포넌트 | 주요 hook | REST endpoint | 사용자 액션 |
|-------|-----------------|---------------------|-----------|---------------|------------|
| **`/strategy/paper`** ⭐ | **`pages/Strategy/PaperDashboardPage.tsx`** (346 LOC) | `PaperBadge` · `PaperStrategyCard`(local) · `PaperPositionRow`(local) · `SignalListPanel`(재사용) | `useDaytradePaperStrategies` · `useDaytradePaperPositions` · `useDaytradePaperStop` · `useStompSignal`(실시간 시그널 prepend) · `useOnboardingStore` | `features/daytradePaper/api/daytradePaperApi` (조회 + stop) + `GET /signals`(기존 path 재사용) + STOMP | 활성 모의 전략 조회 · 전략 중단 · 시그널 보기(작동 확인) · 모의 포지션 조회 |

#### 5-2-C-1. 모의 대시보드 구성 (권위: `PaperDashboardPage.tsx` L1~L19·L207~L344)

- **격리 안내** — 헤더 `PaperBadge`(size md) + 전략/포지션 카드 전부에 모의 배지(§6 LOCK). amber 톤으로 실 계좌 화면과 시각 분리. 실 자산 + 가상 자본 합산 표시 금지(ADR 0122 §6).
- **① 활성 모의 전략** — `PaperStrategyCard` (presetTitle · `#strategyId`·presetKey · 종목 코드 칩 · 가상 자본 `가상 ₩…` · status 배지 · 인터벌). 액션 [시그널 보기](스크롤 + 전략 필터 적용)·[전략 중단](`useDaytradePaperStop`, ACTIVE만).
- **② 모의 포지션** — 종목 · 수량 · 평단(가상) · 회차(filledLots) · 실현손익(가상) · 상태(보유 amber / 청산 gray). 빈 상태 시 "포지션 0이어도 고장이 아닐 수 있습니다 — 시그널 내역에서 조건 대기 확인" 안내.
- **③ 시그널 내역** — `SignalListPanel` 재사용(`id="paper-signal-section"`) + STOMP 실시간 prepend. "전략이 지금 작동 중인가" 확인 창(2026-06-10 strategy-mock-paper-flow 보고서 발전점). 시그널 0건 = 조건 대기.
- **가드** — read·중단만(자동 진입 0). 페이퍼 생성은 발굴 화면 승급 액션에서만. 신규 시그널 API 0(기존 path 재사용). userId 미등록 시 "설정 화면에서 KIS 키 등록 후" 안내.

#### 5-2-C-2. 발굴 후보 다중선택 → 페이퍼 승격 모달 (권위: `DaytradeDiscoverPage.tsx` L320~L567)

- **다중선택 툴바** — 발굴 결과 후보 row 체크박스(`selectedCodes` Set) → 하단 툴바에 "(N종목 선택)" + [모의로 굴려보기](`promoteModalOpen`).
- **`PaperPromoteModal`** (`features/daytradePaper/components/PaperPromoteModal.tsx`) — 선택 종목명 lookup 표시 + 가상 자본 입력 + 확인. 사용자 명시 액션 — **자동 진입 0**.
- **가드** — '전체(합집합)' 모드(`presetKey=null`)는 단일 setup 으로 묶을 수 없어 승격 불가("특정 프리셋을 선택해 주세요" 안내). 승격 후 `/strategy/paper`에서 추적.

### 5-2-D. 발굴 스코어카드 ⭐ v3.13.0 신설 (V77 forward-test · read-only)

| route | 페이지 컴포넌트 | 주요 hook | REST endpoint | 사용자 액션 |
|-------|-----------------|-----------|---------------|------------|
| **`/strategy/scorecard`** ⭐ | **`pages/Strategy/DiscoveryScorecardPage.tsx`** (339 LOC) | `useQuery`(`discoveryScorecardApi`) · `DAYTRADE_PRESET_OPTIONS`(프리셋 라벨) | `features/screener/api/discoveryScorecardApi.get({from, presetKey})` | 기간 프리셋(7/30/90일) · 프리셋 필터 · 상세 row 조회(종목 → `/symbol/:code`) |

#### 5-2-D-1. 구성 (권위: `DiscoveryScorecardPage.tsx` L1~L19·L195~L337)

- **목적** — V77 + DiscoveryScorecardScheduler(08:30 snapshot · 23:30 채점)가 매일 발굴 TOP N을 기록·채점한 데이터의 **유일한 화면**(2026-06-10 보고서 발전점 #2 — 화면 0건이던 데이터 가시화). 백테스트(과거)와 달리 **앞으로의 실제 적중**을 측정하는 forward-test 증거.
- **① 필터** — 기간(7/30/90일 토글) + 프리셋(`<select>`).
- **② 요약 카드** — 전체(emphasized) + 프리셋별(`SummaryCard`): hit rate · 평균 다음날 종가수익률 · 평균 다음날 시초가수익률 · 표본(채점/기록).
- **③ 상세 테이블** — 발굴일(DESC) · 프리셋 · 순위(ASC) · 종목 · 점수 · 기준종가 · 다음날종가 · 종가수익률 · 시초가수익률 · 채점(채점됨 emerald / **미채점** gray).
- **가드** — read-only(매매 결정권 0 · watchlist write 0 · KIS 호출 0 · LLM 호출 0). 미채점 row는 채점 필드 null → "미채점" 정직 표기. 빈 응답은 "조회 기간에 기록된 발굴 스냅샷 없음 + 08:30 기록·23:30 채점 절차 안내"(mock 합성 0).

### 5-2-E. 키 사슬 추적성 ⭐ v3.13.0 (ADR 0115 §2-B 불변식 #1·#4 · 최고 관리자 직접 요구 #906)

| 진입점 | 컴포넌트 | 동작 | 권위 |
|--------|---------|------|------|
| **OrderTrigger 발사 출처 배지** | `components/order/OrderTriggerBadge.tsx` | 주문/시그널 내역에서 발사 출처 색상 배지: STRATEGY_SIGNAL(파랑 "전략 사인")·AUTO_EXIT(노랑 "자동 익절")·STOP_LOSS(빨강 "손절")·PARTIAL_TAKE_PROFIT(주황 "분할 익절")·MANUAL(회색 "수동")·EXTERNAL(보라 "외부")·null(점선 회색 "미분류" V80 이전 legacy). `trigger_ref_id` 있으면 hover title 로 유발 근거 키 노출. **0-sentinel 폐지 — "출처를 모르는 주문" 금지** | `OrderTriggerBadge.tsx` · `OrderDetailPage.tsx` L178~L182 · `OrderCard.tsx` |
| **시그널 행 확장 키 사슬 드릴다운** | `components/strategy/SignalTracePanel.tsx` | `SignalCard` [키 사슬 추적 ▸] 토글 → 확장 시 lazy fetch(`useSignalTrace`) → 사인(요약) → 연결 주문 N건(수량·상태·발사 출처) → 체결 타임라인(부분체결 각각 수량·가격·시각) → 현재 전략 보유 수량 + 정합 badge(전략 귀속 == KIS 권위면 정합, 다르면 보정 대기) | `SignalCard.tsx` L70~L71·L153~L179 · `SignalTracePanel.tsx` |

- read-only. 데이터 없으면 "없음" 명시(mock 합성 0). 룰 5: LLM provider import 0건.

### 5-2-F. 시스템 이벤트 Detail 가독성 ⭐ v3.13.0 (#942 · 최고 관리자 보고 2026-06-12)

| 항목 | 동작 | 권위 |
|------|------|------|
| **`DetailCell`** | `/system` Detail 컬럼의 잘린 JSON(`{"signalId":958,...}`)을 ① mouse ~1초(700ms `TOOLTIP_DELAY_MS`) hover → 말풍선 툴팁으로 전체 표시(JSON pretty · `prettyJson`) ② 클릭/Enter/Space → 상세 모달. **portal(body) 렌더** → 테이블 overflow-x clip 영향 0 + 화면 경계 보정. add-only(테이블 레이아웃 불변). 적용은 SystemEventPage 한정(과확장 금지) | `components/ui/DetailCell.tsx` · `SystemEventPage.tsx` L175~L180 |
| **`SystemEventDetailModal`** | DetailCell 클릭 → 이벤트 전체 필드 + detailJson pretty + **복사** 버튼 | `pages/SystemEvent/SystemEventDetailModal.tsx` · `SystemEventPage.tsx` L227~L232 |

### 5-2-G. 종목 상세 호가창 — "표시 중에만" 복원 ⭐ v3.13.0 (#943 · 최고 관리자 결재 2026-06-12)

- v3.13.0 직전 #938 로 DETAIL 호가 구독 경로가 제거됐다가(호가는 전략 ACTIVE 전용으로 좁힘) → 수동매매 시 호가 없이 주문 risk 가 제기되어 **"표시 중에만" 방식으로 복원**.
- 라이프사이클(권위: `SymbolDetailPage.tsx` L12~L16·L226~L260) — 상세 화면 mount → `POST /api/symbols/{code}/realtime/subscribe`(200ms 딜레이 throttle) → STOMP `/topic/market.orderbook.{code}` 실시간 갱신. cleanup(unmount·종목 전환·**탭 닫기**) → `POST /api/symbols/{code}/realtime/unsubscribe` + `navigator.sendBeacon` fallback. **상세 화면 떠 있는 동안만 구독, 이탈 즉시 해제** — watchlist 등록 여부 독립.
- 표시(권위: `SymbolOrderbook.tsx`) — 매수·매도 10단계 호가표 + 호가 클릭 → 지정가 시드(수동매매 보조). 호가 slot 가득(`queued`) → "polling fallback 중" 배지. 장외/null 시 graceful("장 마감 (호가는 거래시간에만 제공)" / "호가 데이터 대기 중...").
- backend 정합 — market-data 가 진입 구독·이탈 즉시 해제 + 15분 idle 안전망 제공(#943 backend).

---

## 5-3. 메뉴 매트릭스 ⭐ v3.13.0 nav 재편 (3 group → 4 group · 전략 퍼널 순 재배열)

### 5-3-A. AppHeader nav (권위: `AppHeader.tsx` L43~L84·L248~L254)

> **★ v3.13.0 변경** — "운영" 그룹 신설(`/admin/realtime`·`/system`). 전략 그룹은 **검증 퍼널 순으로 재배열**(2026-06-10 strategy-mock-paper-flow 보고서 발전점 #5). 그룹 dropdown 은 hover 펼침(180ms close delay) + 버튼 click → 첫 아이템 이동. "대시보드"·"매매일지"는 상시 표시(`TopNavLink`).

| 위치 | 아이템 (label / to) | 비고 |
|------|----------------------|------|
| **상시 (좌)** | 대시보드(`/`, `end`) | `TopNavLink` |
| **시장** (그룹) | 종목검색(`/instruments`) · 포지션(`/positions`) · 주문 내역(`/orders`) · 시장 추세(`/market/trend`) · 시장 캘린더(`/market/calendar`) | 5 item · `/symbol/:code` 는 `/instruments` active 매핑(`isActivePath`) |
| **전략** (그룹) ⭐ 재배열 | 단타 발굴(`/strategy/daytrade-discover`) ⭐노출 → 전략 리서치(`/strategy/research`) → 전략 빌더(`/strategy/builder`) → 백테스트(`/strategy/backtest`) → 모의 트레이딩(`/strategy/paper`) ⭐ → 발굴 스코어카드(`/strategy/scorecard`) ⭐ → 전략 목록(`/strategy/list`) → 시나리오 리플레이(`/strategy/scenario-replay`) | **8 item** · 발굴→검증→실행→관찰 퍼널 순 |
| **리스크** (그룹) | 리스크 설정(`/risk-config`) · 위반 이력(`/risk-events`) | 2 item |
| **운영** (그룹) ⭐ 신설 | 실시간 상태(`/admin/realtime`) ⭐ · 시스템 이벤트(`/system`) | **2 item** · 최고 관리자 장애 처리용 가시화 |
| **상시 (우)** | 매매일지(`/journal`) | `TopNavLink` |

### 5-3-B. AppHeader 우측 indicator·button (v3.12.0 동일)

`RuntimeFlagsIndicator`(로고 옆) · `WsConnectionIndicator` · `KisWsHealthIndicator` · `NotificationCenter`(벨+badge · 전역 STOMP 구독) · Settings button(톱니→SettingsModal) · 헤더 상단 `KisRealCredentialBanner`(실전 키 미등록 노란 배너).

### 5-3-C. SettingsModal 내부 섹션 (v3.12.0 동일 — 5 섹션)

`RealTradingConsentSection` · `KisCredentialSection` · `SystemConfigSection` · `KillSwitchSection` · `TrendPreferenceSection`. 권위: `components/settings/SettingsModal.tsx`. `useModalBehavior` 적용.

### 5-3-D. 공통 모달 동작 — useModalBehavior (v3.12.0 유지 + 신규 모달 적용)

> v3.12.0 11 컴포넌트 적용 패턴 유지(ESC + body scroll lock · IME·중첩 안전). v3.13.0 신규 모달 `SystemEventDetailModal`·`PaperPromoteModal` 도 동일 패턴 정합. 상세는 `_workspace/baseline/v3.12.0/05-ui-inventory.md` 5-3-D 참조.

### 5-3-E. Symbol Detail 내부 탭 / 토글 (v3.12.0 + 호가창 복원)

Interval(`IntervalToggle`) · CandleCount · Indicator overlay · Pattern · AI 진입점 SYMBOL/CHART (v3.12.0 동일) + **호가창 "표시 중에만" 구독 복원**(5-2-G). 상세는 v3.12.0 5-3-E 참조.

---

## 5-4. 상태 매트릭스 (v3.12.0 유지 + 신규)

> Price Card 장 구분 badge · Tick stale badge · Order status · KillSwitch state · RuntimeFlags/Mode · AiCard 7 상태 · CrossSignal badge · TrendLabel badge · AI 발굴 후보 status(NEW/KEPT/DISMISSED)는 v3.12.0 이전 인벤토리 그대로 유지.

### 5-4-J. OrderTrigger 발사 출처 ⭐ v3.13.0 (권위: `OrderTriggerBadge.tsx` L31~)

| trigger | 라벨 | 색 | refKind |
|---------|------|----|----|
| STRATEGY_SIGNAL | 전략 사인 | 파랑 | 사인 |
| AUTO_EXIT | 자동 익절 | 노랑 | 전략 |
| STOP_LOSS | 손절 | 빨강 | 전략 |
| PARTIAL_TAKE_PROFIT | 분할 익절 | 주황 | 전략 |
| MANUAL | 수동 | 회색 | — |
| EXTERNAL | 외부 | 보라 | — |
| null/미분류 | 미분류 | 점선 회색 | (V80 이전 legacy) |

### 5-4-K. 실시간 구독 상태 배너 ⭐ v3.13.0 (권위: `RealtimeStatusBanner`)

WS 연결 · stale · 체결통보 셋 중 하나라도 비정상이면 빨간 강조 + 장애 판독 한국어 힌트. 캔들 신선도 `ageSeconds > 180` 경고색.

### 5-4-L. 페이퍼 전략·포지션 상태 ⭐ v3.13.0 (권위: `PaperDashboardPage.tsx`)

전략 status: ACTIVE(emerald) / 그 외(gray). 포지션 상태: 보유(amber) / 청산(gray). 실현손익 색: 양수 emerald · 음수 red · 0 gray(페이퍼 = 가상 ₩ 중립 톤). 발굴 스코어카드 채점: 채점됨(emerald) / 미채점(gray).

---

## 5-5. STOMP 토픽 패밀리

> v3.12.0 토픽 매트릭스 유지(실시간 시세 4 · 시장 추세 3 · 계좌/주문 2 · 시스템 2 · AI 1). 신규 화면 구독 추가:
> - **모의 트레이딩**(`/strategy/paper`) — `useStompSignal`(시그널 실시간 prepend, 일반 전략 path 재사용 — ADR 0089 Phase 2). 전용 신규 토픽 0.
> - **실시간 구독 상태**(`/admin/realtime`) — REST polling 5s 기반(전용 STOMP 토픽 없음 · consumer Redis 스냅샷 출처).
> - **종목 상세 호가**(`/symbol/:code`) — `/topic/market.orderbook.{code}` 구독 "표시 중에만" 복원(5-2-G).
> 발굴 스코어카드는 REST 폴링(staleTime 60s, 전용 STOMP 없음). 상세 매트릭스는 v3.12.0 5-5 참조.

---

## 5-6. TradingView LWC v4 통합 (frontend 단독 책임 · ADR 0094 §2-2-A / ADR 0085)

v3.12.0 책임 라인 유지(Symbol Detail 차트 · MarketTrend 지수 차트 · 전략 리서치 차트). v3.13.0 신규 LWC 컴포넌트 없음. 상세는 `_workspace/baseline/v3.12.0/05-ui-inventory.md` 5-6 참조.

> chart-curator agent 정의는 `.claude/agents/_archive/chart-curator.md` (역사 보존 · dispatch 0).

---

## 5-7. API 패밀리 (v3.12.0 대비 신규 모듈)

| 모듈 | 신규 여부 | 핵심 endpoint prefix |
|------|----------|----------------------|
| `api/adminRealtime.ts` | ⭐ v3.13.0 | `/admin/realtime-status` (`RealtimeStatusDto` · `useRealtimeStatus` 5s polling) |
| `features/screener/api/discoveryScorecardApi.ts` | ⭐ v3.13.0 | 발굴 스코어카드 조회(`{from, presetKey}`) |
| `features/daytradePaper/api/daytradePaperApi.ts` | ⭐ v3.13.0 | 페이퍼 전략·포지션·중단·승격 |
| `api/signal.ts` (`useSignalTrace`) | ⭐ v3.13.0 확장 | 키 사슬 추적(`SignalTraceSummaryDto`·`TracedOrderDto`·`TracedExecutionDto`) |
| `api/order.ts` (`OrderTrigger`·`triggerRefId`) | ⭐ v3.13.0 확장 | 주문 발사 출처 enum + 유발 근거 키 |
| `api/system-event.ts` | (확장) | Detail 모달용 전체 필드(`detailJson`) |
| `api/strategyResearch`·`strategyBuilderAiGenerate`·`scenarioReplay`·`marketCalendar`·`ai` 등 | (v3.12.0 유지) | v3.12.0 동일 |
| account·symbol·strategy·risk·order·journal·system-event·market·watchlist·position·indicator·patterns·dslTranslate·runtime·onboarding·trendlines·stompBackfill·riskAlertMessage·strategyPosition·instrument·backtest | (유지) | v3.12.0 동일 |

---

## 5-8. 본 문서가 의도적으로 다루지 않는 것 (TBD / 차회 baseline)

- **Playwright 시각 baseline 갱신** — v3.13.0 신규 화면(실시간 상태·스코어카드·모의 대시보드) 및 nav 4 group 재편으로 시각 회귀 diff 발생 가능. spec 추가·snapshot 갱신은 qa Visual 별도 사이클(본 baseline 범위 외 · 헌법 §9-2).
- **MCP UI 노출 (T-MCP)** — backend MCP 서버 expose 유지, frontend 전용 진입 화면 미정착 (v3.12.0 동일).
- **Multipane MTF · LWC plugin** — W5 T-LWC.
- **screenshot description** (`_workspace/frontend/screenshot-desc/`) — Playwright 시각 baseline 이 raw evidence (본 baseline 은 5-2~5-3 표가 SoT).

---

## 5-9. 권위 출처 (raw 인용 LOCK)

| 항목 | raw 출처 |
|------|---------|
| route 정의 (`<Route>` 27건 · 실 path 22) | `frontend/src/App.tsx` L131~L178 |
| nav 4 group + 상시 2 | `frontend/src/components/layout/AppHeader.tsx` L43~L84·L248~L254 |
| 실시간 구독 상태 화면 | `frontend/src/pages/Admin/RealtimeStatusPage.tsx` (122 LOC) + `components/admin/{RealtimeStatusBanner,RealtimeSlotGauge,RealtimeSubscriptionTable,RealtimeCandleFreshnessPanel}.tsx` + `api/adminRealtime.ts` |
| 발굴 스코어카드 | `frontend/src/pages/Strategy/DiscoveryScorecardPage.tsx` (339 LOC) + `features/screener/api/discoveryScorecardApi.ts` |
| 모의 트레이딩 대시보드 | `frontend/src/pages/Strategy/PaperDashboardPage.tsx` (346 LOC) + `features/daytradePaper/**` |
| 발굴 다중선택 승격 모달 | `frontend/src/pages/Strategy/DaytradeDiscoverPage.tsx` L320~L567 + `features/daytradePaper/components/PaperPromoteModal.tsx` |
| OrderTrigger 발사 출처 배지 | `frontend/src/components/order/OrderTriggerBadge.tsx` · `pages/orders/OrderDetailPage.tsx` L178~L182 |
| 키 사슬 추적 드릴다운 | `frontend/src/components/strategy/SignalTracePanel.tsx` · `SignalCard.tsx` L70~L71·L153~L179 · `api/signal.ts`(`useSignalTrace`) |
| 시스템 이벤트 Detail 툴팁·모달 | `frontend/src/components/ui/DetailCell.tsx` · `pages/SystemEvent/SystemEventDetailModal.tsx` · `SystemEventPage.tsx` L175~L180·L227~L232 |
| 종목 상세 호가창 "표시 중에만" 복원 | `frontend/src/pages/Symbol/SymbolDetailPage.tsx` L12~L16·L226~L260 · `SymbolOrderbook.tsx` |
| AI advisory-only 원칙 | ADR 0026 · ADR 0112 |
| 키 사슬 무결성 결정 | ADR 0115 §2-B 불변식 #1·#4 |
| 페이퍼 트레이딩 결정 | ADR 0122 (§6 격리 LOCK · §8-1 다중선택 승격) |
| 차트 책임 이관 결정 | ADR 0085 · ADR 0094 amend 2026-05-21 |
| 관련 PR | #906(키 사슬) · #938→#943(호가창 제거→복원) · #942(Detail 툴팁/모달) · 실시간 상태(`/admin/realtime`) · 스코어카드 · 모의 대시보드 |

— frontend baseline v3.13.0 작성 END —
