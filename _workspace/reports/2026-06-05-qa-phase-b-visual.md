# qa Visual 검증 보고서 — Phase B 핵심 화면 변경 (헌법 §9-2 의무 dispatch)

- **dispatch**: orchestrator 2026-06-05 — Phase B 핵심 화면 변경 3건 Visual 회귀 + 스모크
- **환경**: 라이브 docker (전 컨테이너 healthy — api·consumer·batch·frontend Up healthy), 정규장 중(KST 11:4x)
- **실행**: `pnpm playwright test tests/e2e/s7-phase-b-visual-verification.spec.ts` (chromium 1440x900, locale ko-KR, baseURL http://localhost:3000)
- **제약 준수**: 읽기성 액션만. 실주문·전략 상태 변경·설정 변경 0건. 리플레이는 sandbox(scenario_* only, KIS 주문 미발행 — ADR 0107)라 실행 허용. mock 0건(실 백엔드 응답).

## 종합 판정

**PASS** — Phase B 변경 3건 모두 의도대로 동작. Phase B 회귀 0건. Phase B와 무관한 사전 결함 P2 2건 분리 보고(favicon 404, GATE 라벨 raw enum 노출).

| 시나리오 | 대상 | 판정 | 비고 |
|---|---|---|---|
| S7-1 | PR #727 캔들 수집 배지 (005930) | **PASS** | FRESH/OK_REALTIME 녹색 점 정상, API 교차확인 일치 |
| S7-2 | PR #730 리플레이 게이트 이벤트 | **PASS** | GATE_SKIPPED + GATE_NOT_SIMULATED 정직 표시 |
| S7-3 | Codex 전략 리서치 신규 화면 | **PASS** | 진입·렌더 정상, Phase B 콘솔 에러 0 |

---

## S7-1 — PR #727 종목 상세 캔들 수집 상태 배지

**PASS.** `/symbol/005930` 진입 → `data-testid="candle-health-badge"` 차트 위(AI "차트 해석" 진입점 아래)에 렌더.

- **DOM evidence**: `data-tone="fresh"`, text = `"캔들 실시간 수집 정상"`, 녹색 점(`bg-emerald-400`) 표시.
- **API 교차확인** (가짜 FRESH 아님 검증):
  ```
  GET /api/symbols/005930/candle-health
  {"status":"FRESH","reason":"OK_REALTIME","reasonDetail":"실시간 수집 정상 (마지막 봉 0초 전)", ...}
  ```
  배지 tone=fresh ↔ API status=FRESH 일치 단언 GREEN.
- **STALE/NO_DATA reason 매핑 교차확인** (mock 강제 재현 금지 → 자연 상태 API 직접 호출):
  - `035720`·`005380` → `NO_DATA / WS_NOT_SUBSCRIBED` ("정규장 중이나 실시간(KIS WS) 구독이 없습니다"). 컴포넌트 `resolveTone`이 `NO_DATA → red` 매핑(코드 검증), reasonDetail 원문 표시 → 정직 경로 확인.
  - `004090` → `FRESH / OK_REALTIME` (마지막 봉 77초 전) — 정상.
- **스크린샷**: `screenshots/2026-06-05-s7-1-candle-health-badge.png`, `screenshots/2026-06-05-s7-1-symbol-detail-full.png`
- **콘솔**: Phase B 관련 error 0건 (favicon 404 1건은 P2 분리).

## S7-2 — PR #730 시나리오 리플레이 게이트 이벤트

**PASS.** `/strategy/scenario-replay` 진입(onboarding userId=74683223 seed) → strategy 44(000660·15m) 선택 → 2026-06-04 16:00~17:00 KST(장외 1시간) 리플레이 1회 실행(sandbox).

- **실 UX 흐름 확인**: 제출 → `onSuccess`가 `activeTab='history'` + `selectedRunId` 설정 → 타임라인(ScenarioReplayDetail) 직접 진입. (제출 즉시 타임라인 표시 — 별도 "완료 배너→타임라인 보기" 단계 없음. 첫 spec 초안이 가정한 중간 배너 단계는 실제로 없어 spec을 실 흐름에 맞게 정정함.)
- **게이트 이벤트 정직 표시** (라이브 POST 응답 + 타임라인 DOM 양쪽 확인):
  - POST `/api/scenario-replay/runs` 201 응답에서 수집한 GATE type: `["GATE_NOT_SIMULATED","GATE_SKIPPED"]`
  - 타임라인 DOM: `"리플레이 정규장 게이트 skip"` × 5 (GATE_SKIPPED — 장외 구간이라 정규장 게이트 정직 skip = MARKET_HOURS 성격), `"리플레이 미재현 게이트"` × 4 (GATE_NOT_SIMULATED: KILL_SWITCH_ON / DAILY_LOSS_LIMIT / TRADE_FREQUENCY_MINUTE / TRADE_FREQUENCY_DAY)
  - payload `reasonCode` = `GATE_NOT_SIMULATED:<gate>` 형태로 정직 명시.
- **스크린샷**: `screenshots/2026-06-05-s7-2-replay-timeline.png` (게이트 이벤트 노드 + 가상 캔들 차트 + 가상 포지션 패널)
- **콘솔**: error 0건.

## S7-3 — Codex 전략 리서치 신규 화면 (스모크)

**PASS.** `/strategy/research` 진입 → `<h1>전략 리서치</h1>` 렌더, "리서치 전용" 안내 + 리서치 폼 표시. AppHeader "전략" 드롭다운에 `/strategy/research` (label "전략 리서치") 메뉴 신설 확인.

- **스크린샷**: `screenshots/2026-06-05-s7-3-strategy-research.png`
- **콘솔**: Phase B 관련 error 0건 (favicon 404 1건은 P2 분리). 기능 심층 검증은 dispatch 범위 외(스모크 한정).

---

## 분리 보고 — Phase B와 무관한 사전 결함 (회귀 아님)

### P2-1 — `/vite.svg` favicon 404 (전 페이지 간헐)
- `index.html`이 `<link rel="icon" href="/vite.svg">` 참조하나 docker nginx 빌드에 해당 파일 미포함 → `GET /vite.svg` 404. `favicon.ico`도 404.
- 모든 라우트에서 콘솔 `Failed to load resource: ... 404` 1건 간헐 출현(브라우저 캐시 타이밍 의존). Phase B 3건과 무관한 사전 결함.
- **영향**: 기능 영향 0(탭 아이콘 미표시뿐). **분류 P2(cosmetic)**. fix 후보: vite public/ 에 svg 추가하거나 index.html 참조 제거. → **frontend** 후속.

### P2-2 — 리플레이 타임라인 GATE 이벤트 라벨에 raw enum 노출
- `scenarioReplayFormat.ts` `eventLabel()`에 `GATE_SKIPPED`·`GATE_NOT_SIMULATED` 한국어 매핑 부재 → 이벤트 노드 상단 라벨 배지에 raw enum 문자열(`GATE_SKIPPED`) 그대로 표시. (제목·본문은 한국어로 정상 — "리플레이 정규장 게이트 skip" 등.)
- **영향**: 기능 정상, 정직성 OK. 다만 UI 일관성상 라벨도 한국어 권장. **분류 P2(polish)**. fix 후보: `eventLabel` labels 맵에 GATE 2종 한국어 추가. → **frontend** 후속(선택).

---

## 산출물
- `frontend/tests/e2e/s7-phase-b-visual-verification.spec.ts` — Playwright 검증 spec (S7-1·2·3)
- `frontend/tests/e2e/s7-phase-b-visual-verification.spec.ts-snapshots/*.png` — baseline 5건 (add-only)
- `_workspace/reports/screenshots/2026-06-05-s7-*.png` — evidence 사본 4건
- `_workspace/reports/2026-06-05-qa-phase-b-visual.md` — 본 보고서

**최종 1줄 판정: Phase B 핵심 화면 변경 3건(PR #727·#730·Codex 전략 리서치) 모두 라이브에서 의도대로 동작 — 회귀 0건, PASS. 사전 결함 P2 2건(favicon 404·GATE 라벨 raw enum)만 frontend 후속 분리.**
