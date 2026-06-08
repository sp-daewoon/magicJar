# 단타 수익 스크리너 · 체결강도 지표 설계 제안서

- 작성: 2026-06-08, strategy-engine agent (사용자 "돈 버는 것으로 피벗" 선언 후속)
- 상태: **제안 (사용자 결재 대기)** — 코드 변경 0. 결재 후 R1부터 구현 dispatch.
- 선행 자산: `2026-06-06-intraday-3m5m-strategy-candidates.md` (3/5분봉 후보 A·B·C) — 본 문서는 그 위에 **스크리너·체결강도·수익측정 인프라**를 보강하는 상위 설계. 중복 아님(전략 후보 ⊂ 본 스크리너 파이프라인).
- 원칙: 정교한 집행보다 **수익 나는 전략이 먼저**. 신규 코드 최소화 — 이미 있는 것/신설을 실코드로 정직 구분.

## direction-guard

```text
기존 방향: 룰 기반 quant 본체. 스크리너 = universe 필터(ScreenerFilter) + DSL(strategyId) + 인터벌(ScreenerCriteria),
  일봉 candle 영속 reuse로 KIS REST 호출 0. 분봉(1m 원천 + 3m~15h 합산) 6개월 이력 영속.
  백테스트 엔진은 interval "1m/5m/15m" 이미 지원. RVOL·ATR·거래대금은 별도 named 지표가 아니라
  DSL 조합(volume/volume_ma, atr, close*volume)으로 표현됨. 체결강도만 시스템 어디에도 없음.
변경 의도: (1) 체결강도 지표를 KIS가 이미 주는 raw 필드(H0STCNT0[18] CTTR)에서 흡수 — 자체 계산 신설 최소화.
  (2) 2단계(장전 일봉 setup → 장중 tick 확인) 스크리너를 기존 ScreenerEngine·DSL 재사용으로 구성.
  (3) 단타 필터를 기존 백테스트 엔진(분봉 interval)으로 돌려 alpha 측정하는 경로 확정.
방향성 영향: 유지 (보수적 보강). 신규 모듈 0. DSL 확장은 식별자 add-only(하위호환). watchlist 자동 삽입 0(STRATEGY owner 분리 정책 정합).
확인할 리스크: 장중 tick 기반 스크리너는 현 ScreenerEngine(일봉 candle reuse, KIS 호출 0 LOCK)과 데이터 출처가 다름
  → tick 경로는 별도 owner(STRATEGY KIS WS 수요)로 분리, 기존 일봉 스크리너 계약 불변.
```

---

## 1. 단타 지표 정밀 gap 분석 (실코드 기준)

### 1.1 이미 있는 것 (신설 불필요 — 실코드 인용)

| 단타 지표 | 시스템 내 실체 | 실코드 출처 | 단타 충분성 |
|---|---|---|---|
| **ATR** | `atr` (TR의 EMA, period 14 default) | `indicator-impl/.../kis/IndicatorVolatility.kt:107-117` (`KisIndicatorMath.ewm(trueRange)`) | 충분. 분봉에도 동일 적용. **NATR**(`natr`, ATR/close×100, 같은 파일 :120)이 단타 변동성% 비교에 더 적합 — 종목 간 절대값 비교 가능. |
| **RVOL (상대거래량)** | named 지표 **없음**. DSL 조합 `volume / volume_ma(N)`으로 표현 | `volume` 직접접근 = `JvmStrategyEvaluator.kt:583`, `volume_ma` = `IndicatorVolume.kt:45-54` (`rollingMean(volumes, period)`) | **부분 충분** — §1.3 참조. "직전 N봉 단순평균" 대비 비율은 가능. "동일 시각대(time-of-day) 평균" 정밀화는 불가(§1.3). |
| **거래대금 (turnover)** | named 지표 없음. 스크리너 score에 proxy 존재 + DSL `close * volume` | `ScreenerEngineImpl.kt:267` (`turnover = close.multiply(volume)`, log normalize :304), DSL은 `close * volume` 산술식 | 충분(일봉 setup용). 장중 누적거래대금은 KIS raw로 더 정확(§1.2). |
| **OBV** | `obv` (close 방향 × volume 누적) | `IndicatorVolume.kt:21-42` | 보유. 단타에서 우선순위 낮음(누적형 = 장중 노이즈). |
| **volume_ma** | `volume_ma` (거래량 SMA) | `IndicatorVolume.kt:45-54` | RVOL 분모로 사용. 충분. |
| **VWAP** | `vwap` (누적 TP×Vol / 누적Vol) | `IndicatorVolume.kt:57-78` | **단타 부적합** — 전체 시리즈 누적이라 "당일 리셋 VWAP"이 아님. 분봉 슬라이스를 당일분만 넣으면 근사 가능하나 세션 경계 인식 함수 부재(기존 plan §65 알려진 갭). |
| **MFI / Force / VWMA** | 모두 보유 | `IndicatorVolume.kt` | 보유. 단타 보조. |

**결론(있는 것)**: 사용자 예시 필터 4요소 중 **3개(갭상승·RVOL·거래대금)는 신규 지표 0으로 표현 가능**. ATR·NATR·volume_ma·turnover proxy 전부 실재.

### 1.2 없는 것 — 체결강도 (trade intensity): KIS가 이미 주는 raw, 흡수만 필요

체결강도는 indicator-impl·strategy-engine·domain 어디에도 없음. 그러나 **KIS WS H0STCNT0가 이미 계산해서 전송**한다 (자체 계산 불필요).

KIS-first raw 검증 (open-trading-api `domestic_stock_functions_ws.py` `ccnl_krx()` columns 배열):

| index | 필드 | 의미 | 현 파서 처리 |
|---|---|---|---|
| 13 | ACML_VOL | 누적거래량 | ✅ (date용 33만 읽고 13은 IDX_CNTG_VOL 12와 별개) |
| **14** | **ACML_TR_PBMN** | **누적거래대금 (원)** | ❌ **미추출** — 장중 거래대금 raw |
| 15 | SELN_CNTG_CSNU | 매도체결건수 | ❌ |
| 16 | SHNU_CNTG_CSNU | 매수체결건수 | ❌ |
| 17 | NTBY_CNTG_CSNU | 순매수체결건수 | ❌ |
| **18** | **CTTR** | **체결강도 (KIS 계산 완료값)** | ❌ **미추출** |
| 19 | SELN_CNTG_SMTN | 총매도체결수량 | ❌ |
| 20 | SHNU_CNTG_SMTN | 총매수체결수량 | ❌ |
| 21 | CCLD_DVSN | 체결구분 (1=매수,3=장전,5=매도 등) | ❌ |
| 22 | SHNU_RATE | 매수비율 | ❌ |

현 `KisWsMessageParser.parseH0Stcnt0()` (`consumer/.../ws/KisWsMessageParser.kt:163-237`)는 index 0·1·2·3·4·5·12·33만 추출하고 **14·18·19·20을 버린다**. `TickEvent`(`domain/.../tick/TickEvent.kt`)에도 필드 없음.

**체결강도 수식 — 두 경로, 둘 다 raw 정합**:

1. **KIS 직공급 (권장, index 18 CTTR)**: KIS가 누적 매수체결량/매도체결량×100으로 이미 계산. 사용자 예시 "체결강도120%" = `CTTR ≥ 120`. **추가 계산 0, 흡수만**. KIS HTS "체결강도"와 동일 정의라 사용자 직관과 1:1.
2. **자체 산출 (index 19·20에서)**: `체결강도 = SHNU_CNTG_SMTN(총매수체결량) / SELN_CNTG_SMTN(총매도체결량) × 100`. CTTR이 비어 오는 종목/시간대 fallback. 분모 0 시 200(상한) 처리.

→ 신설 작업은 "지표 계산 알고리즘"이 아니라 **WS 파서 필드 2~4개 흡수 + TickEvent 필드 추가 + 장중 평가 노출**. indicator-impl(일봉 candle 기반)에 넣을 대상이 아니라 **tick 경로 지표**임에 주의(§2.2).

호가(H0STASP0) 기반 보조: `KisWsOrderbookParser.kt`가 TOTAL_BIDP_RSQN(44)/TOTAL_ASKP_RSQN(43)을 파싱하면 **매수잔량/매도잔량 비율**(호가 압력)도 산출 가능 — 체결강도(체결 기반)와 상보. 단 현재 파서는 43개까지만 읽어 43·44 미추출. 체결강도(체결)가 1순위, 호가압력은 2차.

### 1.3 RVOL 정밀화 — "동시간대 평균" 가능성

사용자 의도의 RVOL은 통상 **"현재 시각까지 누적거래량 / 과거 N일 같은 시각까지 평균 누적거래량"** (time-of-day RVOL). 현 시스템:

- **현 표현 `volume / volume_ma(N)`**: 직전 N봉(같은 인터벌) 단순 거래량 비. 일봉이면 "오늘 거래량 / 최근 N일 평균 거래량" = 일봉 RVOL로 충분.
- **장중 time-of-day RVOL의 데이터 충분성**: ✅ 분봉 이력 충분. DB 실측 — `candle` 테이블 1m **1,736,247행 / 25종목 / 2025-12-08~2026-06-08(6개월)**, 5m 357,809행, 15m 123,939행. 같은 시각(예: 09:35) 슬롯을 과거 N일 추출해 평균 낼 raw는 존재.
- **갭(정직)**: time-of-day 정렬 누적은 현 `volume_ma`(단순 rolling)로 표현 **불가**. "당일 장 시작 이후 누적" 개념 자체가 DSL의 세션 인식 함수 부재(기존 plan §65)와 동일 한계. 정밀 time-of-day RVOL은 신규 지표(`rvol_intraday`) 또는 DSL 확장 필요.

**권장(과개발 경계)**: 1차는 `volume / volume_ma(20)` (분봉 직전 20봉 비)로 충분. KIS H0STCNT0가 **누적거래량(13)·누적거래대금(14)**을 tick마다 주므로, 장중 페이스 비교는 "누적거래대금(14) / (전일 동시각 누적거래대금)" tick 경로로 정밀화 가능 — 단 이건 §2.2 장중 스크리너 R2 범위. time-of-day RVOL 정식 지표화는 수익성 검증(R3) 후 필요시.

---

## 2. 우리 시스템 맞춤 2단계 단타 스크리너 설계

### 2.1 장전(pre-market) — 후보 풀 (기존 경로 100% 재사용)

전일 일봉 종가/거래대금/ATR로 "오늘 칠 만한 종목" 후보 풀 압축. **기존 ScreenerEngine 그대로** (`ScreenerCriteria(filter, strategyId, interval=ONE_DAY)`).

- **universe 1차 필터** (`ScreenerFilter`): markets=KOSPI+KOSDAQ, types=STOCK, `marketCapMin`·`avgVolumeMin`(5일 평균 거래대금) 활용 — 이미 존재. 동전주/관리종목 제외는 instrument active=true LOCK로 일부 cover, 가격 하한은 DSL로 보강.
- **setup DSL** (저장 strategy, buyCondition): 일봉 기반, 기존 DSL로 전부 표현 가능 —
  ```
  (close > 2000) AND (atr(14) / close > 0.015) AND (atr(14) / close < 0.06)
    AND (close * volume > 10000000000) AND (volume > 1.5 * volume_ma(20))
  ```
  (가격 ≥ 2000원, NATR 1.5~6%, 일거래대금 ≥ 100억, 거래량 setup) — `close`·`atr`·`volume`·`volume_ma`·산술·비교·AND 전부 화이트리스트 내(`KisYamlDslParser` 자체함수/캔들접근 + `IndicatorVolatility.atr`).
- **출력**: `ScreenerRunResult.matches` (rank·score·breakdown) — 장중 감시 대상 종목 풀. 이 풀을 **STRATEGY owner KIS WS 수요**로 등록(watchlist 자동 삽입 금지 — direction-guard 정합).

**신규 0.** 장전 스크리너는 오늘 당장 기존 코드로 돌아간다.

### 2.2 장중(intraday) — 진입 확인 (tick 실시간)

장전 후보 풀에 한해, 장중 KIS WS tick으로 **갭상승·RVOL·체결강도·거래대금 페이스** 실시간 확인.

- **데이터 출처**: KIS WS H0STCNT0 tick (실시간 권위 — CLAUDE.md "장중 실시간 권위는 KIS WS tick"). REST는 bootstrap/fallback.
- **현 ScreenerEngine은 일봉 candle reuse(KIS 호출 0 LOCK)라 장중 tick 평가를 못 함** → 장중은 별도 경로:
  - **경로 X (권장, 보수적)**: 진행 중 분봉(3m/5m) close가 Redis/aggregator로 갱신되므로(`TickToMinuteAggregator`), 장중 스크리너를 "최근 분봉 슬라이스 + 진행봉" 으로 기존 `StrategyEvaluatorPort.evaluate(ast, slice)` 재사용. 갭상승·RVOL·거래대금은 분봉 DSL로 표현(§2.3). 체결강도만 tick 필드 필요.
  - **체결강도 주입**: §1.2 흡수 후, tick의 CTTR을 평가 컨텍스트에 전달. DSL 식별자 `trade_intensity`(또는 `cttr`) add-only 신설 — 분봉 candle엔 없는 값이라 `EvalContext`에 tick snapshot 동반 필요(R1 범위). 1차 단순화: 체결강도는 DSL이 아니라 **진입 직전 게이트**(tick.cttr ≥ 120 boolean)로 코드 레벨 확인 — DSL 확장 회피하는 보수 경로도 가능.
- **갭상승 표현**: `(open - prev_close) / prev_close ≥ 0.03`. `open`·`prev_close` 둘 다 캔들 직접접근 화이트리스트(`JvmStrategyEvaluator.kt:568,572`). **일봉 슬라이스에선 prev_close=전일종가**라 갭상승 정확. 분봉 슬라이스에선 당일 첫 봉 open vs 전일 종가가 필요 → 세션 경계 인식 필요(갭). **1차 권장: 갭상승은 장전 스크리너(일봉)에서 판정**(전일 종가 대비 당일 시가는 시초 tick로 확정), 장중 분봉은 RVOL·체결강도·거래대금 페이스만 확인. 역할 분리가 세션함수 갭을 우회.

### 2.3 사용자 예시 필터 → 우리 DSL/스크리너 매핑 (가능/불가 명시)

사용자 예시: `갭상승3% AND RVOL2.5 AND 체결강도120% AND 거래대금500억`

| 조건 | 우리 표현 | 가능? | 비고 |
|---|---|---|---|
| 갭상승 3% | `(open - prev_close) / prev_close >= 0.03` | ✅ (일봉/시초) | 분봉 장중은 세션 경계 갭 — 장전 일봉 판정 권장 |
| RVOL 2.5 | `volume > 2.5 * volume_ma(20)` | ✅ (rolling) | time-of-day 정밀 RVOL은 ❌ (세션함수 부재, §1.3) |
| 체결강도 120% | `trade_intensity >= 120` (신설 식별자) 또는 진입 게이트 | ⚠️ 신설 필요 | KIS CTTR(index 18) 흡수 후 가능. tick 경로(분봉 candle엔 없음) |
| 거래대금 500억 | `close * volume >= 50000000000` (일봉) / 누적거래대금 tick | ✅ | 장중 정밀은 ACML_TR_PBMN(index 14) tick 흡수 시 |

**DSL 확장 식별자(add-only, 하위호환)**: `trade_intensity`(=cttr) 1종이면 사용자 예시 100% 표현. 선택적으로 `turnover_acc`(장중 누적거래대금), `rvol`(=volume/volume_ma 슈가) 추가 가능하나 **과개발 경계 — 1차는 trade_intensity 1종만**.

**불가/한계 정직 표기**:
- time-of-day RVOL(동시각 누적 평균 비), 당일 리셋 VWAP, ORB(시초 range breakout) → 세션 인식 함수 부재로 현 DSL 불가. 별도 ADR + R-later.
- 체결강도는 candle(영속 일봉/분봉)에 없는 tick-only 값 → 백테스트(과거 candle)에선 재현 불가(§3 중요 한계).

---

## 3. 수익 측정 연결 (핵심 — 돈 버는지)

### 3.1 현 백테스트 데이터 실측 (DB)

`backtest_run` 22건 interval 분포 (실측):

- **1d: 12건** / **15m: 8건** / **5m: 2건**.
- 분봉 run은 있으나 **전부 QA 검증용 scale-in 전략**(`qa_scalein_verify_s8`, `qa_scalein_signalrepeat_s9`)으로, **단타 진입 alpha(갭·RVOL·체결강도) 검증이 아님** — exit 로직(분할청산) 회귀 테스트 데이터.
- 최고치 참고(검증 무의미 — 표본·전략 성격): 5m scalein total_return 0.4166/175거래/승률0.80, 15m 0.2172/105거래. 단 이는 단타 entry 전략의 수익성이 **아니라** scale-in exit 시뮬레이션 산출물.

**결론: 단타 entry 전략의 alpha를 검증한 백테스트는 0건.** 돈 버는지 아직 모름.

### 3.2 백테스트 엔진 단타 적합성 (실코드)

- `BacktestRequest.interval`이 `"1m"·"5m"·"15m"` 명시 지원 (`domain/.../backtest/BacktestRequest.kt:14`). **분봉 백테스트 가능.**
- `BacktestPerformance` (`:32`)가 totalReturn·CAGR·MDD·Sharpe·winRate·profitFactor·tradingFrequency 전부 산출 — 단타 수익성 판정 지표 완비.
- 비용 모델: `taxModel="korean_default"`, `commissionModel="kis_direct"`, `slippageModel="fixed_0.05"` 기본 ON — 단타는 다빈도라 비용이 alpha를 먹으므로 **반드시 ON 유지**.
- 분봉 candle raw 충분: 5m 357,809행/25종목/6개월, 3m 595,618행, 15m 123,939행 (DB 실측). 백테스트 from~to 6개월 IS/OOS 분할 가능.

### 3.3 alpha 검증 방법 (수익 나는 전략 먼저)

**체결강도의 백테스트 한계(중요·정직)**: 체결강도(CTTR)는 tick-only 값으로 과거 candle에 영속되지 않음 → **체결강도 조건은 현재 백테스트로 재현 불가**. 따라서 수익 검증은 2층으로:

1. **A층 — candle 표현 가능 필터만 백테스트로 alpha 확정** (갭·RVOL·거래대금·ATR·돌파):
   - 기존 plan 후보 A/B/C(`2026-06-06-intraday-...md`)를 분봉 백테스트로 실행. 본 스크리너의 장전 setup DSL을 universe로, 분봉 진입 DSL을 strategy로.
   - 판정(기존 plan §56 정합 강화): IS 4개월 → 파라미터 1회 조정 → OOS 2개월. **OOS 기대값>0, 거래≥100, MDD≤8%**. 비용 ON. 음수면 폐기.
   - 이게 "돈 버는 코어"의 1차 증명. 체결강도 없이도 양의 기대값이 나와야 함(체결강도는 그 위 정밀화).
2. **B층 — 체결강도 incremental 가치는 MOCK 페이퍼/리플레이로 측정**:
   - A층 통과 전략을 MOCK 모드 실거동 + tick 체결강도 게이트 ON/OFF 두 버전으로 페이퍼 트레이딩 → 진입 품질(승률·평균손익) 비교. 백테스트 불가분을 라이브 A/B로 보완.

**즉시 실행 가능한 첫 수익 측정(R3)**: 후보 A(5m 신고가 돌파+거래량) + 장전 setup universe로 백테스트 1건 → `BacktestPerformance` 산출 → OOS 기대값 판정. 이게 "단타로 돈 버나?"의 최초 정량 답.

---

## 4. 구현 로드맵 (신규 최소화 — 대부분 이미 있음)

> 헌법 §개발룰 R1 도메인+시그니처 → R2 어댑터+영속 → R3 UI+테스트 정합. 한 PR = 한 영역.

### R1 — 체결강도 흡수 (지표 "신설"이 아니라 KIS raw 흡수)
- **범위**: `KisWsMessageParser.parseH0Stcnt0()`에 index 14(ACML_TR_PBMN)·18(CTTR)·19·20 추출 추가 → `TickEvent`에 `tradeIntensity: BigDecimal?`·`turnoverAccum: BigDecimal?` 필드 add-only.
- DSL 식별자 `trade_intensity` add-only (`KisYamlDslParser` 화이트리스트 + `JvmStrategyEvaluator` tick 컨텍스트 주입) — **또는** 더 보수적으로 DSL 미확장하고 장중 진입 게이트 코드 레벨로(체결강도≥임계 boolean). 사용자 선택지.
- **신규**: 파서 필드 4개 + TickEvent 2필드 + (선택)DSL 식별자 1종. 계산 알고리즘 신설 0 (CTTR은 KIS 완성값, fallback만 매수/매도체결량 비).
- KIS-first 의무: parser KDoc에 index 14·18·19·20 raw URL 인용.

### R2 — 단타 스크리너 프리셋 (신규 거의 0 — 기존 ScreenerEngine·DSL 조합)
- **범위**: 장전 setup 전략 1건(.kis.yaml/DRAFT, §2.1 DSL) + 장중 진입 전략 1~2건(기존 plan 후보 A/B). ScreenerEngine·StrategyEvaluator·ScreenerFilter 전부 재사용.
- 장중 후보 풀 → STRATEGY owner KIS WS 수요 등록 경로 확인(watchlist 비삽입). market-data와 owner/TTL 협의.
- **신규**: 저장 전략 정의(데이터) + 프리셋 노출. 엔진 코드 0.

### R3 — 백테스트 수익 측정 (엔진 재사용 — 신규 0, 실행+판정)
- **범위**: 후보 A를 분봉(5m) `BacktestRequest`로 실행, 장전 setup을 universe로. IS/OOS 분할 2회. 비용 ON. `BacktestPerformance` 수집 → 기대값/MDD/거래수 판정 리포트.
- 체결강도는 백테스트 재현 불가 명시 → B층(MOCK A/B) 후속.
- **신규**: 백테스트 호출 스크립트/요청 + 결과 정리. 엔진·스키마 0.
- 산출물: `_workspace/strategy/sample-backtest/` 단타 run 1~2건 + alpha 판정.

### 신규 코드 총량 (정직 집계)
- **신설**: WS 파서 필드 추출 4 + TickEvent 2필드 + (선택)DSL 식별자 1 + 저장 전략 정의(데이터) + 백테스트 실행 스크립트.
- **재사용(신설 0)**: ATR/NATR/volume_ma/turnover proxy, ScreenerEngine, ScreenerFilter, DSL parser/evaluator, 백테스트 엔진+성과지표, 분봉 candle 6개월, 비용 모델.
- 사용자 예시 필터 4요소 중 3개(갭·RVOL·거래대금)는 **코드 신설 0**. 체결강도 1개만 raw 흡수.

---

## 핵심 결론 요약

1. **단타 지표 gap = 체결강도 1종뿐.** RVOL·ATR·거래대금·OBV·volume_ma는 실재(DSL 조합/지표). 체결강도조차 KIS가 H0STCNT0[18] CTTR로 완성 전송 — 우리 파서가 안 읽을 뿐. → **"지표 개발"이 아니라 "raw 흡수".**
2. **수익 측정 경로 = 분봉 백테스트(A층) + MOCK A/B(B층).** 백테스트 엔진은 5m/15m 이미 지원, 분봉 candle 6개월 영속, 비용 모델 ON. 단 현 분봉 백테스트 22건은 전부 QA scale-in이고 **단타 entry alpha 검증 0건** → 후보 A 백테스트가 첫 정량 답. 체결강도는 tick-only라 백테스트 재현 불가(MOCK A/B로 보완).
3. **구현 우선순위 = R1 체결강도 흡수 → R2 스크리너 프리셋(기존 경로) → R3 백테스트 수익 측정.** 신규 코드 최소(파서 4필드+TickEvent 2필드+전략 데이터+실행 스크립트). 과개발 경계 정합 — "정교한 집행보다 수익 나는 전략 먼저".
4. **DSL 확장은 `trade_intensity` 1종 add-only면 사용자 예시 100% 표현.** time-of-day RVOL·당일 VWAP·ORB는 세션함수 부재로 현 불가 → 수익성 검증 후 필요시 별도 ADR.
