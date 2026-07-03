# ADR 0138 — KIS WS slot owner priority 기반 eviction 구현 (DETAIL evict · ADR 0114/0123/0124 완성)

- 상태: **Accepted (2026-07-03, 사용자(최고 관리자) 결재 — 감사 개선 Wave 구현 동반)**
- 작성일: 2026-07-03
- 결정자: 사용자(최고 관리자) — 2026-07-03 전 영역 감사 §2-③ 지적("slot 경합 시 priority 기반 eviction 미구현 — 정책 반쪽 이행") 후속
- 입력 권위: `_workspace/audit/2026-07-03-system-audit.md` §2-③
- 관계:
  - **ADR 0114 P3 완성** — HOLDING/PENDING_BUY evict 절대 금지 LOCK은 기구현·계승, 본 ADR이 선점(eviction) 측을 채움.
  - **ADR 0123 §eviction 완성** — 단일 ledger(`WsSlotBudgetLedger`)·OPSP0008 graceful path는 기구현, owner priority victim 선정이 본 ADR.
  - **ADR 0124 결정 C 완성** — priority HOLDING(0) > PENDING_BUY(5) > STRATEGY(10) > DETAIL(20), "slot 경합 시 DETAIL evict·STRATEGY 보호" 조항의 구현 확정.

---

## §1. 컨텍스트

감사 실측(`consumer/.../ingestion/RealtimeSubscriptionRouter.kt:278-329`): victim 선정이 "어느 demand에도 없는 잔여 구독"만 대상이고 `priority`는 로그에만 사용됐다. 후보가 없으면 신규 요청이 `queued`(구독 안 함)로 방치되어, PRICE_TICK_MAX=30이 차면 신규 ACTIVE 전략·신규 보유 종목이 tick을 못 받고 degraded feed로 강등 — "전략 수·화면 사용이 늘수록 매매 경로가 멍청해짐"(규모 비례 시세 기아). 통합 ledger admission은 40 초과 시 신규 HOLDING까지 보류하는 역전도 있었다.

## §2. 결정 (LOCK)

1. **victim 선정 = priority 최댓값 우선** — 잔여(무수요) 구독 소진 후에도 slot 부족이면 priority가 가장 낮은(값이 큰) owner 구독을 evict. 실질 1순위 victim = **DETAIL(20)**.
2. **HOLDING(0)·PENDING_BUY(5) evict 절대 금지** — ADR 0114 LOCK 무변경 계승. 신규 HOLDING/PENDING_BUY 수요는 admission에서 보류 대상이 아니라 **선점 트리거**(DETAIL→STRATEGY 순 victim 확보 후 즉시 구독).
3. **STRATEGY(10) 보호** — DETAIL victim이 소진된 뒤에만 STRATEGY evict 가능(HOLDING/PENDING_BUY 수요 한정). 매매 경로 > 관찰 경로(ADR 0124 결정 C).
4. **queued 발생 시 silent 금지** — WARN 로그 + Slack 알림(헌법 §11-2 silent fallback 금지 정합).

## §3. 비결정 (후속)

- evict된 DETAIL 종목의 사용자 화면 degraded 표시 UX(REST fallback 배지)는 frontend 후속.
- eviction 카운트 Prometheus 게이지·알림 임계는 감사 §3-⑧ 알림 스택 트랙과 합류.

## §4. 검증·롤백

- 시나리오: slot 만석 + 신규 HOLDING 수요 → DETAIL evict + HOLDING 즉시 구독 / DETAIL 소진 + 신규 HOLDING → STRATEGY evict / 신규 DETAIL 수요는 만석 시 queued+WARN(선점 없음).
- 롤백: eviction 분기 toggle off 시 기존(잔여 구독만 victim) 동작 — ledger·구독 wire 계약 변경 0.
