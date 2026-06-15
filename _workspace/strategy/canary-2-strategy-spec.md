# canary 전략 2종 정의 (1분봉 + 5분봉)

> strategy-engine · 2026-06-15 · canary 실계좌 소액 테스트용. 등록 상태 **PAUSED**(자본 안전).
> evidence: `sample-backtest/canary-2-backtest-evidence.md` · 등록 SQL: `sample-backtest/canary-2-register-PAUSED.sql`

## 설계 동기 — #86 매도 결함

전략 #86 `canary_1m_ma10_touch_093370` 실측 결함:
- dsl_entry `(low <= ma(10) * 1.002) AND (close >= ma(10))`, dsl_exit `close < ma(10)` — **진입·청산이 같은 ma(10)**.
- ma(10) 바로 위에서 사고 살짝 아래로 빠지면 즉시 청산 → 익절(1%) 도달 전 진동 소손실 회전.

**해소 4원칙** (백테스트로 검증해 채택):
1. **진입 지표 ≠ 청산 지표** — 진입 `donchian_lower(20)`(지지 반등) / 청산 `ema(20)`(추세선 이탈).
2. **청산 버퍼** — `close < ema(20) * 0.988`(1m) / `* 0.985`(5m). 노이즈 dip은 무시, 명확한 이탈만 매도.
3. **min_hold_bars** — 진입 후 N캔들 동안 DSL 매도만 지연(손절·익절은 항상 작동 — 안전망 우선).
4. **take_profit_pct ≥ 2.2** — 익절이 주 청산이 되게(#86은 1.0이라 진동에 먹힘).

## 전략 A — 1분봉 (삼성전자 005930, 전략 id 88)

| 필드 | 값 |
|---|---|
| name | `canary_1m_donchian_bounce_005930` |
| tf / intervals | `1m` / `["1m"]` |
| codes | `["005930"]` (KOSPI 최상위 유동성, 1m 8.1만봉) |
| dsl_entry | `(low <= donchian_lower(20) * 1.003) AND (close > donchian_lower(20)) AND (close >= ema(20) * 0.99) AND (rsi(14) < 75)` |
| dsl_exit | `close < ema(20) * 0.988` |
| take_profit_pct | **2.2** |
| fixed_stop_loss_pct | 2.0 |
| min_hold_bars | 8 |

진입 의미: 하단 돈치안(20봉 저가) 근처로 눌렸다가 그 위로 종가 회복(지지 반등) + 광의 상승추세(ema20 근처 이상) + 과매수 아님(rsi<75).

## 전략 B — 5분봉 (SK하이닉스 000660, 전략 id 89)

| 필드 | 값 |
|---|---|
| name | `canary_5m_donchian_trend_000660` |
| tf / intervals | `5m` / `["5m"]` |
| codes | `["000660"]` (KOSPI 유동성+변동성 양호, 5m 1.6만봉) |
| dsl_entry | `(low <= donchian_lower(20) * 1.005) AND (close > donchian_lower(20)) AND (close >= ema(20))` |
| dsl_exit | `close < ema(20) * 0.985` |
| take_profit_pct | **3.0** |
| fixed_stop_loss_pct | 2.5 |
| min_hold_bars | 4 |

진입 의미: 하단 돈치안 지지 반등 + 상승추세 확인(종가 ≥ ema20). 5분봉이라 TP 폭을 3.0%로 넓힘.

> 종목 사유: 후성(093370)은 #86이 사용 + 1m 1,170봉뿐(데이터 빈약)이라 회피. 005930·000660은 둘 다 6개월치
> 1m/5m 캔들이 가장 풍부해 백테스트·라이브 평가가 안정적.

## 검증 요약 (#86 대비)

| | #86 | A(1m) | B(5m) |
|---|---|---|---|
| 익절도달률(TP-hit%) | 6~8% | 48% | 48~55% |
| DSL 매도 진동 청산(churn) | 1,597~1,941건 | 14~25건 | 4~6건 |
| 승률 | 19~26% | 48% | 52~57% |

전체 표·방법은 evidence 문서 참조.

## 등록 상태 · 활성화

- **등록 상태: PAUSED** (DB 실측 — id 88·89). canary cap 3종(QTY/NOTIONAL/DAILY)이 0=비활성이라 ACTIVE면 cap 없는 실매매가 나가므로 절대 ACTIVE 등록하지 않음.
- **활성화(사용자 직접)**: cap 점검 후, 한 번에 1개만 권장.
  ```sql
  UPDATE strategy SET status='ACTIVE', paused_at=NULL, updated_at=NOW()
  WHERE name='canary_1m_donchian_bounce_005930';   -- 또는 ..._5m_..._000660
  ```
  PAUSED 복귀: `UPDATE strategy SET status='PAUSED', paused_at=NOW(), updated_at=NOW() WHERE name=...;`

## evaluator audit 보강 (함께 반영)

`RealtimeSignalGenerator.buildSignalContextJson` — 평가값(trigger·lhs/rhs·lhsValue/rhsValue·profitPct 등)을
BUY/SELL 모두 `signal.context_json`의 `eval.*`에 직렬화. #86 분석 때 context_json이 비어 캔들 추정에 의존했던
공백을 해소(사후 "왜 이 신호가 났나" 검증 가능). exitPriceType/exitLimitPrice 라우팅 키는 보존(하위호환).
