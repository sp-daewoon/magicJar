# ADR 0022: 프론트엔드 SPA 전환 — React + TS + Vite + TradingView Lightweight Charts

- 상태: Accepted
- 작성일: 2026-04-27
- 결정자: architect (협의: frontend, 사용자 sp-daewoon 명시 제약 반영)
- 관계: ADR 0019(MSA 재구성)에 따른 `web/` 모듈 폐기, ADR 0008(하네스 운영 페이지) 재구현

## 맥락

Phase B의 `web/` 모듈은 Spring Boot + Thymeleaf + HTMX 조합이었다. 한계:

- 차트(틱·캔들·보조지표)·실시간 STOMP 구독·복잡한 사용자 상태(재생/replay·종목 비교 등)에 SSR + HTMX 조합이 명백히 부족.
- API 변경 시 서버 렌더링 코드가 같이 흔들려 백엔드·프런트 동시 작업 충돌 빈번.
- 사용자가 매매일지·전략 편집 같은 풍부한 UX를 요구.

차트 라이브러리 선정에서 사용자가 명시한 제약: **"라이브러리 안에서 가능한 것만 쓴다. 커스텀 코드로 라이브러리를 우회해 흉내내지 않는다."**

## 검토한 대안

### 프레임워크

| 대안 | 장점 | 단점 |
|------|------|------|
| Thymeleaf 유지 | 변경량 0 | 차트·실시간·SPA UX 한계 명확 |
| Next.js (React + SSR) | SEO·SSR | 자동매매 사용자 화면은 인증 뒤라 SSR 가치 낮음, 운영 복잡 |
| Vue 3 / Svelte | 가볍다 | 한국 생태계·구인 풀 작음, TradingView LWC 사례 React 우세 |
| **React + TS + Vite + shadcn/ui** (본 결정) | 빠른 dev server, TS 안전, shadcn 컴포넌트 풍부, LWC 사례 풍부 | 클라이언트 라우팅·상태관리 학습 필요 |

### 차트 라이브러리

| 대안 | 라이선스 | 채택 |
|------|---------|------|
| **TradingView Lightweight Charts v4** | Apache 2.0 (진짜 OSS) | ✅ MVP 채택 |
| TradingView Advanced Charts | Closed (무료 발급) | ❌ 그리기 도구·커스텀 study 사업적 필수해질 시 ADR 0027(미래) |
| Highcharts Stock | 상용 | ❌ 라이선스 부담 |
| ECharts / Chart.js | OSS | ❌ 금융 차트 특화 부족 (보조 차트로는 가능) |

## 결정

**React + TypeScript + Vite + shadcn/ui + TanStack Query + Zustand 스택으로 SPA를 신설하고, 가격 차트는 TradingView Lightweight Charts(LWC)만 사용한다.**

```
frontend/
├─ Dockerfile               # multi-stage (node:20-alpine → nginx:alpine)
├─ vite.config.ts
├─ package.json             # pnpm
└─ src/
   ├─ api/                  # api-app REST 클라이언트 (TanStack Query)
   ├─ ws/                   # STOMP 구독 (api-app /ws)
   ├─ stores/               # Zustand
   ├─ pages/                # Dashboard, Backtest, Journal, Strategy, HarnessOps
   ├─ components/
   │  ├─ chart/             # LWC 얇은 래퍼 — Chart.tsx
   │  └─ ui/                # shadcn/ui
   └─ lib/
```

### 핵심 결정 — **커스텀 지양 원칙** (사용자 명시 제약, MVP PR 리뷰 게이트)

| 항목 | 정책 |
|------|------|
| LWC 공식 API 호출 (시리즈 추가, setData/update, 스케일·옵션, 공식 이벤트 구독) | ✅ 허용 |
| props로 데이터 받아 LWC에 전달하는 React 래퍼 | ✅ 허용 (얇게) |
| LWC 내부 prototype·private 필드 조작 | ❌ 금지 |
| 차트 위에 자체 `<canvas>` 오버레이 | ❌ 금지 |
| LWC가 제공하지 않는 기능을 외부 라이브러리로 흉내 (drawing, indicator 위젯, html-to-image 등) | ❌ 금지 |
| 사업적으로 필수가 된 기능 | **ADR 0027 발동 (Advanced Charts 전환), 커스텀 코드로 메우지 않음** |

→ **MVP 동안 차트 컴포넌트 코드는 "LWC 공식 가이드 예시 ± 데이터 어댑팅" 수준을 넘지 않는다.** PR 리뷰 시 이 원칙을 명시 게이트로 둔다 (frontend 에이전트·QA 책임).

### LWC 한계 → 우리 시스템이 메우는 방법 (LWC 공식 API 한정)

| LWC 한계 | 대응 |
|---------|------|
| 보조지표 계산 미지원 | `domain.strategy.indicator/`(Kotlin)에서 계산 → API 응답에 시리즈 동봉 → LWC 공식 `LineSeries`/`HistogramSeries`로 plot |
| 그리기 도구 없음 | **MVP 미지원**. 사업적 필수해지면 ADR 0027 (Advanced Charts) |
| 시간축 동기화 | LWC 공식 `subscribeVisibleLogicalRangeChange` + `setVisibleLogicalRange`만 사용 |
| 심볼 검색 | 차트 외부 — api-app `/api/symbols/search` + shadcn Combobox |
| Replay 모드 | LWC 공식 `setData` 슬라이싱 + 외부 재생 버튼 (LWC 내부 조작 없음) |
| 사용자 정의 study 작성기 | 미지원 — 필요 시 Advanced Charts (ADR 0027) |
| 틱 차트 | `LineSeries.update({time, value})` (LWC 공식, 비균등 시간 자연 처리) |

### 차트 캡처 — LWC 공식 + 서버 사이드 2단

1. **1차**: `chart.takeScreenshot()` (LWC 공식)
2. **2차**: pyworker `/screenshot` (Playwright, ADR 0023)

`html-to-image` 등 외부 캡처 라이브러리 도입 금지.

`ChartSnapshot.source` 필드(`CLIENT_LWC` / `SERVER_BROWSER`)로 경로 추적 (ADR 0011 개정과 연동).

### 실시간

- api-app `spring-boot-starter-websocket` + STOMP broker
- 프런트는 `@stomp/stompjs` 클라이언트로 구독 (`/topic/market.tick.{code}`, `/topic/journal.entry` 등)

### 페이지 구성

| 페이지 | 핵심 |
|--------|------|
| Dashboard | TickChart + 포지션 요약 + AI 인사이트 |
| Backtest | 전략 선택·실행·Replay 차트 |
| Journal | 매매일지 + 차트 스크린샷 첨부 + 코멘트 |
| Strategy | DSL 편집·미리보기 |
| HarnessOps | 에이전트·Phase·QA 카탈로그·CLAUDE.md 변경 이력 (ADR 0008 재구현) |

## 결과

- 긍정: 차트·실시간·복합 UX 자유도 확보. 백·프런트 작업 동시성 향상. 사용자 명시 "커스텀 지양" 원칙으로 차트 코드 표면 좁게 유지.
- 부정: SPA 빌드·배포 별도, nginx 정적 서빙 컨테이너 추가. CSR이라 첫 로드 느릴 수 있음(인증 후 화면이라 허용).
- 후속:
  - `frontend/` 신규 디렉토리 부트스트랩 (frontend 에이전트)
  - shadcn/ui 초기 컴포넌트 picking
  - LWC 래퍼 1차 구현 + PR 시 본 ADR §"커스텀 지양 원칙" 체크리스트 강제
  - **ADR 0027 후보 트리거**: 그리기 도구·custom study·심볼 비교·고급 replay가 사업적으로 분명히 필요해진 시점

## Links

- spec: `docs/superpowers/specs/2026-04-27-msa-restructure-design.md` §11 (특히 §11-5)
- 관련 ADR: 0019, 0023, 0011(개정)
