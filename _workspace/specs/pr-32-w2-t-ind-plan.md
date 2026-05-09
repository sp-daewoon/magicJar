# PR-32 T-IND 실행 계획 (plan — PR 분할 + LOC + 의존성 그래프 + 일정)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-09
- 정합 SoT: `_workspace/specs/pr-32-w2-t-ind-design.md` v1 + `pr-32-dispatch-spec.md` v1 + `pr-32-conflict-matrix.md` v1
- 본 문서: PR 분할·sub-agent 분배·LOC 추정·일정·의존성 그래프·CHANGELOG 채번 매트릭스 일괄. spec(설계 무엇)·dispatch(누가)와 짝.

---

## §1. PR 시리즈 분할 매트릭스

| PR | 담당 | 라운드 | LOC | 영역 | base | 진입 조건 |
|---|---|---|---|---|---|---|
| **PR-32** | architect | spec only(R0) | ~600 LOC docs | _workspace/specs/{design,dispatch,conflict-matrix,plan}.md 4 파일 + CHANGELOG 슬롯 사전 발행 | main | **현재 — 본 PR** |
| **PR-33a** | strategy-engine | **R1+R2 통합** | ~3000 LOC code | consumer/.../indicator/source/{kis/Indicator{Trend,Momentum,Volume,Volatility,Oscillator,Other}.kt + KisStrategyBuilderSource.kt} + IndicatorEngineConfig + RedisIndicatorCacheAdapter + IndicatorController + IndicatorResultDto + 단위 테스트 5종 | main (PR-32 머지 후) | PR-32 머지 |
| **PR-33b** | backend-core | **R1+R2 통합** | ~800 LOC code | domain/.../indicator/* 7파일 + consumer/.../indicator/source/{NativeIndicatorSource.kt + native/Native{Ma,Ema,Rsi,Macd,BollingerBands}.kt} + (옵션) CrossCheckEngine + 단위 테스트 5종 | main (PR-32 머지 후) | PR-32 머지 |
| **PR-34** | frontend(메인) + strategy-engine + backend-core | R3 일괄 | ~1500 LOC code | frontend/.../IndicatorOverlay.tsx + IndicatorPicker.tsx + useIndicatorSeries hook + 80지표 정확성 fixture 일괄 (75 single + 5 multi) + 통합 e2e | main (PR-33a/b 머지 후) | PR-33a + PR-33b 둘 다 머지 |

**총 4 PR** (PR-32 spec → PR-33a/b 병렬 → PR-34 R3 일괄).

### 1-1. 단일 라운드 묶음 vs 분할 — architect 결정

| 옵션 | 장점 | 단점 | 결론 |
|---|---|---|---|
| (a) PR-33 단일 묶음 — 한 sub-agent가 80개+5종+REST+테스트 일괄 | 인터페이스 동결 자체 결정 가능 | XL+ 분량 (4500 LOC) → 코드 리뷰·머지 부담 + sub-agent 1명 wallclock 8h+ | 폐기 |
| (b) **R1+R2 통합 + 병렬 분할 (채택)** — strategy-engine·backend-core 병렬 + R3 일괄 | wallclock 25% 단축 + 영역 LOCK 명확 + add-only 100% 충돌 0 | 인터페이스 동결을 architect spec에서 결정 의무 | **채택** |
| (c) R1·R2·R3 직렬 — domain port → KIS source → REST → frontend | 각 단계 검증 명확 | wallclock 60%+ 증가 + 80지표 일괄이라 R1만 분리 비효율 | 폐기 |

(b)가 **사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합**.

---

## §2. 의존성 그래프 (KIS-first 정합 인용 의무)

```
                    ┌─────────────────────────────────────────────┐
                    │ KIS open-trading-api (외부 raw 출처)         │
                    │ /strategy_builder/core/indicators.py         │
                    │ License: MIT · 80개 함수 명세                │
                    └────────────────┬────────────────────────────┘
                                     │ raw fetch (헌법 §3 의무)
                                     ▼
                    ┌─────────────────────────────────────────────┐
                    │ _workspace/strategy/indicator-catalog.md     │
                    │ (v3.0.1 시점 작성된 80개 카탈로그 — 본 spec  │
                    │  §5 multi-output 매트릭스로 보강)            │
                    └────────────────┬────────────────────────────┘
                                     │
                                     ▼
                    ┌─────────────────────────────────────────────┐
                    │ ADR 0020 (KIS Strategy Builder 흡수 = 명세  │
                    │   port) + ADR 0021 (Source 추상화 plug-in)  │
                    └────────────────┬────────────────────────────┘
                                     │ implementation 명세 풀어냄
                                     ▼
                    ┌─────────────────────────────────────────────┐
                    │ PR-32 spec/plan/dispatch/conflict-matrix    │
                    │ (architect — 본 PR · 신규 ADR 0건)          │
                    └─────────┬───────────────┬───────────────────┘
                              │ 머지 후 병렬 디스패치
                  ┌───────────┴───────────┐
                  ▼                       ▼
        ┌──────────────────┐   ┌──────────────────────┐
        │ PR-33a           │   │ PR-33b               │
        │ strategy-engine  │   │ backend-core         │
        │ (KIS source 80개)│   │ (domain port +       │
        │ + IndicatorEngine│   │  Native 5종)         │
        │ + REST + 캐시    │   │                      │
        └─────────┬────────┘   └──────────┬───────────┘
                  │                        │
                  └───────────┬────────────┘
                              ▼
                    ┌─────────────────────────────────┐
                    │ PR-34 R3                        │
                    │ frontend IndicatorOverlay·Picker│
                    │ + 80지표 정확성 fixture 일괄    │
                    │ + 통합 e2e                      │
                    └────────────────┬────────────────┘
                                     │ 머지 + dogfooding 통과
                                     ▼
                    ┌─────────────────────────────────┐
                    │ T-IND DONE — W2 다음 트랙 진입  │
                    │ T-PAT (57패턴) 또는 T-DSL 가능  │
                    └─────────────────────────────────┘
```

**선행 트랙 (W1 완료)**:
- T-W2 (Watchlist Ingestion) — candle DB read 의존
- T-CA1 (Corp Action) — adjusted candle 옵션 의존
- T-MS1 (Market Session) — 캔들 timezone 정합
- T-IM1 (Instrument Master) — 종목 코드 검증 의존

W1 풀세트 v3.1.0 마감 완료 — 모든 prerequisite OK.

**후행 트랙 (T-IND 완료 후)**:
- T-PAT (W2) — 57패턴 (T-IND와 병렬 가능 — 영역 다름)
- T-DSL (W2) — DSL 파서·평가기 (`MA(20) > MA(60)` 같은 표현이 IndicatorEngine 호출)
- T-SB (W2) — Strategy Builder UI (IndicatorPicker가 IndicatorCatalog 사용)
- T-BT (W3) — 백테스트 (BacktestRunJob에서 IndicatorEngine 호출)
- T-SC (W3) — Screener (universe × DSL → IndicatorEngine 호출)
- T-SIG (W3) — Signal Engine (룰 매칭 시 IndicatorEngine 호출)

---

## §3. KIS-first raw fetch 의무 매트릭스 (헌법 §3 정합)

| 외부 출처 | URL | 인용 위치 | 담당 |
|---|---|---|---|
| KIS Strategy Builder indicators.py | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/indicators.py` | PR-33a strategy-engine 80개 함수 KDoc 줄번호 인용 | strategy-engine |
| KIS Strategy Builder README | `https://github.com/koreainvestment/open-trading-api/blob/main/strategy_builder/README.md` | ADR 0020 §1-2 인용 (이미 발행됨) | architect (PR-32 spec — 인용만) |
| 80지표 카탈로그 (Kotlin port 시 source-of-truth) | `_workspace/strategy/indicator-catalog.md` | spec §5 multi-output 매트릭스에서 참조 | architect |

**자체 추론 0**: PR-33a sub-agent는 80개 함수 시그니처(param·default·return tuple)를 indicators.py raw fetch 결과로만 결정. 임의 추론 발견 시 사용자 비판 risk + fitness gap 누적 (사용자 메모리 `feedback_kis_first_policy.md` 정합).

**검증 방법 (PR-33a sub-agent 책임)**:
1. `curl -sL https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/indicators.py > /tmp/kis-indicators.py`
2. `wc -l /tmp/kis-indicators.py` (예상 1500-2000 라인)
3. `grep -nE "^def calc_" /tmp/kis-indicators.py | head -20` (함수 시그니처 인덱싱)
4. 각 80개 함수 KDoc에 `Source: indicators.py:line_NN-line_MM` 줄번호 인용
5. multi-output 함수 (calc_macd·calc_bb 등) return tuple → values map key 매핑은 spec §5-2 LOCK 정합 의무

---

## §4. 우선 5종 후보 (NativeIndicatorSource — 사용자 결재 의제)

ADR 0021 §2-3 1차 구현 표 정합:

| ID | 이름 | priority | 사용 빈도 | KDoc reference |
|---|---|---|---|---|
| `ma` | 단순이동평균(SMA) | 50 (Native) | 차트 overlay 최상위 + DSL `MA(20) > MA(60)` 골든크로스 | KIS calc_ma |
| `ema` | 지수이동평균(EMA) | 50 (Native) | MACD 구성 요소 + 추세 추종 | KIS calc_ema |
| `rsi` | 상대강도지수(RSI) | 50 (Native) | 차트 overlay 최상위 + RSI 30/70 oversold/overbought 룰 | KIS calc_rsi |
| `macd` | MACD | 50 (Native) | 차트 overlay 최상위 + 골든크로스 룰 (multi-output) | KIS calc_macd |
| `bb` | 볼린저 밴드 | 50 (Native) | 차트 overlay 최상위 + 변동성 필터 (multi-output) | KIS calc_bb |

**선택 사유**: 사용자가 차트에서 가장 많이 토글하는 5종 + DSL `.kis.yaml` 프리셋 10개 중 9개에서 사용 + KIS와 cross-check 검증 가치 최상위.

**확장 후보 (post-MVP — Native 추가)**:
- `stochastic_k`·`stochastic_d` (K/D — 사용자 차트 자주)
- `atr` (변동성 + 백테스트 슬리피지 모델)
- `adx` (추세 강도)
- `vwap` (거래량 가중)
- `obv` (자금 흐름)

---

## §5. CHANGELOG 슬롯 채번 매트릭스 (헌법 §4 정합)

본 spec PR-32 머지 시점 CHANGELOG.md 정확 위치:

```
Line 5: | 2026-05-09 | docs(specs): PR-32 W2 T-IND 진입 — 80지표 + Source 추상화 ... (architect) | docs/spec | PR-32 W2 T-IND 진입 spec/plan |
Line 6: <!-- pr-32-slot-architect -->
Line 7: | 2026-05-09 | TBD: PR-33a T-IND strategy-engine — KisStrategyBuilderSource 80지표 + IndicatorEngineConfig + RedisIndicatorCacheAdapter + IndicatorController (작성: strategy-engine) — TBD | consumer/api | TBD |
Line 8: <!-- pr-32-slot-strategy-engine -->
Line 9: | 2026-05-09 | TBD: PR-33b T-IND backend-core — domain/indicator/* port 7개 + NativeIndicatorSource 5종 + (옵션) CrossCheckEngine (작성: backend-core) — TBD | domain/consumer | TBD |
Line 10: <!-- pr-32-slot-backend-core -->
Line 11: <!-- pr-32-slot-end -->
Line 12+: 기존 v3.1.0 마감 row 등 ...
```

**채번 룰**:
- architect row: 본 PR-32 머지 시점에 본문 LOCK (TBD 0)
- strategy-engine row: PR-33a 머지 시점에 sub-agent in-place edit (TBD → 실제 산출물)
- backend-core row: PR-33b 머지 시점에 sub-agent in-place edit
- 두 sub-agent slot row 모두 dispatch-spec §5-3 형식 정합 의무

**위반 시 risk**: anchor 위치 변경 시 git rebase 시 modify-shared 충돌 발생 (헌법 §4 정합 위반).

---

## §6. 일정 추정 (사용자 결재 받을 만한 wallclock)

본 추정은 sub-agent 1인 평균 + 사용자 dogfooding 시간 별도. KIS API 호출·CI 빌드·rebase 시간 포함.

| 단계 | wallclock | 종료 조건 |
|---|---|---|
| **PR-32 spec PR 발행 + 머지** | ~1.5h | architect spec 4 파일 발행 + 사용자 결재 + 머지 |
| **PR-33a · PR-33b 병렬 디스패치** | **~5h (max)** | KIS 80지표 일괄 4h + 단위 테스트 1h (병렬이라 max) |
| 사용자 dogfooding 1차 (REST endpoint 검증) | ~1h | DoD #1·#2·#3·#4·#5·#6·#7 통과 |
| **PR-34 R3 일괄** | ~3h | frontend overlay + 80개 정확성 fixture + e2e |
| 사용자 dogfooding 최종 (차트 overlay 검증) | ~1h | DoD #9·#11·#12·#14 통과 |
| **T-IND DONE** | **~11.5h 총** | W2 다음 트랙 진입 가능 |

**현실 보정**:
- 사용자 결재 빈도 + KIS raw fetch 검증 시간 따라 +20% 가능
- circular dep 발견 시 spec 갱신 +1.5h
- 80지표 정확성 fixture에서 drift 발견 시 +2~3h (Wilder smoothing·정밀도 정정)

**병렬 vs 직렬**:
- 직렬 (PR-33b 먼저 → PR-33a): wallclock ~14h
- **병렬 (PR-33a ⊕ PR-33b)**: wallclock ~11.5h (-18%)

---

## §7. 백테스트(T-BT)와의 인터페이스 정합

T-BT(W3) 진입 시 BacktestRunJob에서 IndicatorEngine 호출. 본 PR-32 spec에서 다음 사전 결정:

| 항목 | 결정 |
|---|---|
| BacktestRunJob의 candle source | 항상 `adjusted=true` (raw 폐기 — 룩어헤드 차단) |
| BacktestRunJob의 IndicatorEngine 호출 빈도 | 백테스트 매 틱 in-process 계산 (Redis 캐시 미사용 — backtest는 결정적 deterministic) |
| BacktestRunJob의 source priority | Native 우선 (priority=50) — KIS Python 호출 회피 |

**Source 추상화의 in-memory backtest 정합 검증**:
- IndicatorEngine은 candle list만 받음 — DB·Redis·in-memory 어디서 왔는지 무관
- T-BT의 in-memory backtest 캔들도 동일 IndicatorSource.calculate(spec, candles) 호출 가능 → ADR 0021 §1 사용자 명시 ("KIS 외에서도 받을 수 있게") 정합

---

## §8. fitness gap 검증 요약 (사용자 메모리 `feedback_v3_skepticism_no_regression.md` 정합)

본 plan에서 **자동매매 본체 quant trading 적합도 lens** 적용:

| 항목 | 자동매매 필요? | v1/v2 잔재? | 결론 |
|---|---|---|---|
| 80지표 일괄 | ✅ 필수 (T-DSL·T-SB·T-BT·T-SC·T-SIG 모두 의존) | ❌ (v1 RSI 단일 폐기) | 정합 |
| Source 추상화 | ✅ 필수 (사용자 명시 + Native 자체·KIS·TA-Lib·KRX 동일 인터페이스) | ❌ (v1 KIS hard-coded 회귀 차단) | 정합 |
| Native 5종 | ✅ 필수 (자체 신뢰성 + KIS cross-check) | ❌ | 정합 |
| Redis 캐시 | ✅ 사용자 차트 응답성 직결 | ❌ | 정합 |
| REST 단일 endpoint | ✅ T-DSL·T-SB·차트·screener 통합 | ❌ (v1 endpoint 분산 회귀 차단) | 정합 |
| AI 호출 0 | ✅ 룰 5 + ADR 0023 정합 | ❌ | 정합 |
| 신규 ADR 0건 | - | - | ADR 0021 §2 implementation 충분 (헌법 §3 정독 후 확정) |

**결론**: T-IND plan은 자동매매 본체 quant trading 신경계 — v1/v2 잔재 0건. 80지표는 룰 본체(W2-W3) first-class citizen.

---

## §9. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-09 | PR-32 T-IND 실행 계획 v1 발행. PR 4건 분할(PR-32 spec → PR-33a/b 병렬 → PR-34 R3 일괄) + LOC ~5900 추정 + wallclock ~11.5h 병렬 + 우선 5종(SMA·EMA·RSI·MACD·BB) Native 후보 + CHANGELOG 슬롯 채번 매트릭스 + KIS-first raw fetch 의무 + fitness gap 정합 검증. (architect, opus 4.7 1M) |
