# magicJar Frontend

React + TypeScript + Vite SPA — magicJar 자동매매 시스템 프론트엔드.

## 빠른 시작

```bash
# 의존성 설치
pnpm install

# 개발 서버 (localhost:3000, /api → localhost:8080 프록시)
pnpm dev

# 단위 테스트 (Vitest)
pnpm test

# 프로덕션 빌드
pnpm build

# 빌드 미리보기
pnpm preview
```

## Docker

```bash
# 이미지 빌드
docker build -t magicjar/frontend:p3 .

# 컨테이너 실행 (포트 3000)
docker run -p 3000:80 magicjar/frontend:p3
```

## 기술 스택

| 항목 | 선택 | 이유 |
|------|------|------|
| 프레임워크 | React 19 + TypeScript | 타입 안전, 생태계 |
| 번들러 | Vite | 빠른 HMR |
| 차트 | TradingView Lightweight Charts v4 (Apache 2.0) | 금융 차트 특화, OSS |
| 상태 | Zustand (전역) + TanStack Query (서버) | 단순, 가벼움 |
| UI | shadcn/ui + Tailwind CSS | 접근성, 커스터마이징 |
| WebSocket | @stomp/stompjs | STOMP over WS |
| 테스트 | Vitest + Testing Library | Vite 통합 |

## 페이지

| URL | 페이지 |
|-----|--------|
| `/` | 대시보드 — 실시간 틱 차트 + 포지션 요약 |
| `/backtest` | 백테스트 실행·결과 |
| `/journal` | 매매일지 + AI 복기 |
| `/strategy` | 전략 DSL 편집 |
| `/harness` | 하네스 운영 현황 (읽기 전용) |

## 차트 원칙 (ADR 0022, spec §11-5)

**LWC 공식 API만 사용.** 외부 캡처 라이브러리(`html-to-image`, `html2canvas` 등) 도입 금지.
차트 캡처는 `chart.takeScreenshot()` (LWC 공식) → P4에서 pyworker 서버사이드 fallback 연결.

## 디렉토리 구조

```
src/
├── api/          REST 클라이언트 (P4에서 orval 자동 생성)
├── ws/           STOMP 래퍼
├── stores/       Zustand (auth, marketTick, position)
├── pages/        5 페이지
├── components/
│   ├── chart/    LWC 래퍼 (Chart, TickChart, CandleChart, chartCapture)
│   └── ui/       shadcn/ui 컴포넌트
└── lib/          queryClient, cn
```
