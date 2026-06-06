# 3분/5분봉 단타 후보 전략 설계안 (제안)

- 작성: 2026-06-06, Cowork 세션 (사용자 직접 요청)
- 상태: **제안 (사용자 승인 대기)** — 승인 시 strategy-engine agent 구현 dispatch
- 선행 조사: DSL(KisYamlDslParser)·백테스트(JvmBacktestEngine)·캔들 정책 v2·ADR 0109/0110/0111

## direction-guard

```text
기존 방향: 룰 기반 quant 본체. 분봉(1m 원천 + 3m~12h 합산) 수집·백테스트·RiskPlan(손절/익절/분할매수)·전략별 포지션 귀속까지 인프라 완비. 단, 저장된 프리셋은 일봉 위주이고 분봉 단타 전략은 아직 없음.
변경 의도: 기존 DSL·백테스트·RiskPlan 위에 3/5분봉 단타 후보 전략 2~3개를 "검증 출발점"으로 추가. 신규 모듈/계약 변경 없음.
방향성 영향: 유지 (보수적 보강 — 기존 자산 조합만 사용)
확인할 리스크: 데이터(분봉 이력 충분성), 자본 흐름(단타 다빈도 주문 × canary cap), 검증(walk-forward 미지원 갭), 과최적화
```

## 설계 원칙 (정직한 전제)

1. **목표는 승률이 아니라 양의 기대값.** 승률 × 평균익 − 패률 × 평균손 − 비용. 백테스트는 반드시 수수료+슬리피지+세금 모델 ON.
2. **과최적화 가드**: 전략당 자유 파라미터 ≤ 4개. In-sample(앞 4개월) / Out-of-sample(뒤 2개월) 분할 — walk-forward 미지원이므로 기간 분할 2회 실행으로 대체. OOS에서 기대값 음수면 폐기.
3. **최소 표본**: 백테스트 거래 수 100건 미만이면 판단 보류 (통계적 무의미).
4. **종목 필터가 전략의 절반.** 모든 종목 공통 적용 불가 — 아래 유니버스 필터 필수.
5. **자동매매 결정권 0 원칙 유지**: 후보는 사용자가 백테스트·리플레이로 검증하는 출발점이며, ACTIVE 전이는 사용자 결정.

## 공통 유니버스 필터 (스크리너 1차, 일봉 기준봉)

- 일평균 거래대금 상위 (예: 20일 평균 ≥ 100억 — 슬리피지 통제의 핵심)
- 주가 ≥ 2,000원 (동전주 제외), 관리/거래정지 제외
- 20일 ATR% 1.5~6% 구간 (변동성 없으면 단타 수익원 없음, 과도하면 손절 슬리피지 폭발)

## 후보 A — 분봉 신고가 돌파 + 거래량 확증 (5m, 추세 추종)

- 가설: 유동성 높은 종목이 장중 N봉 신고가를 대량 거래량과 함께 돌파하면 단기 모멘텀 지속.
- 진입 (DSL 예시, 시그니처는 카탈로그 정합 확인 후 확정):
  ```
  (close > highest(high, 20)) AND (volume > 2 * volume_ma(20)) AND (rsi(14) < 70)
  ```
- Exit: RiskPlan 레이어 — TP +1.2% / SL −0.8% (손익비 1.5), partial exit +0.8%에 50% (ADR 0111)
- 파라미터 3개 (N=20, vol배수=2, TP/SL셋). 주의: `highest`가 현재봉 포함인지 구현 확인 필요 (포함이면 N봉 이전 기준으로 조정).

## 후보 B — 볼린저 하단 과매도 반등 (3m, 평균 회귀)

- 가설: 급락 후 과매도 구간에서 단기 반등. 추세장에선 약하고 횡보장에서 효과 — A와 상호 보완(레짐 분산).
- 진입:
  ```
  (close < bb_lower(20, 2.0)) AND (rsi(7) < 25) AND (volume > volume_ma(20))
  ```
- Exit: TP +0.8% / SL −0.8% (손익비 1, 승률 의존형 — 단타형 TP≤SL 허용은 T-20260601-1532 반영 완료)
- 리스크: 하락 추세 역행 진입. 일봉 필터(예: 종가 > 일봉 ma(20) 종목만)를 스크리너 1차에서 강제해 완화.

## 후보 C — FVG 리테스트 진입 (5m, smart money)

- 가설: bullish FVG 형성 후 가격이 갭 상단을 리테스트할 때 진입 (기존 SmartMoneyIndicatorSource `fvg` 활용).
- 전제 확인 필요: `fvg` indicator의 DSL 출력 형태(존재 플래그인지 가격 레벨인지)에 따라 조건식 표현 가능 범위가 다름 → 구현 1단계에서 output 계약 확인 후 조건식 확정. FVG 가드 정책(계산기만으론 미완료, overlay 검증 흐름 연결)과 정합 유지.
- Exit: TP +1.5% / SL −0.7% (구조적 손절선 = FVG 하단)

## 검증 계획 (순서 LOCK)

1. 백테스트 IS 4개월 → 파라미터 1회만 조정 허용 → OOS 2개월 (수수료·슬리피지·세금 ON, 캔들 0건 종목은 aborted 그대로 — mock 금지)
2. 판정 기준: OOS 기대값 > 0, 거래 ≥ 100건, MDD ≤ 8%, 월별 수익 편차 확인
3. `/qa/scenario-replay`로 ScaleInExitCalculator decision 정합 확인
4. MOCK 모드 2주 이상 → 사용자 판단으로 REAL canary (ADR 0109 hard cap ENV 3개 필수, 단타 다빈도 주문이 `MAX_ORDERS_PER_DAY` 빨리 소진하는 점 인지)

## 알려진 갭 (이번 범위 밖, backlog 후보)

- **세션 인식 함수 부재**: 당일 시가 범위(ORB)·당일 리셋 VWAP를 DSL로 표현 불가 (현 VWAP는 전체 누적). ORB·VWAP 되돌림 전략은 DSL 확장(`session_open`, `bars_since_open`, `vwap_daily` 등) ADR 후 2차 후보.
- **walk-forward 미구현** (backlog §27-2) — 당분간 수동 기간 분할로 대체.
- **scale-in 라이브 toggle OFF** (ADR 0111) — 후보 전략은 우선 단일 진입으로 설계, 분할 매수는 리플레이 정합 후.

## 다음 단계

사용자 승인 → orchestrator 절차로 strategy-engine agent dispatch (`agent/strategy/intraday-candidates` 브랜치, task.mjs 4단계, author `bot+strategy@magicjar.local`). R1: 전략 정의(.kis.yaml/DRAFT) + fvg output 계약 확인 → R2: 백테스트 실행·결과 정리 → R3: 리플레이 검증.
