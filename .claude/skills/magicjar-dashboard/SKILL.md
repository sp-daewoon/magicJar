---
name: magicjar-dashboard
description: magicJar의 웹 대시보드(Thymeleaf + HTMX + Chart.js)를 새로 만들거나 페이지·컴포넌트·실시간 연동·AI 인사이트 패널을 추가·수정·보완한다. 화면 추가, 차트 타입 변경, WebSocket 구독 경로 수정, 디자인·접근성 개선 작업에 반드시 사용. "새 화면 추가", "차트 바꿔줘", "실시간 업데이트 붙여줘", "UI 개선"도 포함.
---

# magicjar-dashboard — 대시보드·UI

## 기술 스택 (MVP)

- **Thymeleaf** (서버 사이드 렌더링, Spring Boot와 동거)
- **HTMX** — AJAX/부분 갱신, SPA 없이 반응형 UX
- **Alpine.js** (선택) — 가벼운 상태·토글
- **Chart.js** 또는 **Apache ECharts** — 캔들·지표·매매시점 마커. 초기 Chart.js(번들 작음), 복잡 차트 필요 시 ECharts로 교체 ADR 발행.
- **Tailwind CSS** — 유틸리티 퍼스트. 초기 CDN 허용, 프로덕션 전 빌드 파이프라인으로 전환.

## 화면 구성 (MVP)

| 경로 | 설명 | 주요 데이터 소스 |
|------|------|-----------------|
| `/` | 홈 대시보드(포지션 요약, 오늘 시황, 최신 AI 인사이트) | REST + WS |
| `/instruments` | 종목 검색·즐겨찾기 | REST |
| `/instruments/{code}` | 종목 상세(차트, 뉴스, AI 감성, 조건식 힌트) | REST + WS |
| `/strategies` | 전략 목록, 백테스트 실행/결과 | REST |
| `/strategies/{id}/backtest` | 백테스트 상세(성과, 거래 내역, AI 설명) | REST |
| `/portfolio` | 포지션·리밸런싱 제안 | REST |
| `/settings` | API 키·사용자 프로필(리스크, 선호 섹터) | REST |

## 레이아웃 규칙

- `layouts/base.html` — 상단 NAV, 좌측 사이드바, 메인 슬롯, 연결 상태 인디케이터(WebSocket OK/재연결)
- 부분 템플릿(`fragments/*.html`)을 HTMX 대상으로 반환
- 모든 숫자는 한국어 로케일(천 단위 콤마, 통화 기호 "₩")
- 날짜/시간은 KST + `YYYY-MM-DD HH:mm` 포맷

## 실시간 연동

- WebSocket 토픽 표준
  - `/topic/quotes/{code}` — 실시간 체결가
  - `/topic/orders` — 본인 주문 체결 이벤트
  - `/topic/ai-insights` — 새 AI 인사이트 알림
- 재연결 전략: 지수 백오프(1s→2s→4s→8s, max 30s), 연결 복구 시 최근 상태 재조회

## AI 인사이트 패널

- 카드 레이아웃(제목·요약·감성 라벨·'근거 보기' 토글)
- `reasoning` 필드는 기본 접힘, 펼치면 전체 표시
- 감성 색상 규칙: POSITIVE=초록(#16a34a), NEUTRAL=회색(#6b7280), NEGATIVE=빨강(#dc2626)

## 접근성·UX

- 키보드 단축키: `g h`(홈), `g s`(전략), `/`(검색 포커스), `r`(최신 데이터 새로고침)
- 주요 액션 버튼에 `aria-label`, 차트에는 `role="img"` + 텍스트 요약
- 색맹 고려: 매매 방향 기호(▲/▼)를 색상과 함께 사용

## 재실행/업데이트 지침

- 기존 URL·레이아웃은 보존, 새 화면은 추가 경로.
- 차트 라이브러리 교체는 ADR 선행 + 성능 비교 수치 첨부.
- Tailwind 클래스 과밀 → 공통 `fragments/_class-map.html` 추출.
- WebSocket 토픽명 변경 시 backend-core와 동시 변경 + `_workspace/frontend/ui-map.md` 업데이트.

## 에러 UX

- REST 실패: 토스트 + 해당 위젯만 에러 카드로 대체, 나머지 화면 유지
- WebSocket 끊김: 상단 배너 "실시간 연결 끊김 — 재시도 중…" + 재연결 시 자동 제거
- AI 응답 타임아웃(10초): 스피너 → "AI가 생각 중이에요" → 실패 시 "잠시 후 다시" 버튼
