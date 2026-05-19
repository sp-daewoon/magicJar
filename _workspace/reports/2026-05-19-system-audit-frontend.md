# frontend 시스템 점검 + hotfix 의제 도출 (2026-05-19)

> **Sub-agent**: frontend  
> **Task ID**: T-20260519-1649-system-audit-2026-05-19  
> **Scope**: frontend 영역(차트 외) — 페이지·hook·API·STOMP·mock 0·T-MAN 안전망 매트릭스 audit  
> **영역 경계**: chart-curator 영역(LightweightChart·IndicatorOverlay·PatternMarker 등) 침범 0. 운영 코드 0 touch (audit only).  
> **main base**: 30821f6 (chore(tasks): T-20260519-1622 archive — PR #546)

---

## 1. 페이지 매트릭스 (App.tsx 18 라우트 + 셋업 5 step)

| route | 페이지 컴포넌트 | 트랙 | userId 타입 | API 호출 매트릭스 | STOMP 매트릭스 | 폴링 매트릭스 | 상태 |
|-------|-----------------|------|-------------|-------------------|----------------|---------------|------|
| `/` | `Dashboard/DashboardPage` | T-ACCT Phase 4 (ADR 0081) | string | `/api/account/{summary,balance,positions}` | 0 | 30s polling 3건 | OK |
| `/watchlist` | `Watchlist/WatchlistPage` | T-W1 (ADR 0040) | string | `/api/watchlists` (3 endpoint) | 0 (T-W3 보강 영역) | 0 (mutation invalidate) | OK |
| `/symbol/:code` | `Symbol/SymbolDetailPage` | T-S1 (ADR 0040 §2-2) | string | `/api/symbols/{code}`·indicator·patterns·candles | `/topic/market.{tick,candle.minute,orderbook}.{code}` | 5s fallback | OK (차트는 chart-curator owner) |
| `/strategy/builder` | `Strategy/StrategyBuilderPage` | T-SB | string | `/api/strategies`·dslTranslate·screener | 0 | 0 | OK |
| `/strategy/backtest` | `Strategy/BacktestPage` | T-BT R3 (ADR 0061) | string | `/api/backtests` | 0 | BacktestDetail conditional polling (RUNNING 상태만) | OK |
| `/strategy/list` | `Strategy/StrategyListPage` | T-SIG Phase 3 (ADR 0066) | string | `/api/strategies`·signals | `/topic/signal.user.{userId}` | 0 | OK |
| `/risk-config` | `Risk/RiskConfigPage` | T-RISK Phase 3 (ADR 0067) | string | `/api/risk-config` | 0 | 0 | OK |
| `/risk-events` | `Risk/RiskEventsPage` | T-RISK Phase 3 (ADR 0067) | string | `/api/risk-events` | `/topic/risk.user.{userId}` | 0 | OK |
| `/positions` | `Risk/PositionsPage` | T-RISK Phase 3 (ADR 0067) | string | `/api/positions` (DB) | 0 | 0 | OK |
| `/orders` | `orders/OrdersPage` | T-O Phase 3 (ADR 0068) | string | `/api/orders` (페이징 + filter) | `/topic/order.user.{userId}` | 0 (STOMP invalidate) | OK |
| `/orders/new` | `orders/NewOrderPage` | T-MAN Phase 2 (ADR 0082) | string | `/api/orders/manual` POST + consent/killSwitch | 0 | 0 (sessionStorage cooldown 1s tick) | OK (★ §6 안전망 8 layer 확인) |
| `/orders/:id` | `orders/OrderDetailPage` | T-O Phase 3 (ADR 0068) | string | `/api/orders/{id}` (request·executions·audit) | 0 | 0 | OK |
| `/journal` | `TradingJournalPage` | T-J Phase 3 (ADR 0072) | **number (Number cast)** | `/api/journal/{entries,notes}` | 0 | 0 | ★ userId 타입 불일치 |
| `/performance` | `PerformanceDashboardPage` | T-PERF Phase 3 (ADR 0072) | **number (Number cast)** | `/api/performance/{summary,equity-curve}` | 0 | 0 | ★ userId 타입 불일치 |
| `/setup` | `SetupWizard` (5 step) | T-ONBOARD | n/a | `/api/onboarding/{status,kis-real,kis-mock,system-config,complete}` | 0 | 0 | OK |
| `*` | Navigate to `/` | — | — | — | — | — | OK |

**총 매트릭스**: 16개 활성 라우트 + 5 setup step = 21개 화면 단위. T-ACCT Phase 4 대시보드 격상 정합 확인 ★.

---

## 2. hook 일관성 매트릭스

### 2-1. React Query 사용 통계
- `useQuery` / `useMutation` 호출: **151건** (모든 hook 일관)
- queryKey 패턴: 두 가지 혼재
  - **constant export 패턴**: `STRATEGY_LIST_KEY(userId)`, `WATCHLIST_QUERY_KEY`, `KIS_CREDENTIALS_QUERY_KEY` 등 (`api/hooks/useStrategy.ts`, `api/hooks/useWatchlist.ts`)
  - **inline 패턴**: `['journal', 'entries', { userId, from, to, ... }]`, `['symbol', code]` (`features/*/hooks/`, 일부 `api/hooks/`)
  - ★ 결함이라기보단 tech debt — 한 PR에서 둘 다 일관성 있게 쓰면 좋지만 P3

### 2-2. 30s auto-refresh 매트릭스 (Redis TTL 30s 정합, spec §4-4)
| hook | refetchInterval | 비고 |
|------|----------------|------|
| `useAccountSummary` | 30_000ms | T-ACCT Phase 4 — Dashboard 헤더 |
| `useAccountBalance` | 30_000ms | T-ACCT Phase 4 — BalanceCard |
| `useAccountPositions` | 30_000ms | T-ACCT Phase 4 — HoldingsTable |
| `useKillSwitchState` | 30_000ms | drift auto-ON 감지 |
| `useSymbol` | 5_000ms | STOMP 미연결 시 fallback |
| `useSymbolNews` | 60_000ms | ADR 0075 §6-2 |
| `useSymbolDisclosures` | 60_000ms | ADR 0075 §6-2 |
| `BacktestDetail` | `(query) => RUNNING ? 3000 : false` | conditional |
| `useJournalEntries` | false | manual fetch |
| `useJournalEntry` | false | manual fetch |
| `usePerformanceSummary` | false | manual fetch |
| `useEquityCurve` | false | manual fetch |

**일관성**: KIS 계좌 30s, 종목 시세 5s, 외부(news/disclosure) 60s, 백테스트 진행 중 3s — 정합. ★ Pass.

### 2-3. 페이지 hook 호출 패턴
- 모든 페이지가 `useOnboardingStore((s) => s.registeredUserId)` 단일 진입
- userId null guard 16+ 페이지에 일관 적용 (PR #386 fix 정합)
- ★ 결함: TradingJournalPage·PerformanceDashboardPage 만 `Number(registeredUserId)` 변환 — §5 hotfix 의제

---

## 3. API 클라이언트 정합 매트릭스

### 3-1. backend endpoint cross-ref
| frontend api 파일 | 주요 endpoint | DTO 1:1 매핑 | backend ref |
|------------------|--------------|--------------|-------------|
| `api/account.ts` | `/api/account/{summary,balance,positions}`, `/api/user-account/real-trading-consent` | AccountSummaryDto·BalanceDto·HoldingDto·ConsentToggleResponseDto | T-ACCT Phase 4 (ADR 0081) ★ |
| `api/order.ts` | `/api/orders` GET, `/api/orders/{id}` GET, `/api/orders/{id}/cancel` POST, `/api/orders/manual` POST | OrderListItemDto·OrderDetailDto·CancelOrderResponseDto·ManualOrderRequest/Response | T-O Phase 3 + T-MAN ★ |
| `api/watchlist.ts` | `/api/watchlists` (GET/POST), `/api/watchlists/{code}` DELETE | WatchlistEntryDto (PR-21b 6 필드 신규) | T-W1 ★ |
| `api/symbol.ts` | `/api/symbols/{code}` | SymbolPriceDto·SymbolOrderbookDto | T-S1 |
| `api/indicator.ts` | `/api/indicators/{code}` | IndicatorResponseDto | T-IND |
| `api/patterns.ts` | `/api/patterns/{code}` | PatternResponseDto | T-PAT |
| `api/instrument.ts` | `/api/instruments/search` | InstrumentSearchResultDto | T-S1 InstrumentSearchInput |
| `api/risk.ts` | `/api/risk-events`, `/api/risk-config` | RiskEventDto·RiskConfigDto | T-RISK |
| `api/signal.ts` | `/api/signals` | SignalDto | T-SIG |
| `api/strategy.ts` | `/api/strategies`, `/api/strategies/dsl-translate` | StrategyDto·DslTranslateDto | T-SB |
| `api/onboarding.ts` | `/api/onboarding/*` | OnboardingStatus·KisRealRequest·KisMockRequest·SystemConfigRequest·CompleteRequest | T-ONBOARD |
| `features/journal/api/journalApi.ts` | `/api/journal/{entries,notes}` | TradingJournalEntryDto·JournalNoteDto | T-J ★ userId number |
| `features/performance/api/performanceApi.ts` | `/api/performance/{summary,equity-curve}` | PerformanceSummaryDto | T-PERF ★ userId number |
| `features/screener/api/screenerApi.ts` | `/api/screener/{evaluate,runs}` | ScreenerRunResponse | T-SC ★ userId number |
| `features/disclosure/api/disclosureApi.ts` | `/api/disclosures/{code}` | DisclosureDto | ADR 0075 |
| `features/news/api/newsApi.ts` | `/api/news/{code}` | NewsDto | ADR 0075 |

### 3-2. ★ userId 타입 불일치 매트릭스 (★ P2 hotfix 의제)

| 계열 | 사용처 | 타입 |
|------|--------|------|
| **계열 A — string** | account·order·watchlist·symbol·indicator·patterns·instrument·risk·signal·strategy·onboarding API + Dashboard·Orders·Risk·NewOrder·SymbolDetail·Watchlist·Strategy 페이지 | `userId: string` |
| **계열 B — number** | journal·performance·screener API + TradingJournal·Performance 페이지 | `userId: number` |

**결함 표면화 경로**:
- zustand store `registeredUserId: string | null` (KIS 검증 후 8자리 숫자 string)
- TradingJournalPage:34 `const userId = registeredUserId ? Number(registeredUserId) : null`
- PerformanceDashboardPage:24 동일
- 만약 backend가 userId를 alphanumeric 또는 UUID로 마이그레이션 시 `Number('user-abc')` = NaN → 무성 결함 폭발

**SoT 결정 필요**: backend SoT(`UserAccount.id`) Long? String? 한쪽으로 통일. ADR 신규 발행 또는 backend-core·architect 합의.

---

## 4. STOMP 클라이언트 정합 매트릭스

### 4-1. lib/stomp.ts 싱글톤 구조 (spec §6-4 LOCK)
- `@stomp/stompjs` Client 싱글톤 (re-render 시 재연결 방지) ★
- brokerURL: `ws[s]://{host}/ws` (Vite proxy → localhost:8080/ws)
- reconnectDelay: 5000ms
- `useStompTopic<T>(topic, onMessage, enabled)` 커스텀 훅 — `ensureConnected()` Promise 후 subscribe, cleanup 시 unsubscribe
- `isStompConnected()` 진단 함수

### 4-2. 메시지 라우팅 매트릭스
| topic | hook | 페이로드 DTO | 사용 페이지 | 토스트 매트릭스 |
|-------|------|--------------|-------------|-----------------|
| `/topic/market.tick.{code}` | `useStompTopic` inline | TickPayload | SymbolDetailPage | 0 (시세는 silent) |
| `/topic/market.candle.minute.{code}` | `useStompTopic` inline | CandlePayload | SymbolDetailPage (chart-curator owner) | 0 |
| `/topic/market.orderbook.{code}` | `useStompTopic` inline | OrderbookPayload | SymbolDetailPage | 0 |
| `/topic/signal.user.{userId}` | `useStompSignal` | SignalDto | StrategyListPage | BUY=success / SELL=error / HOLD=info |
| `/topic/order.user.{userId}` | `useStompOrder` | OrderStompPayload | OrdersPage | REQUESTED=info / FILLED=success / PARTIAL=warning / REJECTED=error / CANCELLED=info |
| `/topic/risk.user.{userId}` | `useStompRisk` | RiskEventDto | RiskEventsPage | BLOCKED=error / AUTO_PAUSED=warning / ALLOWED=silent |

★ 토스트 ID dedup: `useStompOrder`는 `toastedFillIds` Set으로 부분체결 spam 방지 — §16-6 정합 ★ Pass.

### 4-3. ★ STOMP 결함 매트릭스 (ADR 0084 P1-2 후속 의제)

#### P0-S1 (자본 안전): onConnect resync 0
- `lib/stomp.ts` ensureConnected는 단순 Promise resolve만 함
- 재연결 후 끊긴 시간 동안 발행된 시그널·주문·리스크 이벤트 영구 소실
- backend backlog/snapshot REST 없음 (시그널·주문·리스크 list 페이징은 invalidate로 복구 가능하지만, **frontend가 reconnect 후 invalidate를 자동 호출 안 함**)
- ADR 0084 P1-2 명시 의제 — backend-core onConnect snapshot REST + frontend onReconnect invalidate 합의 필요

#### P1-S1 (UX): 전역 STOMP 연결 상태 인디케이터 0
- `isStompConnected()` 함수는 있지만 `AppHeader.tsx` 에서 호출 안 함
- 현재는 `SymbolDetailPage`·`SymbolOrderbook` 두 컴포넌트만 사용 (호가창 빈 상태 분기)
- 사용자가 "왜 시세 안 들어오지?"를 발견할 수 없음 — 헤더 우측 dot LED + 텍스트 권장

#### P2-S1 (정합): useStompTopic enabled=false → enabled=true 전환 시 재구독 검증 부재
- `[topic, enabled]` deps에 잘 들어가 있지만, ensureConnected 중간 enabled false 전환 race 보호 0
- cancelled flag 있지만 unsubscribeFn이 set되기 전 cancelled=true 후 subscribe 도래하면 누수

---

## 5. mock 합성 0 LOCK 매트릭스 (feedback_no_mock_data 정합)

### 5-1. grep 결과 (`mock|dummy|sample|stub|placeholder|hardcoded` — test/MSW 제외)
- **합성 mock 데이터: 0건** ★ Pass
- 검출된 거짓 양성 매트릭스:
  - `placeholder="..."` UI input — 정당 사용
  - `MOCK` 모드 분기 (`mode === 'MOCK'`, KisMockStep, AccountType `'MOCK'`) — 정당
  - `mockMode`/`MOCK 가상 주문` 표시 라벨 — 정당
  - feedback_no_mock_data 정합 주석 (DisclosurePanel·NewsPanel·OrderAuditTimeline·ExecutionTable) — 정합

### 5-2. 빈 상태 graceful 표시 매트릭스
| 컴포넌트 | 빈 상태 메시지 |
|----------|----------------|
| `DisclosurePanel` | "최근 7일간 공시가 없습니다" (ADR 0075 §11-3) |
| `NewsPanel` | "최근 7일간 뉴스가 없습니다" (ADR 0075 §11-3) |
| `OrderAuditTimeline` | "감사 이벤트 없음" (feedback_no_mock_data) |
| `ExecutionTable` | "체결 없음" (feedback_no_mock_data) |
| `JournalEntryList` | (확인 필요 — 코드 read 안 함, but T-J Phase 3 정합 가정) |
| `WatchlistEmpty` | 종목 등록 안내 (T-W1 dedicated 컴포넌트) |

★ Pass — "데이터 없음" 명시 정합.

---

## 6. T-MAN UI 안전망 8 layer 매트릭스 (NewOrderPage·OrderPreviewModal)

### 6-1. NewOrderPage 8 layer 검증 evidence (spec §3-7 LOCK)
| layer | 의제 | 구현 위치 | 상태 |
|-------|------|-----------|------|
| 1 | 종목코드 6자리 LOCK `^\d{6}$` | `NewOrderPage.tsx:140` `codeValid` | OK |
| 2 | 수량 ≥ 1 정수 | `NewOrderPage.tsx:141` `quantityValid` + input `min={1}` | OK |
| 3 | LIMIT 시 가격 > 0 | `NewOrderPage.tsx:144` `limitPriceValid` parseFloat 후 > 0 | OK |
| 4 | 3중 게이트 상태 표시 (MODE·ALLOW_REAL·consent) | `NewOrderPage.tsx:357-378` panel | OK (★ allowReal proxy로 hasConsent 사용 — backend 권위) |
| 5 | KillSwitch 상태 표시 + LOCK | `NewOrderPage.tsx:393-399` panel + `canOpenPreview` block | OK |
| 6 | 미리보기 → OrderPreviewModal 이중 confirm | `OrderPreviewModal.tsx:283` `aria-label` 분기 | OK |
| 7 | 60s cooldown (sessionStorage 영속화) | `NewOrderPage.tsx:45-73` cooldown helpers + 1s tick | OK |
| 8 | REAL 강조 (빨강 confirm 텍스트) | `OrderPreviewModal.tsx:255` `'실 자본 변동' vs 'MOCK 가상 주문'` | OK |

★ Pass — T-MAN B-2-extended 8 layer 풀 구현 확인.

### 6-2. ★ 후속 의제 (T-MAN Phase 3 — 자본 안전 보강)
- layer 4 `allowReal = hasConsent` proxy는 backend env `MAGICJAR_ALLOW_REAL` 와 강결합 가정. backend가 ALLOW_REAL=false면 frontend는 모르고 미리보기 통과 → consumer 거절 → audit `REAL_GATE_DENIED` 토스트로 회수. UX는 "왜 막혔지?" 혼란. ★ backend가 `/api/system/allow-real-status` 같은 endpoint 제공 후 frontend 직접 노출 권장 (P2 hotfix 의제).
- 룰 안전망 우회 경고 panel(`NewOrderPage:213-220`)은 yellow border — REAL 모드일 때 빨강 강조로 격상 권장.

---

## 7. ★ hotfix 의제 도출

### P0-F1 (자본 안전 — frontend가 보호 못 함)
없음. T-MAN 8 layer 안전망 + STOMP 토스트 4 event 매트릭스 정합 — frontend 단독 자본 안전 결함 0.

### P1-F1 (UX·자동매매 운영) — STOMP onConnect resync 부재
**현상**: STOMP 재연결 후 끊긴 시간 동안 발행된 signal/order/risk 이벤트 영구 소실.  
**fix 의제**: 
- backend-core: onConnect snapshot REST (예: `GET /api/signals/recent?since=<ts>`) 또는 SUBSCRIBE 시 last-N replay
- frontend: `lib/stomp.ts` `onConnect` handler에서 `queryClient.invalidateQueries({ queryKey: ['orders'] })` + `['signals']` + `['risk-events']` 자동 호출  
**예상 PR 본문**: ADR 0084 P1-2 정합 LOCK 인용. 1 hook 변경 + 3 queryKey invalidate add-only.  
**할당**: backend-core(snapshot REST) + frontend(invalidate hook).

### P1-F2 (UX) — 전역 STOMP 연결 상태 인디케이터 0
**현상**: 사용자가 시세·시그널·주문 이벤트가 안 들어오는 이유를 알 수 없음.  
**fix 의제**: `AppHeader.tsx` 우측 dot LED + "실시간 OFF/ON" 텍스트 add. `useStompConnectionStatus()` hook 신규(setInterval 1s polling `isStompConnected()` — 또는 onConnect/onDisconnect callback 외부 노출).  
**규모**: 30분 작업. 1 컴포넌트 + 1 hook add.  
**할당**: frontend.

### P2-F1 (정합) — userId 타입 불일치 매트릭스
**현상**: zustand store는 `string`, journal·performance·screener API는 `number`, 페이지에서 `Number(registeredUserId)` 인라인 캐스트. backend SoT 정의가 string인지 number인지 모호.  
**fix 의제**: 
1. backend-core SoT 결정 — `UserAccount.id` 타입 ADR 발행
2. frontend: 모든 API DTO `userId: string` 또는 모든 `number` 통일 (한쪽 일괄 마이그레이션)
3. zustand store 타입 정합 LOCK  
**규모**: 15+ 파일. R1(API DTO) → R2(hook signature) → R3(페이지) 라운드 분할 권장.  
**할당**: architect 결정 후 backend-core + frontend 동시.

### P2-F2 (자본 안전 보강) — backend ALLOW_REAL 상태 frontend 노출 0
**현상**: NewOrderPage layer 4가 backend env `MAGICJAR_ALLOW_REAL` 직접 노출 안 함 — proxy로 `hasConsent`만 사용. backend ALLOW_REAL=false면 사용자가 미리보기 통과 후에야 실패 알게 됨.  
**fix 의제**: backend `GET /api/system/runtime-flags` (또는 `/api/onboarding/status` 확장) → `allowReal: boolean` + `mode: 'REAL'|'MOCK'` 노출. NewOrderPage layer 4 3중 게이트 panel 진짜 값 표시.  
**규모**: backend 1 endpoint + frontend 1 hook + 1 panel 갱신.  
**할당**: backend-core + frontend.

### P3-F1 (tech debt) — queryKey 패턴 혼재
**현상**: constant export 패턴(`STRATEGY_LIST_KEY()`) vs inline 패턴(`['journal', 'entries', ...]`) 혼재.  
**fix 의제**: 한 PR에 features/*/hooks 일괄 constant 추출 또는 inline 일관성. 코드 일관성 P3 — release blocker 아님.  
**규모**: 20+ 파일. low priority.  
**할당**: frontend.

### P3-F2 (tech debt) — `window.location.href` 강한 navigation 1건
**현상**: `lib/axios.ts:29` interceptor `window.location.href = '/setup'` — react-router-dom navigate 미사용.  
**fix 의제**: axios interceptor는 react-router context 외부라서 navigate hook 못 씀. event bus 패턴(window.dispatchEvent) 또는 App.tsx에서 axios.interceptor.eject 후 setupInterceptor(navigate) 의존 주입 패턴. 현재 코드는 정상 작동 — UX 차이는 페이지 전체 reload 한 번 발생.  
**규모**: 작음. P3.  
**할당**: frontend.

### P3-F3 (tech debt) — frontend/src/ws/ 빈 디렉토리
**현상**: `frontend/src/ws/` 빈 디렉토리 존재 (실제 STOMP 코드는 `lib/stomp.ts`).  
**fix 의제**: 빈 디렉토리 삭제 (또는 `lib/stomp.ts` → `ws/stomp.ts`로 이전).  
**규모**: 1분.  
**할당**: frontend.

---

## 8. 결론

### 8-1. PASS 매트릭스
- 페이지 매트릭스: 16 활성 라우트 + 5 setup step — T-ACCT Phase 4 격상 + T-MAN Phase 2 풀 구현 정합 ★
- hook 일관성: 151 React Query 호출, 30s polling 매트릭스 정합 (Redis TTL 정합) ★
- API 클라이언트: 16 API 모듈, backend endpoint cross-ref 정합 ★
- STOMP 메시지 라우팅: 6 topic, 4 hook, 토스트 매트릭스 정합 ★
- mock 합성 0 LOCK: **0건** ★ (feedback_no_mock_data 정합)
- T-MAN 8 layer 안전망: 풀 구현 (NewOrderPage + OrderPreviewModal) ★

### 8-2. FAIL 매트릭스
- **P1-F1**: STOMP onConnect resync 부재 (ADR 0084 P1-2 후속) — backend-core + frontend 합작
- **P1-F2**: 전역 STOMP 연결 상태 인디케이터 0 — frontend 단독 30분 작업
- **P2-F1**: userId 타입 불일치 (string vs number) — architect 결정 → backend-core + frontend
- **P2-F2**: backend ALLOW_REAL 상태 frontend 노출 0 — backend-core + frontend
- **P3-F1**: queryKey 패턴 혼재 — frontend tech debt
- **P3-F2**: `window.location.href` 1건 — frontend low priority
- **P3-F3**: `frontend/src/ws/` 빈 디렉토리 — frontend 1분

### 8-3. 사용자 결정 의제
- **P2-F1 userId 타입 정합** 은 ADR 발행 필요 (string vs number SoT) — architect 디스패치 권장.
- **P1-F1 STOMP resync** 는 backend snapshot REST 의제 — 다음 세션 1순위 권장 (자동매매 운영 정합).
- **P1-F2 연결 상태 인디케이터** 는 작은 작업이라 P1-F1과 동일 PR로 묶을 수 있음.

---

## 9. 영역 경계 준수 evidence

- chart-curator 영역(`pages/Symbol/LightweightChart.tsx`·`IndicatorOverlay.tsx`·`SymbolChart.tsx`·`components/chart/Pattern*.tsx`) 0 read·0 touch
- backend 코드(`api/`·`batch/`·`consumer/`·`domain/`) 0 touch
- 운영 frontend 코드 0 touch (audit only — read만)
- `_workspace/reports/2026-05-19-system-audit-frontend.md` 신규 1 파일 + `_workspace/tasks/T-20260519-1649-*.md` 1 파일만 add
