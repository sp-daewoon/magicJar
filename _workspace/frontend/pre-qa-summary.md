# web(frontend) 모듈 — Phase B-2 pre-QA 작업 요약

**시점:** 2026-04-25 17:20 KST · **상태:** Phase C(QA) 검수 직전
**브랜치:** `agent/frontend/dashboard-journal-harness` · **PR:** #5 (base=qa)
**에이전트:** frontend · **스킬:** magicjar-dashboard, magicjar-trading-journal, magicjar-harness-dashboard

## 무엇을 작업했나

| 영역 | 산출물 |
|------|-------|
| 컨트롤러 | `Home` · `Instrument` · `Strategy` · `Portfolio` · `Journal` · `Harness` 6개 |
| Thymeleaf 템플릿 | `layout` · `home` · `instrument` · `portfolio` · `strategies/list` · `strategies/backtest` 7개 |
| REST API (SpringDoc) | `BacktestApi` · `JournalApi` · `HarnessApi` |
| 서비스 | `HarnessStateReaderImpl` (`_workspace/_harness-state.json` + `.claude/agents/{name}.md` + `_workspace/qa/issues.md` 파싱) · `WorkspaceTreeReader` · `ClaudeMdChangeLogReader` |
| WebSocket | `WebSocketConfig` + `QuoteStreamHub` (실시간 시세 — market-data가 이벤트 발행 시 연결, 현재는 빈 컨테이너) |
| Stub | `StubPortConfig` — 다른 어댑터 미통합 상태에서도 `:app:bootRun` 가능하게 |
| 정적 리소스 | Tailwind CDN(3.x) · HTMX 1.9 · Alpine 3 · Chart.js 4 + financial 플러그인 |
| 설정 | `application-web.yml` (한국어 locale, KST, UTF-8) |

## 빌드 상태
- `./gradlew :web:build` → **BUILD SUCCESSFUL**
- `./gradlew ktlintCheck` → **BUILD SUCCESSFUL**

## 의존 / 경계
- core 포트만 의존: `HarnessStateReader` 구현체 제공 + 다른 포트는 조회용 주입
- 직접 import 외부 어댑터 모듈 없음

## QA 스크린샷 게이트 (ADR 0011) — Phase C에서 수행
경로: `_workspace/qa/screenshots/agent-frontend-dashboard-journal-harness/`
필수:
- `01-home-happy.png` (홈 대시보드)
- `02-journal-list-empty.png` (매매일지 목록 — 빈 상태)
- `03-harness-phase-b.png` (`/harness` 운영 페이지)

해상도 1440×900, INDEX.md(파일명/화면/상태/캡처 시각/메모) 필요.

## QA 체크 포인트
- `:app:bootRun --args='--spring.profiles.active=mock'` → 6개 페이지 200 응답
- AI 비활성 배너 표시 여부(`th:if="${!aiEnabled}"`)
- 한국어/KST 표시 정확성
- HarnessStateReader가 실제 `_workspace/_harness-state.json`을 정확히 파싱(현재 phase=B_DONE)

## 알려진 TODO (후속)
- 종목 페이지 실시간 시세는 폴링 기반 MVP (3s HTMX) — core에 `RealtimeQuoteEvent` 추가 후 WS 전환
- 백테스트 트리거는 strategy 모듈 통합 후 활성 (현재 `NotImplementedBacktestService`)
- Tailwind CLI 빌드 전환 (현재 CDN)
