# 05 — UI Inventory (frontend SPA · 화면·메뉴·버튼·상태 매트릭스)

> **시점**: v3.14.0 baseline (2026-06-21 cut)
> **권위 소스**: `frontend/src/App.tsx` (route 정의) · `frontend/src/components/layout/AppHeader.tsx` (nav 4 group) · `frontend/src/pages/Strategy/{StrategyListPage,StrategyBuilderPage}.tsx` · `frontend/src/components/strategy/{StrategyFilterBar,StrategyList,StrategyCard,StrategyRunStatusSummary,strategyCardBadges,StrategySaveModal,ScaleInExitPanel,ScaleInPlanPreviewInline,scaleInPlanPreviewDisplay,CollapsibleSection,StrategyBuilderCanvas,builderTreeValidation}.tsx` · `frontend/src/hooks/{useMediaQuery,useDebouncedValue}.ts` · `frontend/src/api/strategy.ts` · `frontend/src/api/hooks/{useSigStrategy,useScaleInDefaults}.ts`
> **작성자**: frontend (전략 빌더 고도화 — 저장 전 분할계획 프리뷰·floor 필드 항상노출+prefill·자동청산 토글·RiskPlan 섹션 접기·조건 검증 인라인·모바일 탭 / 전략 목록 UX — 검색·정렬·카드 배지·빠른/일괄 액션·상태 요약 칩 점프)
> **이전 판**: `_workspace/baseline/v3.13.0/05-ui-inventory.md`

본 문서는 **v3.14.0 시점에 화면이 무엇을 보여주고, 어떤 메뉴·버튼·상태를 가지며, 어떤 데이터 토픽을 구독하는지**를 SPA 구조 그대로 인벤토리화한다. 화면 추측·미구현 화면 묘사 금지 — `App.tsx` route 정의 + `AppHeader.tsx` nav를 raw 출처로 한다. 본 판은 **v3.13.0 인벤토리를 베이스로 증분**(전략 빌더·전략 목록 두 클러스터 UX 고도화만 상세 기술, 무변경 영역은 5-2-A·5-9 안정 영역으로 압축)한다.

---

## 5-0. v3.14.0 변경 요약 (v3.13.0 대비)

| 영역 | v3.13.0 | v3.14.0 | 권위 |
|------|---------|---------|------|
| route count | `<Route>` 27건 (실 path 22) | **`<Route>` 28건 (실 path 25 + redirect alias 3: `/watchlist`·`/qa/scenario-replay`·`*`)** — `/strategy/:id/performance`(ADR 0126 · v3.13.0 cut 직후)·`/orders/new` 명시 포함 | `App.tsx` L132~L181 |
| nav 구조 | 4 group dropdown (시장·전략·리스크·운영) + 상시 2 | **4 group 유지** — 단 **전략 그룹 재배열**: "전략 목록"을 **그룹 dropdown 첫 항목으로 승격**(2026-06-17 사용자 결재 · `a96a32ae`), 발굴→리서치→빌더→백테스트→모의→스코어카드→리플레이가 뒤따름 (8 item) | `AppHeader.tsx` L43~L85 |
| **전략 빌더 (저장 모달)** | RiskPlanPreviewPanel(손절·익절) + ScaleInExitPanel(분할) 단일 스크롤 | **(A) 저장 전 분할매수 계획 프리뷰**(`ScaleInPlanPreviewInline` · client-side 산식) + **(B) floor 필드 항상노출+prefill·자동청산 토글**(`ScaleInExitPanel` 갭①②) + **(C) `CollapsibleSection` 그룹핑**(필수 펼침 / 고급 접기) | `StrategySaveModal.tsx` L577~L691 · `ScaleInExitPanel.tsx` L70~L324 · `ScaleInPlanPreviewInline.tsx` |
| **전략 빌더 (캔버스)** | 팔레트·트리·결과 3분할 (데스크톱 고정) | **(B) 조건 검증 인라인 피드백**(`builderTreeValidation` 빈 슬롯 카운트 → 탭바 상태·경고 배너·버튼 disable) + **(D) 모바일 탭 레이아웃**(`useMediaQuery` lg → 블록·조건·결과 탭) | `StrategyBuilderCanvas.tsx` L420~L470 · `StrategyBuilderPage.tsx` L187~L193·L740~L866 · `builderTreeValidation.ts` |
| **전략 목록** | status 탭 필터 + 카드 그리드 + 무한 스크롤 | **검색·정렬 드롭다운**(`q`·`sort` 서버 위임) + **카드 배지**(분할 N회·floor 알람/자동청산) + **빠른 액션**(시작/중지·즐겨찾기★·복제) + **다중선택 일괄 시작/중지** + **상태 요약 칩 필터 점프** | `StrategyListPage.tsx` · `StrategyFilterBar.tsx` · `StrategyList.tsx` · `StrategyCard.tsx` · `StrategyRunStatusSummary.tsx` · `strategyCardBadges.ts` |
| 전략 목록 API | `list(userId, page, size)` | **`list(userId, page, size, {q, sort})`** — `StrategyListQuery`/`StrategyListSort`(`recent`\|`name`\|`status`) · `GET /api/strategies?q=&sort=` (미배포 백엔드 graceful 무시·q 공백/sort recent 전송 생략) | `api/strategy.ts` L349·L357·L374 · `useSigStrategy.ts` |

> v3.14.0 = **전략 운영 UX 성숙 baseline** — ① 빌더 저장 전 "예상 매수 계획"(회차 비중·예상 진입가·예상 손절가·floor 알람선) 가시화 + 빈 슬롯 사전 경고 + 과밀 모달 위계화(접기) + 모바일 빌더 사용 가능화. ② 전략 수 증가 대비 목록 탐색성(검색·정렬)·카드 한눈 상태(분할·floor)·빠른/일괄 운영 액션·상태 요약 점프. AI 매매 결정권 0 (ADR 0026·0112 정합 유지) · 빌더/목록 전부 사용자 명시 액션 · floor 자동청산은 전략별 opt-in default false(알람만 — ADR 0128 amend R3).

---

## 5-1. SPA 한 장 요약

- **단일 SPA** — React 19 + TS + Vite + Tailwind + TanStack Query + STOMP.js, pnpm, Vite dev `:5173`, 운영 docker `frontend` 컨테이너 (ADR 0009 · ADR 0049)
- **레이아웃** — `AppHeader` (전 화면 공통 · 4 nav group dropdown + 상시 대시보드·매매일지 + WS/KIS WS indicator 2 + RuntimeFlagsIndicator + NotificationCenter + Settings) + page outlet
- **데이터 채널** — REST (`/api/**`, axios `apiClient`) + STOMP (`/topic/**`)
- **상태 관리** — TanStack Query (server state) + zustand (`frontend/src/stores`) + STOMP topic subscription
- **route count** — `<Route>` 28건 (실 path 25 + redirect alias 3: `/watchlist`·`/qa/scenario-replay`·`*`) · **nav group** — 4 (시장 5 · 전략 8 · 리스크 2 · 운영 2 = 17 item) + 상시 2 (대시보드·매매일지) · indicator 2 + RuntimeFlags + NotificationCenter + Settings
- **공통 모달 표준** — `useModalBehavior` (ESC + body scroll lock · IME·중첩 안전) — v3.13.0 유지
- **반응형** — `useMediaQuery('(min-width: 1024px)', true)` (matchMedia 부재 시 데스크톱 default → SSR/테스트 호환). v3.14.0 빌더가 첫 모바일 탭 분기 도입

---

## 5-2. 화면 매트릭스 (route × 컴포넌트 × hook × data source × 액션)

> **권위**: `frontend/src/App.tsx` L132~L181 의 `<Route>` 정의 그대로.

### 5-2-A. 안정 영역 (v3.13.0 무변경 — 압축 인용)

다음 화면은 v3.13.0 인벤토리 그대로 유지된다. 상세는 `_workspace/baseline/v3.13.0/05-ui-inventory.md` 참조.

| route | 페이지 컴포넌트 | 비고 |
|-------|-----------------|------|
| `/` | `pages/Dashboard/DashboardPage.tsx` | MarketIndexStrip + AccountSummary + Holdings + Balance + QuickLink (v3.13.0 동일) |
| `/symbol/:code` | `pages/Symbol/SymbolDetailPage.tsx` | LWC v4 차트 + 지표 + AI SYMBOL/CHART + 호가창 "표시 중에만" (v3.13.0 동일) |
| `/strategy/builder` | `pages/Strategy/StrategyBuilderPage.tsx` | ⭐ **고도화(5-2-H)** — 분할계획 프리뷰·floor 필드·검증 인라인·모바일 탭 |
| `/strategy/backtest` | `pages/Strategy/BacktestPage.tsx` | v3.13.0 동일 (빌더 차단 사유 핸드오프 진입점) |
| `/strategy/research` | `pages/Strategy/StrategyResearchPage.tsx` | AI 종목발굴 패널 (v3.13.0 동일) |
| `/strategy/daytrade-discover` | `pages/Strategy/DaytradeDiscoverPage.tsx` | 다중선택 → 페이퍼 승격 모달 (v3.13.0 동일) |
| `/strategy/paper` | `pages/Strategy/PaperDashboardPage.tsx` | 모의 트레이딩 대시보드 (v3.13.0 동일) |
| `/strategy/scorecard` | `pages/Strategy/DiscoveryScorecardPage.tsx` | 발굴 스코어카드 V77 forward-test (v3.13.0 동일) |
| `/strategy/list` | `pages/Strategy/StrategyListPage.tsx` | ⭐ **UX 고도화(5-2-I)** — 검색·정렬·카드 배지·빠른/일괄 액션·상태 요약 |
| `/strategy/:id/performance` | `pages/Strategy/StrategyPerformancePage.tsx` | 전략별 일자별 성과 + 메모 풀스크린 (ADR 0126 · v3.13.0 cut 직후 추가) |
| `/strategy/scenario-replay` | `pages/QA/ScenarioReplayPage.tsx` | v3.13.0 동일 |
| `/risk-config`·`/risk-events`·`/positions` | `pages/Risk/*` | v3.13.0 동일 |
| `/orders`·`/orders/new`·`/orders/:id` | `pages/orders/*` | 3중 게이트 + KillSwitch + OrderTrigger 발사 출처 배지 (v3.13.0 동일) |
| `/journal` | `pages/TradingJournalPage.tsx` | v3.13.0 동일 |
| `/performance` | `pages/PerformanceDashboardPage.tsx` | `/performance` route + Dashboard `?view=performance` 병존 (v3.13.0 동일) |
| `/system` | `pages/SystemEvent/SystemEventPage.tsx` | Detail 툴팁+상세 모달 (v3.13.0 동일) |
| `/admin/realtime` | `pages/Admin/RealtimeStatusPage.tsx` | 실시간 구독 상태 관리자 (v3.13.0 동일) |
| `/market/trend`·`/market/calendar` | `features/market/pages/*` | v3.13.0 동일 |
| `/instruments` | `pages/Instruments/InstrumentUniversePage.tsx` | `?watchlist=1` 쿼리로 관심종목 필터 진입 (v3.13.0 동일) |
| `/setup` | `pages/SetupWizard.tsx` | 셋업 마법사 (v3.13.0 동일) |
| `/watchlist` | `<Navigate to="/instruments?watchlist=1" replace />` | redirect (v3.13.0 동일) |
| `/qa/scenario-replay` | `<Navigate to="/strategy/scenario-replay" replace />` | redirect (v3.13.0 동일) |
| `*` | `<Navigate to="/" replace />` | 미정의 URL은 대시보드로 |

### 5-2-H. 전략 빌더 고도화 ⭐ v3.14.0 (`/strategy/builder` · ADR 0128 amend R3 · 빌더 클러스터)

> **권위**: `StrategyBuilderPage.tsx` · `StrategySaveModal.tsx` · `ScaleInExitPanel.tsx` · `ScaleInPlanPreviewInline.tsx` · `scaleInPlanPreviewDisplay.ts` · `CollapsibleSection.tsx` · `StrategyBuilderCanvas.tsx` · `builderTreeValidation.ts` · `useMediaQuery.ts`. 빌더 클러스터 add-only(전략 목록·`api/strategy.ts` 목록 계약 미수정).

#### 5-2-H-1. (A) 저장 전 분할매수 계획 프리뷰

| 항목 | 동작 | 권위 |
|------|------|------|
| `ScaleInPlanPreviewInline` | SaveModal 안 scale-in **enabled 시만** 마운트. 저장 전 strategyId 없어 서버 `/scalein-plan` 호출 불가 → **client-side 순수 산식** `buildScaleInPlanPreview`로 회차별 표 렌더 | `StrategySaveModal.tsx` L682~L690 · `ScaleInPlanPreviewInline.tsx` |
| 회차 표 | `{N}차` + 비중(`weightPct`%) + 예상 진입가(`expectedEntryPrice`) + 진입 조건(`triggerDescription`). **PRICE_DROP_PCT는 기준가×(1+drop/100)^n 복리**, SIGNAL_REPEAT는 텍스트만(예상가 미정) | `scaleInPlanPreviewDisplay.ts` |
| 예상 손절가 | 기준가×(1−손절%) red 카드. 손절 미설정/기준가 미입력 분기 정직 표기 | `ScaleInPlanPreviewInline.tsx` L88~L99 |
| floor 알람선 | `floorPct` amber 카드 + `floorAutoLiquidate` true → "자동 청산" / false → "알람만 · 자동 청산 아님"(R3 정합). floor 비활성 분기 | `ScaleInPlanPreviewInline.tsx` L101~L118 |
| 가드 | 0% 회차 제외(⑨) · **수량 미표기**(base 예산 미상) · "예상/미리보기 · 저장 안 됨" 명시 · "미리보기 기준가"(`previewEntryPrice` default 100000, 저장 X) 미입력 시 안내. **mock 합성 0건** | `ScaleInPlanPreviewInline.tsx` L50~L51·L82~L86 |

#### 5-2-H-2. (B) floor 필드 항상노출 + prefill + 자동청산 토글 (갭①②)

| 항목 | 동작 | 권위 |
|------|------|------|
| floor% 입력 항상 노출 | scale-in enabled 시 **catastrophic floor% 입력을 항상 노출**(조건부 숨김 폐지). 값 미설정(null)이면 `useScaleInDefaults`(`GET /api/strategies/scale-in-defaults` → `{floorDefaultPct}` LOCKED 계약 · 실패 시 graceful fallback 15)로 **prefill**. 0(의도적 비활성)은 prefill 제외 | `ScaleInExitPanel.tsx` L70~L79·L267~L296 |
| floorAutoLiquidate 토글 | 체크박스. default false=알람만("청산은 수동 판단 · 권장") / true=amber 경고("floor 도달 시 자동 전량 청산 — 스윙 반등을 놓칠 수 있음") | `ScaleInExitPanel.tsx` L297~L325 |
| 저장 payload | `ScaleInDto.stopLossFloorPct?`·`floorAutoLiquidate?` 명시 전송(null 전송 시 백엔드 yml default 재주입). floor%가 분할 최저 진입가보다 얕으면 저장 거부(PRICE_DROP_PCT 힌트) | `StrategySaveModal.tsx` L314~L316 · `ScaleInExitPanel.tsx` L314 |

#### 5-2-H-3. (C) RiskPlan UX 정돈 — CollapsibleSection 그룹핑

- `CollapsibleSection`(접기/펼치기 · `aria-expanded`/`aria-controls` · ▸ 회전)으로 SaveModal 그룹핑. **필수**(이름·종목·손절·익절·미리보기 기준가) 펼침 default, **고급**(최소 보유 캔들·VI 정책 / 분할 매수·분할 익절) 접기 default(기존값 있으면 `defaultOpen` 자동 펼침). 입력·검증·payload 로직은 부모(SaveModal)가 보존 — 레이아웃만 담당.
- 권위: `CollapsibleSection.tsx` · `StrategySaveModal.tsx` L618~L691.

#### 5-2-H-4. (B) 조건 검증 인라인 피드백 + (D) 모바일 탭

| 항목 | 동작 | 권위 |
|------|------|------|
| 인라인 검증 | `validateBuilderTree`/`validateTreeRoot`가 직렬화 전 빈 슬롯·미완성 노드 카운트(comparison/logical/arithmetic는 left·right, not은 operand 필수 · 잎은 항상 완성). 결과 → Canvas 탭바 상태(완성/빈슬롯N)·DSL 미리보기 위 amber 경고 배너·검증/저장 버튼 disable(entry 기준) | `builderTreeValidation.ts` · `StrategyBuilderCanvas.tsx` L420~L470 |
| 모바일 탭 | `useMediaQuery('(min-width: 1024px)', true)` 단일 마운트 분기. 데스크톱 3분할 / 모바일(lg 미만) 탭(블록·조건·결과) 세로 스택. 결과 패널 JSX는 `resultsPanelContent`로 추출해 양 레이아웃 공유(중복 0) | `StrategyBuilderPage.tsx` L187~L193·L599~L600·L740~L866 · `useMediaQuery.ts` |

### 5-2-I. 전략 목록 UX ⭐ v3.14.0 (`/strategy/list` · 목록 클러스터)

> **권위**: `StrategyListPage.tsx` · `StrategyFilterBar.tsx` · `StrategyList.tsx` · `StrategyCard.tsx` · `StrategyRunStatusSummary.tsx` · `strategyCardBadges.ts` · `api/strategy.ts`(목록 q/sort) · `useSigStrategy.ts` · `useDebouncedValue.ts`. 목록 클러스터 add-only(빌더 클러스터 미수정).

| 영역 | 컴포넌트 | 동작 |
|------|---------|------|
| **검색** | `StrategyFilterBar` 검색 입력 | `useDebouncedValue` 300ms → `listQuery.q`(name/nameKo/code 부분일치). `type="search"` · `aria-label`. 공백이면 전송 생략 |
| **정렬** | `StrategyFilterBar` `<select>` | `StrategyListSort`(`recent`·`name`·`status`). 변경 시 0페이지 재시작(`useSigStrategies` queryKey에 q/sort 포함) · `recent`는 전송 생략 |
| **status 탭** | `StrategyFilterBar` `role="tablist"` | 전체·자동매매중·매수차단·중지·보호중지(`ALL`/`ACTIVE`/`BUY_BLOCKED`/`PAUSED`/`DISABLED`). ArrowLeft/Right 키 네비. status는 **client-side** 필터(`StrategyList`에서 적용) |
| **카드 배지** | `StrategyCard` + `strategyCardBadges.ts` | 한눈 배지: 분할매수(`scaleInBadgeLabel` → "분할 N회" indigo) · floor(`floorBadge` → `floorAutoLiquidate` true "floor 자동청산 -N%" red / false "floor 알람 -N%" amber · ADR 0128 R3). 기존 정보(DSL·RiskPlan·VI·포지션 패널) 손실 0 |
| **빠른 액션** | `StrategyCard` 액션 버튼 행 | 빌더에서 수정 · **복제**(`?strategyId=&duplicate=1` 라우팅) · 📊 성과·메모(모달) · **즐겨찾기 ★**(`useToggleListFavorite` · start/list 캐시 동시 무효화) · 시작/중지(상태 토글 · 차단 사유 시 백테스트 핸드오프 또는 toast) · 매수 재개/전체 중지(BUY_BLOCKED) · 삭제(확인) |
| **다중선택 일괄** | `StrategyList` 선택 모드 | "여러 개 선택" 토글 → 카드 체크박스 + 전체 선택 + **일괄 시작(emerald)·일괄 중지(amber)**. `useBulkToggleStatus`(기존 status endpoint client-side `Promise.allSettled` 루프 · 신규 백엔드 0 · 부분 실패 graceful 토스트). 필터/검색 변경 시 사라진 선택 자동 정리 |
| **상태 요약** | `StrategyRunStatusSummary` | 거래 모드·감시 중 전략·리스크 주문 기준·최근 자동 주문 4 카드 + **집계 칩**: 자동매매중/매수차단/중지(클릭 → `onJumpToFilter`로 status 탭 점프) · floor 알람 N(자동청산 제외 정보 칩) · 분할 진행 N(설정상 2회 이상 정보 칩) |

- 가드: read·운영 액션만(watchlist write 0 · 자동 진입 0). 빈 목록은 검색어/필터 별 정직 안내(mock 합성 0). 무한 스크롤(IntersectionObserver · 전역 polyfill 금지). 룰 5: LLM provider import 0건(`strategyCardBadges`는 순수 계산 · AI 진입점은 카드 하단 `AiExplainEntry` STRATEGY 유지).

---

## 5-3. 메뉴 매트릭스 (v3.13.0 nav 4 group 유지 · 전략 그룹 재배열)

### 5-3-A. AppHeader nav (권위: `AppHeader.tsx` L43~L85)

> **★ v3.14.0 변경** — 4 group 구조 불변. **전략 그룹만 "전략 목록"을 그룹 dropdown 첫 항목으로 승격**(2026-06-17 사용자 결재 · `a96a32ae` — top-level 승격 `54ee2b3d` 후 그룹 첫 항목으로 정정). 그룹 dropdown hover 펼침(180ms close delay) + 버튼 click → 첫 아이템 이동. "대시보드"·"매매일지"는 상시 표시(`TopNavLink`).

| 위치 | 아이템 (label / to) | 비고 |
|------|----------------------|------|
| **상시 (좌)** | 대시보드(`/`, `end`) | `TopNavLink` |
| **시장** (그룹) | 종목검색(`/instruments`) · 포지션(`/positions`) · 주문 내역(`/orders`) · 시장 추세(`/market/trend`) · 시장 캘린더(`/market/calendar`) | 5 item · `/symbol/:code` 는 `/instruments` active 매핑 |
| **전략** (그룹) ⭐ 재배열 | **전략 목록(`/strategy/list`) ⭐첫 항목** → 단타 발굴(`/strategy/daytrade-discover`) → 전략 리서치(`/strategy/research`) → 전략 빌더(`/strategy/builder`) → 백테스트(`/strategy/backtest`) → 모의 트레이딩(`/strategy/paper`) → 발굴 스코어카드(`/strategy/scorecard`) → 시나리오 리플레이(`/strategy/scenario-replay`) | **8 item** · 전략 목록을 운영 진입 최상단으로 |
| **리스크** (그룹) | 리스크 설정(`/risk-config`) · 위반 이력(`/risk-events`) | 2 item |
| **운영** (그룹) | 실시간 상태(`/admin/realtime`) · 시스템 이벤트(`/system`) | 2 item · 최고 관리자 장애 처리용 |
| **상시 (우)** | 매매일지(`/journal`) | `TopNavLink` |

### 5-3-B. AppHeader 우측 indicator·button (v3.13.0 동일)

`RuntimeFlagsIndicator`(로고 옆) · `WsConnectionIndicator` · `KisWsHealthIndicator` · `NotificationCenter`(벨+badge · 전역 STOMP 구독) · Settings button(톱니→SettingsModal) · 헤더 상단 `KisRealCredentialBanner`(실전 키 미등록 노란 배너).

### 5-3-C. SettingsModal 내부 섹션 (v3.13.0 동일 — 5 섹션)

`RealTradingConsentSection` · `KisCredentialSection` · `SystemConfigSection` · `KillSwitchSection` · `TrendPreferenceSection`. 권위: `components/settings/SettingsModal.tsx`.

### 5-3-D. 전략 빌더 SaveModal 섹션 구조 ⭐ v3.14.0 (권위: `StrategySaveModal.tsx`)

> CollapsibleSection 그룹핑(5-2-H-3). **필수 펼침**(이름·종목·인터벌·손절·익절·미리보기 기준가 + RiskPlanPreviewPanel + 잔익절 비용 경고) → **고급 — 보유·VI 정책**(접기 · 최소 보유 캔들·VI 발동 정책) → **고급 — 분할 매수 / 분할 익절**(접기 · `ScaleInExitPanel` + floor 필드·자동청산 토글 + `ScaleInPlanPreviewInline` 프리뷰). 기존값 있으면 고급 섹션 자동 펼침. `useModalBehavior` 정합.

### 5-3-E. Symbol Detail 내부 탭 / 토글 (v3.13.0 동일)

Interval · CandleCount · Indicator overlay · Pattern · AI 진입점 SYMBOL/CHART + 호가창 "표시 중에만" 구독. 상세는 v3.13.0 5-3-E 참조.

---

## 5-4. 상태 매트릭스 (v3.13.0 유지 + 신규)

> Price Card 장 구분 badge · Tick stale badge · Order status · KillSwitch state · RuntimeFlags/Mode · AiCard 7 상태 · OrderTrigger 발사 출처(5-4-J v3.13.0) · 실시간 구독 상태 배너(5-4-K) · 페이퍼 전략·포지션 상태(5-4-L)는 이전 인벤토리 그대로 유지.

### 5-4-M. 전략 카드 배지 ⭐ v3.14.0 (권위: `strategyCardBadges.ts`)

| 배지 | 조건 | 색 |
|------|------|----|
| 분할 N회 | `scaleIn.totalSlices >= 2` | indigo |
| floor 자동청산 -N% | `stopLossFloorPct > 0` && `floorAutoLiquidate === true` | red |
| floor 알람 -N% | `stopLossFloorPct > 0` && `floorAutoLiquidate !== true` (default) | amber |

- `floorMode` → `NONE`/`ALARM`/`AUTO_LIQUIDATE`. 회차별 실시간 진행(N/M·filledLots)은 `StrategyPositionPanel` 폴링이 담당하고, 본 헬퍼는 "전략 설정상" 분할/floor 여부만 파생(순수 계산 · mock 0).

### 5-4-N. 빌더 캔버스 조건 검증 상태 ⭐ v3.14.0 (권위: `builderTreeValidation.ts`)

| 상태 | 의미 |
|------|------|
| empty | 블록 미추가(루트 없음) |
| emptySlotCount > 0 | 비어 있는 슬롯 N개 → amber 경고 배너 + 검증/저장 disable(entry 기준) |
| complete | `empty=false && emptySlotCount=0` → 검증·저장 가능 |

### 5-4-O. floor 입력·자동청산 토글 상태 ⭐ v3.14.0 (권위: `ScaleInExitPanel.tsx`)

floor% 미설정(null) → `floorDefaultPct`(서버 또는 fallback 15)로 prefill 힌트 / 0 → 사용자 의도적 비활성(prefill 제외). 토글 OFF → "알람만 · 청산 수동"(권장) / ON → amber "자동 전량 청산 · 스윙 반등 놓칠 수 있음".

---

## 5-5. STOMP 토픽 패밀리

> v3.13.0 토픽 매트릭스 그대로 유지(실시간 시세 4 · 시장 추세 3 · 계좌/주문 2 · 시스템 2 · AI 1 + 종목 상세 호가 "표시 중에만"). v3.14.0 신규 STOMP 토픽 0 — 빌더·목록 고도화는 REST(`/api/strategies` q·sort · `/api/strategies/scale-in-defaults` · `/api/strategies/{id}/scalein-plan` · 일괄 status 루프) + 기존 `useStompSignal`(전략 목록 실시간 시그널 prepend) 재사용. 상세 매트릭스는 v3.13.0 5-5 참조.

---

## 5-6. TradingView LWC v4 통합 (frontend 단독 책임 · ADR 0094 §2-2-A / ADR 0085)

v3.13.0 책임 라인 유지(Symbol Detail 차트 · MarketTrend 지수 차트 · 전략 리서치 차트). v3.14.0 신규 LWC 컴포넌트 없음. 상세는 `_workspace/baseline/v3.12.0/05-ui-inventory.md` 5-6 참조.

> chart-curator agent 정의는 `.claude/agents/_archive/chart-curator.md` (역사 보존 · dispatch 0).

---

## 5-7. API 패밀리 (v3.13.0 대비 신규·확장 모듈)

| 모듈 | 신규/확장 | 핵심 endpoint / 타입 |
|------|----------|----------------------|
| `api/strategy.ts` (`list` q·sort) | ⭐ v3.14.0 확장 | `GET /api/strategies?q=&sort=` · `StrategyListQuery`·`StrategyListSort`(`recent`\|`name`\|`status`) · `ScaleInDto.stopLossFloorPct?`·`floorAutoLiquidate?` 필드 추가 |
| `api/hooks/useScaleInDefaults.ts` | ⭐ v3.14.0 | `GET /api/strategies/scale-in-defaults` → `{floorDefaultPct}` (실패 시 fallback 15 · 빌더 floor prefill) |
| `api/hooks/useSigStrategy.ts` (`useSigStrategies`·`useBulkToggleStatus`·`useToggleListFavorite`) | ⭐ v3.14.0 확장 | optional `{q, sort}` 인자(queryKey 포함 · 0페이지 재시작) · 일괄 토글 client-side `Promise.allSettled` 루프 · 목록 즐겨찾기 토글 |
| `api/strategyPosition.ts` (`getScaleInPlan`) | (v3.13.0 cut 직후) | `GET /api/strategies/{id}/scalein-plan?currentPrice=` → `ScaleInPlanDto`(저장 후 화면용 — 빌더 프리뷰와 별개) |
| `adminRealtime`·`discoveryScorecardApi`·`daytradePaperApi`·`signal`(trace)·`order`(trigger)·`system-event` 등 | (v3.13.0 유지) | v3.13.0 동일 |
| account·symbol·strategy·risk·order·journal·market·watchlist·position·indicator·patterns·dslTranslate·runtime·onboarding·trendlines·stompBackfill·instrument·backtest 등 | (유지) | v3.13.0 동일 |

---

## 5-8. 본 문서가 의도적으로 다루지 않는 것 (TBD / 차회 baseline)

- **Playwright 시각 baseline 갱신** — v3.14.0 빌더 모달 접기·모바일 탭·목록 검색/정렬/배지로 시각 회귀 diff 발생 가능(S12 분할계획 패널 baseline은 선행 — `3240f538`). spec 추가·snapshot 갱신은 qa Visual 별도 사이클(본 baseline 범위 외 · 헌법 §9-2).
- **분할매수 리스크풀 분리** — "지정 목표금액 매집"(현 base 고정이 토대)은 미래 트랙(메모 `project_scalein_strategy_design.md`). 본 baseline은 현 base 고정 UI까지.
- **MCP UI 노출 (T-MCP)** — backend MCP 서버 expose 유지, frontend 전용 진입 화면 미정착 (v3.13.0 동일).
- **Multipane MTF · LWC plugin** — W5 T-LWC.
- **screenshot description** (`_workspace/frontend/screenshot-desc/`) — Playwright 시각 baseline 이 raw evidence (본 baseline 은 5-2~5-3 표가 SoT).

---

## 5-9. 권위 출처 (raw 인용 LOCK)

| 항목 | raw 출처 |
|------|---------|
| route 정의 (`<Route>` 28건 · 실 path 25) | `frontend/src/App.tsx` L132~L181 |
| nav 4 group + 상시 2 (전략 그룹 재배열) | `frontend/src/components/layout/AppHeader.tsx` L43~L85 |
| 전략 빌더 페이지(모바일 탭·결과 패널 공유) | `frontend/src/pages/Strategy/StrategyBuilderPage.tsx` L187~L193·L599~L600·L740~L866 |
| 빌더 저장 모달(섹션 접기·프리뷰·floor payload) | `frontend/src/components/strategy/StrategySaveModal.tsx` L314~L316·L577~L691 |
| 분할 매수/익절 패널(floor 항상노출·prefill·자동청산 토글) | `frontend/src/components/strategy/ScaleInExitPanel.tsx` L70~L79·L267~L325 |
| 저장 전 분할계획 프리뷰 | `frontend/src/components/strategy/ScaleInPlanPreviewInline.tsx` · `scaleInPlanPreviewDisplay.ts` |
| 접기 섹션 | `frontend/src/components/strategy/CollapsibleSection.tsx` |
| 빌더 조건 인라인 검증 | `frontend/src/components/strategy/builderTreeValidation.ts` · `StrategyBuilderCanvas.tsx` L420~L470 |
| 반응형 분기 | `frontend/src/hooks/useMediaQuery.ts` |
| 전략 목록 페이지 | `frontend/src/pages/Strategy/StrategyListPage.tsx` |
| 목록 필터 바(검색·정렬·status 탭) | `frontend/src/components/strategy/StrategyFilterBar.tsx` |
| 목록 그리드(다중선택 일괄 토글) | `frontend/src/components/strategy/StrategyList.tsx` |
| 전략 카드(배지·빠른 액션·복제·즐겨찾기) | `frontend/src/components/strategy/StrategyCard.tsx` |
| 카드 배지 파생 헬퍼 | `frontend/src/components/strategy/strategyCardBadges.ts` |
| 상태 요약 칩 점프 | `frontend/src/components/strategy/StrategyRunStatusSummary.tsx` |
| 목록 q/sort 계약 + scale-in 필드 | `frontend/src/api/strategy.ts` L349·L357·L374 |
| 목록 hooks(q/sort·일괄·즐겨찾기) | `frontend/src/api/hooks/useSigStrategy.ts` |
| floor default 조회 hook | `frontend/src/api/hooks/useScaleInDefaults.ts` |
| AI advisory-only 원칙 | ADR 0026 · ADR 0112 |
| floor 운영 default 외부화 + 자동청산 토글 | ADR 0128 amend R3 |
| 차트 책임 이관 결정 | ADR 0085 · ADR 0094 amend 2026-05-21 |
| 관련 PR | #1069(목록 검색·정렬 api) · #1070(빌더 uplift) · #1067(floor 필드·자동청산 토글) · 전략 목록 UX(`80c9a69f`) · 빌더 복제 fix(`c2a59bb6`) · 전략 목록 nav 정정(`a96a32ae`) |

— frontend baseline v3.14.0 작성 END —
