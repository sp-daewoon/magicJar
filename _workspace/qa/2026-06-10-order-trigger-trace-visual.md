# qa Visual 검증 리포트 — orderTrigger 키 사슬 추적 화면 (PR #906)

- **dispatch**: frontend 핵심 화면 변경 동반 Visual 검증 (헌법 §9-2 자동 의무)
- **대상 PR**: #906 (ADR 0115 §2-B 불변식 #4 — orderTrigger 키 사슬 추적 화면)
- **검증일**: 2026-06-10
- **검증자**: qa (품질 헌터)
- **브랜치**: agent/qa/order-trigger-trace
- **환경**: 라이브 docker (api·consumer·batch·frontend healthy), frontend 컨테이너 포트 3000
- **데이터**: 실데이터 005930 주문 185건·체결·trigger 분류 완료 (mock 합성 0)
- **spec**: `frontend/tests/e2e/s10-order-trigger-trace.spec.ts` (신규)
- **baseline**: `frontend/tests/e2e/s10-order-trigger-trace.spec.ts-snapshots/` (3 PNG, add-only)
- **evidence**: `_workspace/qa/screenshots/2026-06-10-order-trigger/` (4 PNG)

---

## 검증 데이터 키 사슬 (API 스모크 — GET /api/signals/699/trace?userId=74683223 → 200)

```
signal 699 (strategy 8 "모의 학습용 1분봉 MA10 터치 삼성전자", 005930 1m, userId 74683223)
  → 주문 #219 BUY 3주 STRATEGY_SIGNAL FILLED (triggerRefId=699)
        부분체결 2건: exec#198 1주 + exec#199 2주 @ 298,750원 = 체결 3주
  → 주문 #220 SELL 1주 STRATEGY_SIGNAL CANCELLED (체결 0)
  → summary: orderCount=2 · orderedQty=4 · filledQty=3
        strategyHeldQty=5 vs authorityHeldQty=5 → reconciled=true (정합)
        평단 299,000원 · 1회차 · 실현손익 -47,397.619원
```

---

## PASS/FAIL 매트릭스

| # | 시나리오 | 검증 항목 | 결과 | evidence |
|---|----------|-----------|------|----------|
| S10-1 | `/orders` 목록 발사 출처 badge | 6값 분류 badge 노출 (실데이터: 전략 사인·손절·수동·외부 4종 확인) | **PASS** | `s10-1-orders-list.png` |
| S10-1 | 〃 | Visual baseline 일치 (3회 재현) | **PASS** | baseline PNG |
| S10-1 | 〃 | 콘솔 에러 0 · 레이아웃 파손 0 | **PASS** | console 0 errors |
| S10-2 | `/orders/219` 상세 발사 출처 필드 | "발사 출처: 전략 사인 #699" badge 노출 | **PASS** | `s10-2-order-detail.png` |
| S10-2 | 〃 | 체결 내역 2건(1+2=3주) · 감사 이벤트 6건 정상 | **PASS** | evidence 확인 |
| S10-2 | 〃 | Visual baseline 일치 + 콘솔 0 | **PASS** | baseline PNG |
| S10-3 ★ | signal 699 키 사슬 추적 드릴다운 | 토글 버튼(aria-label) 존재 · 확장 동작 | **PASS** | `s10-3-...-panel.png` |
| S10-3 ★ | 〃 | 주문 #219 링크 노출 (화면-API 대조) | **PASS** | 토큰 단언 |
| S10-3 ★ | 〃 | 주문 #220 연결 노출 | **PASS** | 토큰 단언 |
| S10-3 ★ | 〃 | 요약: 주문 4주 → 체결 3주 → 전략 보유 5주 vs KIS 권위 5주 | **PASS** | 패널 baseline |
| S10-3 ★ | 〃 | **정합 badge(초록)** — strategyHeld 5 == authorityHeld 5 | **PASS** | 토큰 단언 + 패널 |
| S10-3 ★ | 〃 | 체결 타임라인(1주/2주 @ 298,750원) + 부분체결 ol | **PASS** | 패널 baseline |
| S10-3 ★ | 〃 | orderTrigger=STRATEGY_SIGNAL · triggerRefId=699 표기("전략 사인 #699") | **PASS** | 패널 baseline |
| S10-3 ★ | 〃 | #220 취소 badge + "체결 내역 없음" | **PASS** | 패널 baseline |
| S10-3 ★ | 〃 | Visual 패널 baseline 일치 (3회 재현) + 콘솔 0 | **PASS** | 패널 PNG |
| S10-4 | 존재하지 않는 signal trace | 404 시 "키 사슬 추적 데이터를 불러오지 못했습니다" 명시 (mock 합성 0) | **PASS** | `s10-4-trace-error.png` |

**총 16개 항목 전부 PASS. RED 회귀 0건.**

---

## 화면-API 대조 결과 (불변식 #4 핵심 — S10-3)

| 항목 | API 응답 | 화면 표기 | 일치 |
|------|----------|-----------|------|
| 연결 주문 수 | orderCount=2 | "이 사인이 발사한 주문 (2건)" | OK |
| 주문 #219 | BUY 3주 FILLED STRATEGY_SIGNAL | #219 매수 3주 · 체결 완료 · 전략 사인 #699 | OK |
| 부분체결 | exec 1주 + 2주 @ 298750 | "1. 1주 @ 298,750원 / 2. 2주 @ 298,750원" | OK |
| 주문 #220 | SELL 1주 CANCELLED | #220 매도 1주 · 취소 · 전략 사인 #699 · 체결 내역 없음 | OK |
| 체결 합 | filledQty=3 | 요약 "체결 3주" | OK |
| 전략 보유 | strategyHeldQty=5 | "전략 보유 5주" | OK |
| KIS 권위 | authorityHeldQty=5 | "KIS 권위 5주" | OK |
| 정합 | reconciled=true | 정합 badge(초록) | OK |
| 포지션 메타 | avgPrice 299000 · lots 1 · pnl -47397.619 | "평단 299,000원 · 1회차 · 실현손익 -47,397.619원" | OK |

---

## 회귀 검증 (S10-5)

- `/orders` · `/orders/:id` · `/strategy/list` 3 화면 콘솔 error/pageerror **0건** (known-noise vite.svg 404 제외).
- 레이아웃 파손 0 — 기존 OrderCard·SignalCard 레이아웃 보존, 신규 badge add-only 삽입.
- Visual baseline 3회 연속 재현 일치 — diff 0.

---

## 발견 결함

**기능·시각 결함 0건.** PR #906 frontend 구현이 frontend 작성 캡처 가이드(`_workspace/frontend/screenshot-desc/2026-06-10-order-trigger-trace.md`) 명세대로 완전 정합.

### 주의사항 (P3 — 결함 아님, qa Visual 운영 관찰)

| ID | 내용 | 영향 | 권고 |
|----|------|------|------|
| OBS-1 | Visual fullPage baseline(S10-1·S10-2)에 우측 STOMP 실시간 toast 영역이 포함됨. 현재 검증은 장 비활성 시간대라 toast 고정 → 3회 재현 통과. 단, **장중 실시간 toast 발생 시 toast 텍스트 변동이 false positive 회귀**를 유발할 수 있음. | 향후 장중 qa:visual 재실행 시 S10-1·S10-2 RED 가능 | frontend 협업 — toast 컨테이너에 안정적 `data-testid` 부여 → spec에서 `mask` 옵션 적용 (S10-3 패널 baseline은 이미 요소-스코프라 toast 영향 최소). qa 단독 spec 보강으로 흡수 가능, 운영 코드 0 touch. |
| OBS-2 | signal 699는 'today' 프리셋 첫 페이지(size 20, index 16)에 위치. signal 목록 ts 분포가 바뀌면 699가 첫 페이지를 벗어나 S10-3 드릴다운 탐색이 실패할 수 있음. | 데이터 노화 시 S10-3 탐색 실패 | spec은 20개 버튼 순회 + trace 응답 URL 매칭으로 견고하게 구현됨(현재 안전). 장기적으로 signal 699가 today 범위를 벗어나면 spec 상수 갱신 필요. |

### 비고 — API 단건 조회 계약 (결함 아님, 기록)

- `GET /api/orders/219` (userId 미지정) → 400. `GET /api/orders/219?userId=74683223` → 200. 프런트는 userId를 항상 부착하므로 화면 정상. userId 필수 계약은 의도된 설계.

---

## Visual baseline add-only 보존 검증

- 기존 baseline PNG (s1~s9) **0 touch**.
- 신규 발행 3 PNG: `s10-1-orders-list` · `s10-2-order-detail` · `s10-3-signal-699-trace-panel`.
- 운영 코드 0 touch — `frontend/tests/e2e/*` + `_workspace/qa/*` + CHANGELOG 1줄만.

---

## 종합 판정

> ✅ **GREEN baseline 확립.** PR #906 orderTrigger 키 사슬 추적 화면은 3 화면 모두 가이드 명세 완전 정합.
> 자본 path 직결 화면(Orders·Strategy)의 키 사슬 추적성(불변식 #4)이 사인→주문→체결→보유+정합 badge로 끊김 없이 화면 재현됨을 실데이터(signal 699 / 주문 219·220)로 실증. RED 회귀 0건.
> 운영 관찰 OBS-1(장중 toast mask)은 qa 단독 spec 보강 후보로 차기 dispatch 흡수.
