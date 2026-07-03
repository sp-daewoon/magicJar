# ADR 0137 — DSL 청산 휩쏘 완화 (dsl_exit 버퍼 0.3% + min_hold_bars 8)

- 상태: **Accepted (2026-07-03, 사용자(최고 관리자) 결재)**
- 작성일: 2026-07-03
- 결정자: 사용자(최고 관리자) — 2026-07-03 전 영역 감사 부록 A "금호타이어 3,690 매수 → 37분 후 3,675 매도 반복 패턴" 추적 결과 직접 결재
- 입력 권위: `_workspace/audit/2026-07-03-system-audit.md` 부록 A (코드 실측 — 원인 1위 "DSL 청산조건 vwap/ma20 하향 이탈 휩쏘 → MARKET 매도" 확신도 높음)
- 관계:
  - **ADR 0129 amend(레버4)** — V94가 전 AUTOPILOT 전략에 `min_hold_bars=3` 소급 주입("vwap-cross 즉시청산 휩쏘 완화" 목적)했으나 3봉(3m tf = 9분)으로는 불충분 실증 → 8봉 상향.
  - ADR 0114(주문 집행 정책 — 진입 marketable limit ask1, cap +0.2%) · ADR 0132(INTRADAY_REALISTIC 백테스트 — 재검증 경로) · ADR 0133(ACTIVE 검증 게이트)
  - 감사 §2-② (LEGACY 백테스트가 본 청산 경로를 재현하지 못해 라이브에서만 관측된 구조)
- 마이그레이션: **Flyway V97** — 전 AUTOPILOT 전략 dsl_exit 버퍼·min_hold_bars 소급 적용

---

## §1. 컨텍스트

REAL 계좌에서 여러 종목 반복 패턴: 매수 후 ~37분 보유 → 소폭 손실(-0.4%대) MARKET 매도. 감사 부록 A 코드 실측 메커니즘 체인:

1. 진입 = marketable limit **ask1 그대로**(`EntryPricePolicy`, cap +0.2%) → 항상 스프레드 위 매수.
2. minHold 3봉(3m tf = 9분) 동안만 DSL 매도 억제(`RealtimeSignalGenerator.kt:552-556`, V94).
3. 돌파 실패 → `close < vwap()` 참 → SELL 시그널 (버퍼 0 — VWAP 1틱 아래도 즉시 발사).
4. DSL 매도는 exitContext 부재로 **무조건 MARKET**(`SignalGeneratedKafkaListener.kt:508-510`) → bid 체결.
5. ask 매수 + bid 매도 스프레드 + 수수료·거래세 = 왕복당 -0.3~-0.5% 고정 잠식 (`consumer/application.yml:167` 주석 실측: 2026-06-23 122640 ~8왕복, 거래비용 46% 잠식).
6. LEGACY 백테스트는 이 청산 경로를 미평가(감사 §2-②)라 사전 검출 불가.

## §2. 결정 (LOCK)

1. **dsl_exit 하향 이탈 버퍼 0.3%** — vwap/ma 하향 이탈 계열 청산조건은 기준선 대비 0.3% 버퍼를 둔다 (`close < vwap() * 0.997` 류). 기준선 1틱 밑 휩쏘로 스프레드 비용만 확정하는 경로 차단.
2. **min_hold_bars 3 → 8** — AUTOPILOT 전략 default 상향(3m tf 기준 9분 → 24분). 손절(SL)·익절(TP)·VI·EOD 안전망은 min_hold 우회 유지(ADR 0127·0129 정합 — 본 결정은 DSL SELL만 지연).
3. **V97 마이그레이션** — 전 AUTOPILOT 전략에 1·2를 소급 적용(V94의 min_hold_bars=3 소급 주입 선례 패턴).

## §3. 비결정 (후속 검토 — 본 ADR 범위 밖)

- 이익 구간에서만 vwap-exit 허용 + 손실 구간은 SL 위임 게이트 (감사 부록 A 권장 1 후단)
- DSL 청산 MARKET → bid1 marketable limit 전환 (체결 보장 vs 비용 — 사용자 결재 별도)
- TRAIL `activatePct`(수익 활성 임계) 추가 (부록 A 원인 2위 — DB 확인 후)
- `.env` PER_TRADE_PCT/POOL_PCT/MAX_POSITIONS 자본 하니스 축소

## §4. 검증

- 6전략 dsl_exit를 INTRADAY_REALISTIC(ADR 0132)로 재검증 의무 — LEGACY 백테스트는 본 경로 미재현이므로 검증 수단으로 사용 금지.
- 관측: SELL 시그널 reason 분포·왕복당 비용·재진입 왕복 수 before/after 대조.

## §5. 롤백

V97 down(값 원복) 또는 전략별 dsl_exit/min_hold_bars 수동 원복 — 평가 엔진 코드 계약 변경 0.
