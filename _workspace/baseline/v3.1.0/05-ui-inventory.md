# 05. UI 화면·메뉴·버튼 인벤토리 (v3.1.0)

> 기준일: 2026-05-08 | 작성 에이전트: frontend (sonnet)
> 구현 위치 기준: `frontend/src/` (React 19 + Vite + Tailwind + LWC v4)
> 코드 그대로 인용 원칙: 추측 0건, 모든 기술 사항은 실제 파일 + 라인 명시

---

## §1 라우팅 매트릭스

> 구현 위치: `frontend/src/App.tsx:84-99`

### 라우트 정의 표

| path | 컴포넌트 | 진입 조건 | 보호 가드 |
|------|---------|---------|---------|
| `/` | `DashboardPlaceholder` | 셋업 완료 상태 | `OnboardingGuard` — `hasUserAccount=false`이면 `/setup`으로 redirect |
| `/watchlist` | `WatchlistPage` | 셋업 완료 상태 | 동일. ADR 0040 §2-4 — Watchlist first UI |
| `/symbol/:code` | `SymbolDetailPage` | 셋업 완료 상태 | 동일. `code` path param 필수 |
| `/setup` | `SetupWizard` | 셋업 미완 또는 직접 진입 | `OnboardingGuard` — `hasUserAccount=true`이면 `/`로 redirect (중복 진입 차단) |
| `*` | (없음) | 미정의 경로 | `Navigate to="/" replace` |

### OnboardingGuard 분기 로직

> 구현 위치: `frontend/src/App.tsx:31-65`

```
GET /api/onboarding/status
  └─ isLoading → 스플래시 스피너 (bg-gray-950 전체화면)
  └─ isError   → children 그대로 렌더 (graceful — /setup 접근 허용)
  └─ hasUserAccount=false + path !== '/setup' → Navigate to="/setup"
  └─ hasUserAccount=true  + path === '/setup' → Navigate to="/"
  └─ else → children 렌더
```

### AppLayout 조건

> 구현 위치: `frontend/src/App.tsx:67-82`

- `/setup` 경로: AppHeader 미표시, 컨텐츠만 full-page 렌더
- 그 외: `<AppHeader />` sticky 상단 + `<main>` 하단

### 화면 전이도

```mermaid
graph LR
  A([브라우저 진입]) --> B{OnboardingGuard}
  B -- hasUserAccount=false --> S[/setup - SetupWizard]
  B -- hasUserAccount=true --> D[/ - DashboardPlaceholder]
  B -- isLoading --> L[스플래시 스피너]
  L --> B

  S -- Step5 시작하기 --> D
  D -- 관심종목 시작하기 버튼 --> W[/watchlist - WatchlistPage]
  D -- AppHeader 관심종목 메뉴 --> W
  W -- WatchlistRow 클릭 --> SYM[/symbol/:code - SymbolDetailPage]
  SYM -- 관심종목으로 버튼 --> W
  SYM -- SymbolError 관심종목으로 버튼 --> W

  AppHeader -- 대시보드 메뉴 --> D
  AppHeader -- 관심종목 메뉴 --> W
```

---

## §2 화면별 인벤토리

### 2-1. DashboardPlaceholder (`/`)

> 구현 위치: `frontend/src/pages/DashboardPlaceholder.tsx`

**진입 경로**
- 앱 최초 진입 + `hasUserAccount=true`
- AppHeader "대시보드" NavLink
- SetupWizard Step5 "시작하기" 완료 후 redirect (`/`)

**표시 영역**

| 영역 | 내용 |
|------|------|
| 로고 | `magic<span class="text-blue-400">Jar</span>` 브랜드 텍스트 + 시스템 설명 |
| 안내 카드 | h1 "메인 대시보드" + PR-7+ 예정 안내 텍스트 |
| 예정 화면 목록 | 관심종목(T-W1) · LWC v4 차트(T-LWC) · 전략 빌더(T-SB) · AI 옵션 콘솔(T-AI) 4항목 |
| CTA 버튼 | "관심종목 시작하기 →" |
| 버전 표시 | "v3.0.1 · PR-5b 스캐폴드" |

**버튼 인벤토리**

| 버튼명 | onClick | API 호출 | 상태 변화 |
|-------|--------|---------|---------|
| 관심종목 시작하기 → | `href="/watchlist"` (anchor) | 없음 | `/watchlist` 페이지 이동 |

**상태 매트릭스**

| 상태 | 표시 |
|-----|-----|
| 항상 | 단일 상태, 조건부 렌더 없음 |

**관련 트랙 + ADR**
- ADR 0040 §2-4 — Watchlist-first UI, 대시보드는 placeholder
- T-W1 — 관심종목이 UI 기본 진입점

---

### 2-2. SetupWizard (`/setup`)

> 구현 위치: `frontend/src/pages/SetupWizard.tsx`

**진입 경로**
- 신규 사용자 앱 최초 진입 (`hasUserAccount=false`)
- OnboardingGuard 자동 redirect

**표시 영역**

| 영역 | 내용 |
|------|------|
| 헤더 | `magicJar` 브랜드 + "셋업 마법사" 서브타이틀 |
| Step 라벨 | 현재 step 이름 + "N / 5" 카운터 |
| 진행률 바 | `progressPercent = (currentStep - 1) / (5 - 1) * 100` — Tailwind `transition-all duration-300` |
| 메인 카드 | Step 컴포넌트 렌더 영역 |
| 버전 | "v3.0.1 · T-ONBOARD" |

**메뉴 항목 (5-step 시퀀스)**

> 구현 위치: `frontend/src/pages/SetupWizard.tsx:28-34`

| 내부 Step | 라벨 | 컴포넌트 |
|----------|-----|---------|
| 1 | KIS 실전 키 입력 | `KisRealStep` |
| 2 | 검증 중 | `KisValidatingStep` |
| 3 | 모의계좌 (선택) | `KisMockStep` |
| 4 | 시스템 설정 (선택) | `SystemConfigStep` |
| 5 | 완료 | `CompleteStep` |

**상태 매트릭스**

| 상태 | 표시 |
|-----|-----|
| Step 전이 | 진행률 바 애니메이션 + Step 라벨 변경 |
| 검증 실패 | Step 1로 복귀 + `validationError` 메시지 표시 (Zustand store) |

**관련 트랙 + ADR**
- T-ONBOARD — 셋업 마법사 7-step (ADR 0046 §2-5 키 보안)
- spec §5 7-step 계약 LOCK

---

### 2-2-A. KisRealStep (SetupWizard Step 1)

> 구현 위치: `frontend/src/pages/setup/steps/KisRealStep.tsx`

**버튼 인벤토리**

| 버튼명 | type | onClick | API 호출 | 상태 변화 |
|-------|------|--------|---------|---------|
| 초기화 | button | `handleReset()` — 4 input 클리어 | 없음 | 입력값 + 에러 초기화 |
| 검증 시작 → | submit | `handleSubmit()` | 없음 (Step 2 전이 후 API 호출) | 클라이언트 검증 통과 시 `onNext(data)` 호출 → Step 2 |

**입력 필드 인벤토리**

| 필드명 | type | 검증 규칙 | 비고 |
|-------|------|---------|-----|
| 실전 계좌번호 | text | `^\d{8}$` | `autoComplete="off"`, maxLength=8, 숫자 강제 |
| 닉네임 | text | maxLength=100 (선택) | `autoComplete="off"` |
| KIS App Key | password | 비어있으면 오류 | `autoComplete="off"`, unmount 시 메모리 초기화 |
| KIS Secret Key | password | 비어있으면 오류 | 동일, AES-256-GCM 암호화 안내 |

**상태 매트릭스**

| 상태 | 표시 |
|-----|-----|
| `validationError` (Zustand) | 빨간 박스 서버 오류 메시지 (Step 2 실패 후 복귀 시) |
| 클라이언트 검증 실패 | 각 input 하단 빨간 `text-xs text-red-400` 오류 문자열 |
| 보안 안내 | 황색 박스 "AES-256-GCM 암호화 후 저장" |

---

### 2-2-B. KisValidatingStep (SetupWizard Step 2)

> 구현 위치: `frontend/src/pages/setup/steps/KisValidatingStep.tsx`

**버튼 인벤토리**
- 버튼 없음. 취소 불가 (in-flight)

**자동 동작**
- mount 즉시 `useValidateKisReal().mutate(payload)` 호출
- `POST /api/onboarding/kis/validate` → 성공: `setRegistered(userId)` + Step 3 / 실패: Step 1 복귀 + `setValidationError(message)`

**상태 매트릭스**

| 상태 | 표시 |
|-----|-----|
| isPending=true | 스피너 + "KIS 키 검증 중..." + "예상 소요 시간: 1~3초" |
| isPending=false (전이 직전) | "처리 중..." (잠깐 표시) |

---

### 2-2-C. KisMockStep (SetupWizard Step 3)

> 구현 위치: `frontend/src/pages/setup/steps/KisMockStep.tsx`

**버튼 인벤토리**

| 버튼명 | 조건 | onClick | API 호출 | 상태 변화 |
|-------|------|--------|---------|---------|
| 네, 등록할게요 | 항상 | `setWantMock('yes')` | 없음 | 입력 폼 노출 |
| 건너뛰기 (선택) | 항상 | `setWantMock('no')` | 없음 | "다음 단계로" 버튼 노출 |
| 모의계좌 등록 → | wantMock='yes' + 폼 submit | `handleSubmit()` | `POST /api/onboarding/kis/mock` | 성공: `setMockRegistered()` + Step 4 |
| 다음 단계로 → | wantMock='no' | `onNext()` | 없음 | Step 4 전이 |

**상태 매트릭스**

| 상태 | 표시 |
|-----|-----|
| wantMock=null | Yes/No 버튼만 표시 |
| wantMock='yes' | 모의계좌 3-field 폼 노출 |
| isPending=true | "검증 중..." 버튼 비활성 |
| serverError | 빨간 박스 오류 메시지 |

---

### 2-2-D. SystemConfigStep (SetupWizard Step 4)

> 구현 위치: `frontend/src/pages/setup/steps/SystemConfigStep.tsx`

**버튼 인벤토리**

| 버튼명 | onClick | API 호출 | 상태 변화 |
|-------|--------|---------|---------|
| 건너뛰기 | `onSkip()` | 없음 | Step 5 전이 (API 미호출) |
| 저장 후 다음 → | `handleSubmit()` | `POST /api/onboarding/system-config` | 성공: `setSystemConfig()` + Step 5 |

**입력 필드 인벤토리**

| 필드명 | 검증 규칙 | 비고 |
|-------|---------|-----|
| DART API 키 | 없음 (선택) | `autoComplete="off"` |
| Slack Webhook URL | `https://hooks.slack.com/` 시작 | type=url, 등록 시 test message 발송 |

---

### 2-2-E. CompleteStep (SetupWizard Step 5)

> 구현 위치: `frontend/src/pages/setup/steps/CompleteStep.tsx`

**버튼 인벤토리**

| 버튼명 | 조건 | onClick | API 호출 | 상태 변화 |
|-------|-----|--------|---------|---------|
| magicJar 시작하기 → | `registeredUserId` 존재 시 활성 | `handleStart()` | `POST /api/onboarding/complete` | 성공: Zustand `reset()` + `navigate(data.redirectTo)` (`"/"`) |

**표시 데이터**

| 항목 | 출처 |
|-----|-----|
| 실전 계좌 masked | Zustand `registeredUserId` → `"XXXX****"` |
| 모의 계좌 | Zustand `hasMockRegistered` |
| DART API | Zustand `hasSystemConfig` |
| Slack 알림 | Zustand `hasSystemConfig` |

**상태 매트릭스**

| 상태 | 표시 |
|-----|-----|
| isPending | "처리 중..." + 버튼 비활성 |
| serverError | 빨간 박스 오류 메시지 |
| registeredUserId=null | 버튼 disabled |

---

### 2-3. AppHeader (공통 레이아웃)

> 구현 위치: `frontend/src/components/layout/AppHeader.tsx`

**표시 조건**: `/setup` 제외 모든 경로 (App.tsx AppLayout 제어)

**표시 영역**

| 영역 | 내용 |
|-----|-----|
| 로고 | `NavLink to="/"` — `magicJar` 브랜드 텍스트 |
| 네비게이션 | `대시보드 (to="/")` + `관심종목 (to="/watchlist")` |

**메뉴 항목**

| 메뉴명 | NavLink to | 활성 표시 | 키보드 |
|-------|-----------|---------|-------|
| 대시보드 | `/` (end=true) | `bg-gray-800 text-white` | focus-ring: `focus:ring-2 focus:ring-blue-400` |
| 관심종목 | `/watchlist` | 동일 | 동일 |

**스타일**: `sticky top-0 z-50 border-b border-gray-800 bg-gray-950/95 backdrop-blur`

---

### 2-4. WatchlistPage (`/watchlist`)

> 구현 위치: `frontend/src/pages/Watchlist/WatchlistPage.tsx`

**진입 경로**
- AppHeader "관심종목" NavLink
- DashboardPlaceholder "관심종목 시작하기 →" 버튼
- WatchlistRow 클릭 후 SymbolDetailPage "관심종목으로" 버튼

**표시 영역**

| 영역 | 내용 | 출처 |
|-----|------|-----|
| 페이지 헤더 | h1 "관심종목" + "총 N종목" | `useWatchlist()` entries.length |
| 등록 폼 | `WatchlistAddForm` (종목 검색 + 등록) | — |
| 목록 영역 | `WatchlistRow` 반복 렌더 | `GET /api/watchlists` |
| 빈 상태 | `WatchlistEmpty` | entries.length === 0 |
| 에러 상태 | 빨간 박스 오류 텍스트 | REST 실패 시 |
| 로딩 상태 | `WatchlistSkeleton` (3개 pulse 박스) | isLoading |

**상태 매트릭스**

| 상태 | 조건 | 표시 |
|-----|-----|-----|
| loading | `isLoading=true` | WatchlistSkeleton (3 pulse rows) |
| error | `isError=true` | 빨간 `role="alert"` 오류 박스 (페이지 전체 중단 없음) |
| empty | entries.length === 0 | WatchlistEmpty + CTA 버튼 |
| normal | entries.length > 0 | WatchlistRow 목록 |

**관련 트랙 + ADR**
- T-W1 — Watchlist CRUD UI
- ADR 0040 §2-4 — Watchlist-first UI, 진입 default

---

### 2-5. WatchlistAddForm (WatchlistPage 하위)

> 구현 위치: `frontend/src/pages/Watchlist/WatchlistAddForm.tsx`

**버튼 인벤토리**

| 버튼명 | 조건 | onClick | API 호출 | 상태 변화 |
|-------|-----|--------|---------|---------|
| 등록 | `selectedCode` 존재 + `!isPending` | `handleSubmit()` | `POST /api/watchlists { code }` | 성공: 입력 초기화 + 성공 메시지 1초 + `useWatchlist` invalidate |

**입력 필드**

| 필드명 | 컴포넌트 | 검증 규칙 |
|-------|---------|---------|
| 종목 검색 | `InstrumentSearchInput` (자동완성) | 자동완성 목록에서 선택 필수 (`^\d{6}$`) |

**상태 매트릭스**

| 상태 | 표시 |
|-----|-----|
| isPending | "등록 중..." 버튼 텍스트 + disabled |
| selectedCode 없음 | 등록 버튼 disabled |
| selectedCode 존재 | "선택: 종목명 코드" 파란 텍스트 |
| 400 응답 | "유효하지 않은 종목 코드입니다" |
| 409 응답 | "이미 등록된 종목입니다" |
| 성공 | "종목명 등록되었습니다" 초록 텍스트 (1초 후 자동 제거) |

---

### 2-6. WatchlistRow (WatchlistPage 하위)

> 구현 위치: `frontend/src/pages/Watchlist/WatchlistRow.tsx`

**진입 경로**: WatchlistPage 목록 렌더 (`entries.map`)

**표시 데이터**

| 항목 | 출처 | 형식 |
|-----|-----|-----|
| 종목명 | `entry.name ?? entry.code` | 텍스트 |
| 상장폐지 배지 | `entry.delisted === true` | 빨간 "상장폐지" 배지 + tooltip |
| 거래정지 배지 | `entry.tradingHalt === true` | 주황 "거래정지" 배지 |
| 관리종목 배지 | `entry.managementStock && !tradingHalt` | 노란 "관리종목" 배지 |
| 종목코드 | `entry.code` | 모노 폰트 |
| 시장 배지 | `entry.market` (UNKNOWN 제외) | 회색 소형 배지 |
| 우선순위 | `entry.priority` | `P{N}` 회색 배지 |
| 등록일 | `entry.createdAt` | `date.toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' })` |

**버튼 인벤토리**

| 버튼명 | onClick | API 호출 | 상태 변화 |
|-------|--------|---------|---------|
| 휴지통 (삭제) | `handleRemoveClick(e)` — `e.stopPropagation()` + `window.confirm()` | `DELETE /api/watchlists/{code}` | 성공: `opacity-0` fade (200ms) + `useWatchlist` invalidate; 실패: `isRemoving=false` 복구 |
| Row 자체 (클릭) | `handleRowClick()` | 없음 | `navigate('/symbol/${entry.code}')` |

**키보드 접근성**
- `role="button" tabIndex={0}` — Enter/Space 키로 row 클릭 동작
- 삭제 버튼: `focus:ring-2 focus:ring-red-400`

**상태 매트릭스**

| 상태 | 표시 |
|-----|-----|
| isRemoving=true | `opacity-0` (200ms transition) |
| isPending (삭제 중) | 삭제 버튼 disabled + opacity-40 |
| entry.delisted=true | 빨간 "상장폐지" 배지 |
| entry.tradingHalt=true | 주황 "거래정지" 배지 |
| entry.managementStock=true | 노란 "관리종목" 배지 |

**관련 ADR**
- ADR 0017 §2-2 — 상장폐지 종목 instrument 보존 (사용자 직접 정리)

---

### 2-7. WatchlistEmpty (WatchlistPage 하위)

> 구현 위치: `frontend/src/pages/Watchlist/WatchlistEmpty.tsx`

**버튼 인벤토리**

| 버튼명 | onClick | 효과 |
|-------|--------|-----|
| 관심종목 등록하기 | `onRegisterClick()` | WatchlistPage의 `inputRef.current?.focus()` 호출 — 등록 폼 포커스 이동 |

**ADR**: ADR 0040 §2-2 — 빈 상태 CTA "관심종목 등록하기" 의무

---

### 2-8. InstrumentSearchInput (자동완성 검색)

> 구현 위치: `frontend/src/components/InstrumentSearchInput.tsx`

**진입 경로**: WatchlistAddForm 내 검색 필드

**기능 상세**

| 기능 | 구현 |
|-----|-----|
| debounce | 200ms (`useEffect` + `setTimeout`) |
| 한글 IME 처리 | `onCompositionStart` → `isComposing=true` (API 차단) / `onCompositionEnd` → 즉시 검색 |
| 키보드 네비게이션 | ArrowDown/ArrowUp — `highlightedIndex` / Enter — 선택 / Escape — 닫기 |
| 서버 오류 누적 fallback | 5xx 3회 → `rawInputMode=true` (코드 직접 입력 모드 전환) |
| 외부 클릭 닫기 | `document.addEventListener('mousedown', handleClickOutside)` |

**상태 매트릭스**

| 상태 | 조건 | 표시 |
|-----|-----|-----|
| 정상 | 결과 있음 | dropdown listbox (z-50, `role="listbox"`) |
| 로딩 중 | `isLoading` | "검색 중..." 텍스트 (input 우측) |
| 결과 없음 | `results.length === 0 + debouncedQuery >= 1` | "검색 결과가 없습니다" |
| 5xx 오류 (<3회) | `isServerError` | 노란 "검색 일시 불가능" 경고 |
| raw input mode (>=3회) | `rawInputMode=true` | 노란 테두리 input + 직접 입력 + "자동완성으로 전환" 버튼 |

**접근성**
- `role="combobox"`, `aria-expanded`, `aria-autocomplete="list"`, `aria-owns="instrument-search-listbox"`
- dropdown item: `role="option"`, `aria-selected`

---

### 2-9. SymbolDetailPage (`/symbol/:code`)

> 구현 위치: `frontend/src/pages/Symbol/SymbolDetailPage.tsx`

**진입 경로**
- WatchlistRow 클릭 → `navigate('/symbol/${entry.code}')`

**표시 영역 (4-zone 레이아웃)**

| 영역 | 컴포넌트 | 그리드 |
|-----|---------|-------|
| 헤더 (종목명·코드·시장·산업) | `SymbolHeader` | full-width |
| STOMP 연결 상태 배지 | 인라인 (`!stompConnected` 조건) | full-width |
| 현재가 카드 | `SymbolPriceCard` | desktop: left 1/3 |
| 기업 정보 카드 | `SymbolMetaCard` | desktop: left 1/3 |
| LWC 차트 | `SymbolChart` | desktop: right 2/3 |
| 호가창 | `SymbolOrderbook` | full-width |

**데이터 흐름**

| 데이터 | 출처 | 갱신 방식 |
|-------|-----|---------|
| 종목 마스터 (active·delistedAt) | `useInstrument(code)` → `GET /api/instruments/{code}` | staleTime 60s |
| 현재가·메타 (SymbolDto) | `useSymbol(code)` → `GET /api/symbols/{code}` | refetchInterval 5s polling fallback |
| 실시간 tick | STOMP `/topic/market.tick.{code}` | push |
| 실시간 1m 캔들 | STOMP `/topic/market.candle.minute.{code}` | push |
| 호가 | STOMP `/topic/market.orderbook.{code}` | push |
| 호가 slot | `POST /api/symbols/{code}/orderbook/subscribe` (mount) | 단발 |

**라이프사이클 — 호가 slot**

> 구현 위치: `frontend/src/pages/Symbol/SymbolDetailPage.tsx:104-140`

```
mount (200ms delay)
  → POST /api/symbols/{code}/orderbook/subscribe
  → setOrderbookSlot('live' | 'queued')

unmount
  → navigator.sendBeacon(unsubUrl, Blob([{code}], 'application/json'))
  → fallback: POST /api/symbols/{code}/orderbook/unsubscribe
```

**상태 매트릭스**

| 상태 | 조건 | 표시 |
|-----|-----|-----|
| loading | `isLoading=true` | 전체화면 스피너 "종목 정보 로딩 중..." |
| delisted | `instrument.active=false` | `SymbolError variant="delisted"` (가격·차트·호가 전부 차단) |
| HTTP 503/404/400 | `isBEError(httpStatus)` | `SymbolError` 기본 |
| symbol=null | 예상치 못한 경우 | `SymbolError` 기본 |
| STOMP 끊김 | `!stompConnected` | 노란 배지 "실시간 연결 재시도 중... (5초 polling fallback 활성)" |
| 정상 | 4-zone 레이아웃 렌더 |

**STOMP 구독 조건**

> 구현 위치: `frontend/src/pages/Symbol/SymbolDetailPage.tsx:81-99`

```
stompEnabled = Boolean(code) && !isDelisted
```
- 상장폐지 종목: tick·candle·orderbook 3개 구독 모두 차단

**관련 트랙 + ADR**
- T-S1 — Symbol Detail (ADR 0040 §2-2)
- ADR 0014 — 현재가 Fallback 3-tier (stale 배지)
- ADR 0019 — 캔들 인터벌 5종
- ADR 0029 — STOMP candle.minute 실시간
- ADR 0048 — 호가 LRU 10 slot

---

### 2-10. SymbolError

> 구현 위치: `frontend/src/pages/Symbol/SymbolError.tsx`

**진입 경로**: SymbolDetailPage 에러 분기

**variant 매트릭스**

| variant | 아이콘 | 제목 | 추가 표시 |
|--------|-------|-----|---------|
| `not-found` (default) | 회색 경고 아이콘 | "종목 정보를 찾을 수 없습니다" | `message` prop (선택) |
| `delisted` | 빨간 금지 아이콘 | "상장폐지된 종목입니다" | `delistedAt` 표시 + "실시간 가격·차트·호가는 제공되지 않습니다" 안내 |

**버튼 인벤토리**

| 버튼명 | onClick | 효과 |
|-------|--------|-----|
| 관심종목으로 | `navigate('/watchlist')` | WatchlistPage 이동 |

---

### 2-11. SymbolHeader (SymbolDetailPage 하위)

> 구현 위치: `frontend/src/pages/Symbol/SymbolHeader.tsx`

**표시 데이터**

| 항목 | 조건 |
|-----|-----|
| 종목명 | `symbol.name ?? symbol.code` (graceful — instrument 미연동 시 code 표시) |
| 종목코드 | `symbol.name` 있을 때만 `(code)` 표시 |
| 시장 배지 | `symbol.market` 있을 때 파란 배지 |
| 산업 배지 | `symbol.industry` 있을 때 회색 배지 |

**버튼 인벤토리**

| 버튼명 | onClick | 효과 |
|-------|--------|-----|
| 관심종목으로 | `navigate('/watchlist')` | WatchlistPage 이동 |

---

### 2-12. SymbolPriceCard (SymbolDetailPage 하위)

> 구현 위치: `frontend/src/pages/Symbol/SymbolPriceCard.tsx`

**표시 데이터**

| 항목 | 출처 | 포맷 |
|-----|-----|-----|
| 현재가 | `realtimeTick?.price ?? symbol.currentPrice` | `toLocaleString('ko-KR')` + "원" |
| 등락 | `symbol.change` | `+N,NNN` or `-N,NNN` |
| 등락률 | `symbol.changeRate` | `±NN.NN%` |
| source 배지 | `realtimeTick?.source ?? symbol.source` | live: 초록 pulse / closed: 노란 "장 마감" / stale: 노란 "지연 데이터" |
| 고가 | `symbol.high` | 빨강 (`text-red-300`) |
| 저가 | `symbol.low` | 파랑 (`text-blue-300`) |
| 시가 | `symbol.open` | 회색 |
| 전일종가 | `symbol.previousClose` | 회색 |
| 거래량 | `symbol.volume` | 1M/천 단위 약어 |

**색상 컨벤션** (한국 증권 표준)
- 상승 (change > 0): `text-red-400`
- 하락 (change < 0): `text-blue-400`
- 보합 (change = 0): `text-gray-300`

---

### 2-13. SymbolMetaCard (SymbolDetailPage 하위)

> 구현 위치: `frontend/src/pages/Symbol/SymbolMetaCard.tsx`

**표시 데이터**

| 항목 | 포맷 | null 처리 |
|-----|-----|---------|
| 시가총액 | N조 N,NNN억원 (1조 이상 조/억 분리) | "-" |
| PER | N.NN 배 | "-" |
| PBR | N.NN 배 | "-" |
| EPS | N,NNN 원 | "-" |
| BPS | N,NNN 원 | "-" |

---

### 2-14. SymbolChart (SymbolDetailPage 하위)

> 구현 위치: `frontend/src/pages/Symbol/SymbolChart.tsx`

**버튼 인벤토리**

| 버튼명 | 조건 | onClick | 효과 |
|-------|-----|--------|-----|
| 1분 / 5분 / 30분 / 1시간 / 일봉 | `IntervalToggle` 5버튼 | `handleIntervalChange(newInterval)` | `useSymbolCandles` queryKey 변경 → re-fetch |
| 더 보기 | `hasNextPage=true` | `fetchNextPage()` | before cursor 페이지네이션 추가 로드 |

**상태 매트릭스**

| 상태 | 조건 | 표시 |
|-----|-----|-----|
| loading | `isLoading=true` | 스피너 "차트 로딩 중..." (h-96) |
| error | `isError=true` | "차트 데이터를 불러올 수 없습니다." (h-96) |
| cold-start | `allCandles.length === 0` | 노란 pulse + "캔들 데이터 수집 중" + "잠시 후 새로고침" 안내 (ADR 0019 §2-3) |
| 정상 | candles > 0 | `LightweightChart` 렌더 |
| 더 불러오기 중 | `isFetchingNextPage=true` | "이전 데이터 불러오는 중..." 텍스트 |

---

### 2-15. IntervalToggle (SymbolChart 하위)

> 구현 위치: `frontend/src/pages/Symbol/IntervalToggle.tsx`

**버튼 인벤토리**

| 버튼명 | value | aria-pressed | 활성 스타일 |
|-------|-------|-------------|---------|
| 1분 | `1m` | true/false | `bg-blue-600 text-white` |
| 5분 | `5m` | | |
| 30분 | `30m` | | |
| 1시간 | `1h` | | |
| 일봉 | `1d` | | (기본 선택) |

**ADR**: ADR 0019 §2-2 — 1차 MVP 5종 인터벌 LOCK

---

### 2-16. LightweightChart (SymbolChart 하위)

> 구현 위치: `frontend/src/pages/Symbol/LightweightChart.tsx`

**LWC v4 API 사용**

| API | 용도 |
|-----|-----|
| `createChart(container, options)` | 차트 인스턴스 생성 (마운트 1회) |
| `chart.addCandlestickSeries({...})` | 캔들스틱 시리즈 추가 |
| `chart.addHistogramSeries({ priceScaleId: '' })` | 거래량 히스토그램 (독립 스케일) |
| `candleSeries.setData(candles)` | 인터벌 변경/초기 로드 시 전체 교체 |
| `candleSeries.update(candle)` | STOMP 실시간 1건 갱신 |
| `chart.timeScale().fitContent()` | 데이터 로드 후 전체 보기 |
| `chart.timeScale().subscribeVisibleLogicalRangeChange()` | 좌측 스크롤 끝 감지 → `onScrollLeft()` |

**차트 테마 설정**

```javascript
// 구현 위치: frontend/src/pages/Symbol/LightweightChart.tsx:73-95
layout: { background: { color: '#0d1117' }, textColor: '#9ca3af' }
grid: { vertLines: { color: '#1f2937' }, horzLines: { color: '#1f2937' } }
crosshair: { mode: 1 }  // CrosshairMode.Magnet
```

**ADR**: ADR 0025 §2-1 — LWC v4 공식 API 한정, TradingView Advanced 0

---

### 2-17. SymbolOrderbook (SymbolDetailPage 하위)

> 구현 위치: `frontend/src/pages/Symbol/SymbolOrderbook.tsx`

**표시 데이터**

| 항목 | 출처 | 표시 |
|-----|-----|-----|
| 매도호가 10단계 (asks) | STOMP `OrderbookPayload.asks` (역순 정렬 — 낮은 가격 하단) | 파랑 (`text-blue-300`) |
| 매수호가 10단계 (bids) | STOMP `OrderbookPayload.bids` | 빨강 (`text-red-300`) |
| 수량 배경 바 | 수량/maxQty 비례 width | 불투명 20% 파랑/빨강 |
| slot 배지 | `orderbookSlot` | `live`: 초록 pulse "실시간" / `queued`: 주황 "polling fallback 중" |

**상태 매트릭스**

| 상태 | 조건 | 표시 |
|-----|-----|-----|
| orderbook=null + STOMP 연결 중 | `!orderbook && isStompConnected` | "호가 데이터 대기 중..." |
| orderbook=null + STOMP 끊김 | `!orderbook && !isStompConnected` | "실시간 연결 재시도 중... (STOMP 끊김)" |
| orderbookSlot='queued' | | 주황 "polling fallback 중" 배지 |
| orderbookSlot='live' + 연결됨 | | 초록 pulse "실시간" 배지 |

**ADR**: ADR 0048 — 호가 LRU 10 slot (H0STASP0)

---

## §3 컴포넌트 라이브러리

### 3-1. 의존성 인벤토리

> 구현 위치: `frontend/package.json`

| 패키지 | 버전 | 용도 |
|-------|-----|-----|
| `react` | ^19.2.6 | UI 프레임워크 |
| `react-dom` | ^19.2.6 | DOM 렌더링 |
| `react-router-dom` | ^7.1.0 | SPA 라우팅 |
| `@tanstack/react-query` | ^5.100.9 | 서버 상태 관리 (TanStack Query v5) |
| `axios` | ^1.7.9 | REST HTTP 클라이언트 |
| `lightweight-charts` | ^4.2.0 | TradingView LWC v4 차트 |
| `@stomp/stompjs` | ^7.0.0 | STOMP WebSocket 클라이언트 |
| `zustand` | ^5.0.13 | 클라이언트 상태 관리 (Wizard step) |
| `tailwindcss` | ^3.4.17 | 유틸리티 CSS |

### 3-2. 디자인 토큰

> 구현 위치: `frontend/tailwind.config.js`

**컬러 토큰**

| 토큰 | 값 | 용도 |
|-----|---|-----|
| `brand.50` | `#eff6ff` | — |
| `brand.500` | `#3b82f6` | 주요 액션 버튼 기본 |
| `brand.600` | `#2563eb` | 주요 액션 버튼 hover |
| `up` | `#ef4444` | 상승 (빨강 — 한국 증권 표준) |
| `down` | `#3b82f6` | 하락 (파랑 — 한국 증권 표준) |
| `gray-950` | Tailwind 기본 | 전체 배경 |
| `gray-900` | Tailwind 기본 | 카드 배경 |
| `gray-800` | Tailwind 기본 | 입력 배경, 버튼 비활성 |

**폰트 토큰**

| 토큰 | 값 | 용도 |
|-----|---|-----|
| `font-sans` | Pretendard → system-ui fallback | 한국어 UI 본문 |
| `font-mono` | JetBrains Mono → Menlo → Courier New | 종목코드·가격 숫자 |

**LWC 차트 색상** (하드코딩 — tailwind.config 미포함)

| 항목 | 값 |
|-----|---|
| 캔들 상승 | `#26a69a` |
| 캔들 하락 | `#ef5350` |
| 거래량 상승 | `#26a69a44` |
| 거래량 하락 | `#ef535044` |

### 3-3. Vite 청크 분리

> 구현 위치: `frontend/vite.config.ts:36-43`

| 청크명 | 패키지 |
|-------|-------|
| `vendor-react` | react, react-dom |
| `vendor-router` | react-router-dom |
| `vendor-query` | @tanstack/react-query |
| `vendor-chart` | lightweight-charts |

### 3-4. Vite 프록시 설정

> 구현 위치: `frontend/vite.config.ts:15-26`

| 경로 | 대상 | 용도 |
|-----|-----|-----|
| `/api` | `http://localhost:8080` | REST API 프록시 |
| `/ws` | `ws://localhost:8080` (ws:true) | STOMP WebSocket 프록시 |

---

## §4 데이터 fetch 패턴

### 4-1. TanStack Query hook 인벤토리

| hook | 파일 | queryKey | staleTime | refetchInterval | enabled 조건 |
|-----|-----|---------|---------|----------------|------------|
| `useOnboardingStatus` | `frontend/src/api/onboarding.ts:107` | `['onboarding', 'status']` | 0 | 없음 | 항상 |
| `useWatchlist` | `frontend/src/api/hooks/useWatchlist.ts:18` | `['watchlist']` | 30s | 없음 | 항상 |
| `useSymbol` | `frontend/src/api/hooks/useSymbol.ts:14` | `['symbol', code]` | 1s | 5s (STOMP fallback) | `Boolean(code)` |
| `useInstrument` | `frontend/src/api/hooks/useInstrument.ts:14` | `['instrument', code]` | 60s | 없음 | `Boolean(code)` |
| `useSymbolCandles` | `frontend/src/api/hooks/useSymbolCandles.ts:15` | `['symbolCandles', code, interval]` | 30s | 없음 | `Boolean(code)` |
| `useInstrumentSearch` | `frontend/src/api/hooks/useInstrumentSearch.ts:30` | `['instrument-search', query, limit, market]` | 60s | 없음 | `query.length >= 1 && !isComposing` |

**Mutation 인벤토리**

| hook | 파일 | mutationFn | onSuccess |
|-----|-----|----------|---------|
| `useAddWatchlist` | `frontend/src/api/hooks/useWatchlist.ts:30` | `POST /api/watchlists` | `invalidateQueries(['watchlist'])` |
| `useRemoveWatchlist` | `frontend/src/api/hooks/useWatchlist.ts:43` | `DELETE /api/watchlists/{code}` | `invalidateQueries(['watchlist'])` |
| `useValidateKisReal` | `frontend/src/api/onboarding.ts:122` | `POST /api/onboarding/kis/validate` | `invalidateQueries(['onboarding', 'status'])` |
| `useAddMockAccount` | `frontend/src/api/onboarding.ts:139` | `POST /api/onboarding/kis/mock` | 없음 |
| `useUpdateSystemConfig` | `frontend/src/api/onboarding.ts:151` | `POST /api/onboarding/system-config` | 없음 |
| `useCompleteOnboarding` | `frontend/src/api/onboarding.ts:163` | `POST /api/onboarding/complete` | `invalidateQueries(['onboarding', 'status'])` |

### 4-2. STOMP 구독 패턴

> 구현 위치: `frontend/src/lib/stomp.ts`

**싱글톤 클라이언트**

```javascript
// brokerURL: ws://{host}/ws (Vite proxy → ws://localhost:8080/ws)
// reconnectDelay: 5000ms
```

**useStompTopic hook 시그니처**

> 구현 위치: `frontend/src/lib/stomp.ts:98-137`

```typescript
useStompTopic<T>(
  topic: string,       // 구독 topic (예: /topic/market.tick.005930)
  onMessage: (payload: T) => void,
  enabled = true       // false이면 구독 0 (상장폐지 종목 차단 등)
): void
```

**STOMP topic 매트릭스 (SymbolDetailPage)**

| topic | payload 타입 | 수신 시 상태 변화 | enabled 조건 |
|-------|------------|----------------|------------|
| `/topic/market.tick.{code}` | `TickPayload` | `setRealtimeTick(payload)` → SymbolPriceCard 실시간 갱신 | `!isDelisted` |
| `/topic/market.candle.minute.{code}` | `CandleMinutePayload` | `setRealtimeCandle(payload)` → LWC `candleSeries.update()` | `!isDelisted` |
| `/topic/market.orderbook.{code}` | `OrderbookPayload` | `setOrderbook(payload)` → SymbolOrderbook 갱신 | `!isDelisted` |

**STOMP 끊김 fallback**

> 구현 위치: `frontend/src/pages/Symbol/SymbolDetailPage.tsx:71-77`

```javascript
// 5초 주기로 isStompConnected() 폴링
// STOMP 끊김 → !stompConnected → 노란 배지 표시
// useSymbol refetchInterval=5s → REST polling 자동 활성
```

### 4-3. localStorage 사용처

현재 구현에서 localStorage 직접 사용 없음. 클라이언트 상태는 Zustand (wizard step) + TanStack Query 캐시로 관리.

---

## §5 상태 관리 + 에러 처리

### 5-1. Auth 가드 (OnboardingGuard)

> 구현 위치: `frontend/src/App.tsx:31-65`

```
GET /api/onboarding/status → hasUserAccount
  false + 비/setup 경로 → Navigate to="/setup"
  true  + /setup 경로   → Navigate to="/"
```

**axios interceptor 보조 가드**

> 구현 위치: `frontend/src/lib/axios.ts:21-35`

```javascript
// 401 + code='ONBOARDING_REQUIRED' → window.location.href = '/setup'
// (backend OnboardingGuardFilter 연동)
```

### 5-2. Network Error Fallback

| 레이어 | 정책 |
|-------|-----|
| WatchlistPage REST 실패 | 목록 영역만 빨간 박스 오류 표시. 페이지 전체 중단 없음 |
| SymbolDetailPage REST 실패 | HTTP 코드 분류 후 SymbolError 렌더 |
| STOMP 끊김 | 노란 배지 "실시간 연결 재시도 중..." + useSymbol 5s polling 자동 활성화 |
| InstrumentSearchInput 5xx | 첫 3회: 노란 경고. 3회 이후: rawInputMode 전환 |
| axios 401 ONBOARDING_REQUIRED | `/setup` 자동 redirect |

### 5-3. Empty State 정책

| 화면 | 데이터 없음 표시 |
|-----|--------------|
| WatchlistPage | `WatchlistEmpty` — "관심종목이 없습니다" + CTA 버튼 |
| SymbolChart cold-start | "캔들 데이터 수집 중" + "잠시 후 새로고침" (mock 합성 0건) |
| SymbolOrderbook 데이터 없음 | "호가 데이터 대기 중..." or "실시간 연결 재시도 중..." |
| InstrumentSearchInput 검색 결과 없음 | "검색 결과가 없습니다" |

**mock 합성 정책**: 사용자 피드백 `feedback_no_mock_data.md` 준수 — 0건 또는 에러 시 명시적 표시, 합성 데이터 0건.

### 5-4. Polling/STOMP demotion 정책

| 조건 | 동작 |
|-----|-----|
| `instrument.active=false` (상장폐지) | useSymbol enabled=false (polling 차단) + STOMP 3종 구독 차단 + 호가 subscribe 차단 |
| STOMP 연결 끊김 | useSymbol refetchInterval=5s REST polling 자동 활성 (STOMP 재연결 시 자동 우선) |
| InstrumentSearchInput 5xx 3회 | rawInputMode 전환 (API 호출 중단) |

---

## §6 테스트 인벤토리

| 파일 | describe | 검증 시나리오 |
|-----|---------|------------|
| `frontend/src/__tests__/onboarding-routing.test.tsx` | 온보딩 라우팅 가드 | (1) `hasUserAccount=false` → `/setup` redirect 검증 |
| `frontend/src/__tests__/watchlist-flow.test.tsx` | 관심종목 등록 flow | (1) 빈 상태 "관심종목이 없습니다" 표시; (2) InstrumentSearchInput 자동완성 선택 + "등록" 버튼 클릭 → `POST /api/watchlists` → 목록 1건 표시 |
| `frontend/src/components/__tests__/InstrumentSearchInput.test.tsx` | InstrumentSearchInput | (1) 검색어 입력 → dropdown 2건 표시; (2) 삼성전자 클릭 → `onSelect({code:'005930'})` callback 호출 |
| `frontend/src/pages/Symbol/__tests__/SymbolDetailPage.test.tsx` | SymbolDetailPage smoke | (1) 정상 종목(005930) 로드 → 종목명·현재가·PER 카드 렌더; (2) 잘못된 코드(999999) 503 → SymbolError "종목 정보를 찾을 수 없습니다"; (3) 상장폐지 종목(004530) → SymbolError variant=delisted + delistedAt 표시 |

**테스트 인프라**

| 도구 | 버전 | 용도 |
|-----|-----|-----|
| vitest | ^3.2.0 | 테스트 러너 |
| @testing-library/react | ^16.3.0 | React 컴포넌트 렌더 |
| @testing-library/user-event | ^14.5.2 | 사용자 이벤트 시뮬레이션 |
| msw | ^2.10.0 | REST API mock 서버 |
| jsdom | ^26.1.0 | 브라우저 환경 시뮬레이션 |

**LWC v4 mock**

> 구현 위치: `frontend/src/pages/Symbol/__tests__/SymbolDetailPage.test.tsx:24-26`

```javascript
// jsdom 환경에서 canvas 미지원 → LightweightChart 전체 mock
vi.mock('../LightweightChart', () => ({
  LightweightChart: () => <div data-testid="mock-lightweight-chart" />
}))
```

---

## §7 Cross-reference

| 문서 | 연결 지점 |
|-----|---------|
| `_workspace/baseline/v3.1.0/01-architecture.md` | frontend 단일 SPA 컨테이너 구조 (ADR 0009) + React 19 + Vite 스택 선택 근거 |
| `_workspace/baseline/v3.1.0/02-data-flow.md` | REST endpoint (GET /api/watchlists, GET /api/symbols/{code}, GET /api/instruments/{code}) + STOMP topic (/topic/market.tick.{code}, /topic/market.candle.minute.{code}, /topic/market.orderbook.{code}) 백엔드 구현 상세 |
| `_workspace/baseline/v3.1.0/03-use-sequences.md` | 사용자 시나리오: Onboarding 완료 → Watchlist 등록 → SymbolDetail 진입 시퀀스 (KIS-first raw 출처 포함) |
| `_workspace/baseline/v3.1.0/04-feature-spec.md` | REST endpoint 전체 스펙 (DTO 스키마 + HTTP 상태코드 + 에러 포맷) — 본 문서 §4 fetch 패턴의 백엔드 계약 |

---

*본 문서는 `frontend/src/` 코드 직접 inspect 결과만 포함합니다. 추측 내용 0건.*
