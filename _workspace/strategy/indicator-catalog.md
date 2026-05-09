# KIS Strategy Builder 80지표 카탈로그 (KIS-first Raw Fetch 정합)

> **raw 출처**: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/indicators.py`
> **fetch 시점**: 2026-05-09 (1507 lines)
> **License**: MIT
> **담당**: market-data (PR-33 KIS-first 정합 검증)
> **갱신 목적**: strategy-engine sub-agent가 KisStrategyBuilderSource 80지표 어댑터 작성 시 자체 추론 0 보장 — 본 카탈로그 직접 인용 의무

---

## 카탈로그 사용 안내

각 항목 필드:

| 필드 | 설명 |
|------|------|
| **id** | `IndicatorSpec.name` 값 (REST `?name=` 파라미터) |
| **KIS 함수명** | `indicators.py`의 `def calc_xxx` 정확 이름 |
| **줄번호** | indicators.py 내 `def calc_xxx(` 시작 줄 (raw fetch 정합) |
| **입력 파라미터** | Python 시그니처 그대로 — Kotlin port 시 1:1 정합 의무 |
| **출력 keys** | `IndicatorPoint.values: Map<String, BigDecimal>` key 목록 (spec §5 LOCK) |
| **최소 캔들 수** | candles.size < N → InsufficientCandlesException 기준 |
| **비고** | 특이사항 (multi-output 분리 방식, OHLCV 의존 등) |

---

## TREND (추세) — 20개

| id | KIS 함수명 | 줄번호 | 입력 파라미터 (Python 시그니처) | 출력 keys | 최소 캔들 | 비고 |
|----|-----------|--------|--------------------------------|-----------|-----------|------|
| `ma` | `calc_ma` | L15 | `period: int, column: str = "close"` | `{ma}` | period | close 컬럼 rolling mean |
| `ema` | `calc_ema` | L264 | `period: int, column: str = "close"` | `{ema}` | period | ewm(span=period, adjust=False) |
| `wma` | `calc_wma` | L1074 | `period: int = 20` | `{wma}` | period | Wilder 방식 — ewm(alpha=1/period). 전통 선형가중 WMA 아님 |
| `hma` | `calc_hma` | L952 | `period: int = 20` | `{hma}` | period | 2*WMA(half)-WMA(full) + sqrt 기간 rolling |
| `dema` | `calc_dema` | L965 | `period: int = 20` | `{dema}` | period | 2*EMA1 - EMA(EMA1) |
| `tema` | `calc_tema` | L990 | `period: int = 20` | `{tema}` | period | 3*EMA1 - 3*EMA2 + EMA3 |
| `trima` | `calc_trima` | L1037 | `period: int = 20` | `{trima}` | period | SMA의 SMA (half=(period+1)//2) |
| `lwma` | `calc_lwma` | L1029 | `period: int = 20` | `{lwma}` | period | 선형 가중 MA (weights=1..period, numpy dot) |
| `t3` | `calc_t3` | L1046 | `period: int = 20, volume_factor: float = 0.7` | `{t3}` | period | Tillson T3 — 6단 EMA 합성 (c1~c4 계수) |
| `zlema` | `calc_zlema` | L1065 | `period: int = 20` | `{zlema}` | period | lag=(period-1)//2 보정 후 EMA |
| `kama` | `calc_kama` | L1000 | `period: int = 20` | `{kama}` | period | ER 기반 SC — fast_sc=2/3, slow_sc=2/31 |
| `alma` | `calc_alma` | L1017 | `period: int = 20, sigma: float = 6.0, offset: float = 0.85` | `{alma}` | period | 가우시안 가중 MA |
| `frama` | `calc_frama` | L1081 | `period: int = 20` | `{frama}` | period | fractal dimension 기반 alpha 계산 |
| `vidya` | `calc_vidya` | L1107 | `period: int = 20` | `{vidya}` | period | CMO 절댓값 기반 adaptive MA |
| `macd` | `calc_macd` + `calc_macd_signal` + `calc_macd_histogram` | L312 + L339 + L365 | `fast_period: int = 12, slow_period: int = 26, signal_period: int = 9` | `{macd, signal, histogram}` | slow_period | **multi-output** — KIS에서 3 별도 함수. Kotlin port: 3 함수 호출 후 합성 |
| `ichimoku_tenkan` | `calc_ichimoku_tenkan` | L938 | `period: int = 9` | `{tenkan}` | period | (high.max + low.min) / 2 over period |
| `ichimoku_kijun` | `calc_ichimoku_kijun` | L945 | `period: int = 26` | `{kijun}` | period | (high.max + low.min) / 2 over period |
| `supertrend` | `calc_supertrend` | L872 | `period: int = 10, multiplier: float = 3.0` | `{value, direction}` | period+1 | **multi-output** — KIS 코드는 direction(1/-1)만 반환. value=hl2+/-multiplier*atr 별도 계산 필요. **default period=10** (기존 카탈로그 14와 상이 — KIS 코드 우선) |
| `sar` | `calc_sar` | L896 | `af_start: float = 0.02, af_max: float = 0.2` | `{sar}` | 2 | 파라볼릭 SAR — af 누적 방식 |
| `regression_slope` | `calc_regression_slope` | L1469 | `period: int = 20` | `{slope}` | period | 선형 회귀 기울기 |

### TREND 중요 비고

- **`macd`**: KIS indicators.py는 `calc_macd`(L312), `calc_macd_signal`(L339), `calc_macd_histogram`(L365) 3개 별도 함수. strategy-engine Kotlin port 시 세 함수 결과를 하나의 IndicatorPoint.values `{macd, signal, histogram}`으로 합성.
- **`supertrend`**: KIS `calc_supertrend` L872 시그니처 = `period: int = 10` (기존 카탈로그 default `period=14`와 상이). KIS raw 코드 기준 `period=10, multiplier=3.0`. strategy-engine은 KIS 코드 우선 적용.
- **`wma`**: KIS 코드 주석 "Wilder 방식" — `ewm(alpha=1/period)`. 전통적 WMA(numpy 선형 가중)가 아님. strategy-engine 주의.

---

## MOMENTUM (모멘텀) — 20개

| id | KIS 함수명 | 줄번호 | 입력 파라미터 | 출력 keys | 최소 캔들 | 비고 |
|----|-----------|--------|--------------|-----------|-----------|------|
| `rsi` | `calc_rsi` | L282 | `period: int = 14` | `{rsi}` | period+1 | ewm Wilder smoothing (adjust=False) |
| `stochastic_k` | `calc_stochastic_k` | L490 | `period: int = 14` | `{k}` | period | (close - lowest_low) / (highest_high - lowest_low) * 100 |
| `stochastic_d` | `calc_stochastic_d` | L512 | `k_period: int = 14, d_period: int = 3` | `{k, d}` | k_period+d_period | **multi-output** — %K + %D 합성. KIS 코드 L512는 %D만 반환 → Kotlin port 시 calc_stochastic_k도 호출 |
| `stochrsi` | `calc_stochrsi` | L792 | `rsi_period: int = 14, stoch_period: int = 14` | `{stochrsi}` | rsi_period+stoch_period | RSI의 Stochastic (%K만) |
| `cci` | `calc_cci` | L561 | `period: int = 20` | `{cci}` | period | Typical Price 기반, 상수 0.015 |
| `williams_r` | `calc_williams_r` | L592 | `period: int = 14` | `{wr}` | period | 범위 -100~0 |
| `roc` | `calc_roc` | L763 | `period: int = 10` | `{roc}` | period+1 | pct_change(period) * 100 |
| `momentum` | `calc_momentum` | L785 | `period: int = 10` | `{momentum}` | period+1 | close.diff(period) |
| `apo` | `calc_apo` | L1124 | `fast: int = 12, slow: int = 26` | `{apo}` | slow | EMA(fast) - EMA(slow) |
| `ppo` | `calc_ppo` | L1133 | `fast: int = 12, slow: int = 26` | `{ppo}` | slow | (EMA(fast) - EMA(slow)) / EMA(slow) * 100 |
| `cmo` | `calc_cmo` | L1142 | `period: int = 14` | `{cmo}` | period | (gain - loss) / (gain + loss) * 100 |
| `ao` | `calc_ao` | L1152 | (없음) | `{ao}` | 34 | HL2 SMA(5) - HL2 SMA(34) 고정 기간 |
| `trix` | `calc_trix` | L1184 | `period: int = 15` | `{trix}` | period | 삼중 EMA pct_change * 100 |
| `tsi` | `calc_tsi` | L1194 | `long: int = 25, short: int = 13` | `{tsi}` | long | 이중 EMA smoothing |
| `rvi` | `calc_rvi` | L1204 | `period: int = 10` | `{rvi}` | period+3 | CO/HL 대칭 가중합 (open 컬럼 필요) |
| `dpo` | `calc_dpo` | L1215 | `period: int = 20` | `{dpo}` | period | close - SMA.shift(period//2+1) |
| `kst` | `calc_kst` | L1283 | (없음) | `{kst}` | 30 | ROC(10/15/20/30) 가중합 — 고정 파라미터 |
| `coppock` | `calc_coppock` | L1297 | (없음) | `{coppock}` | 14 | ROC(14) + ROC(11) → EWM(10) 고정 |
| `schaff` | `calc_schaff` | L1317 | `period: int = 23, fast: int = 10, slow: int = 50` | `{schaff}` | slow | STC — MACD의 이중 Stochastic |
| `fisher` | `calc_fisher` | L1333 | `period: int = 9` | `{fisher}` | period | Fisher Transform HL2 기반 |

### MOMENTUM 중요 비고

- **`stochastic_d`**: KIS `calc_stochastic_d` L512는 %D Series만 반환. spec §5-2 `{k, d}` multi-output 위해 strategy-engine이 `calc_stochastic_k` 결과도 합성 필요.
- **`ao`**, **`kst`**, **`coppock`**: 파라미터 없음 (고정 기간). IndicatorSpec.params 빈 Map 허용.
- **`rvi`**: open 컬럼 필요 (L1208 `df["close"] - df["open"]`). Candle 엔티티에 open 컬럼 존재 확인 필요.

---

## VOLUME (거래량) — 12개

| id | KIS 함수명 | 줄번호 | 입력 파라미터 | 출력 keys | 최소 캔들 | 비고 |
|----|-----------|--------|--------------|-----------|-----------|------|
| `obv` | `calc_obv` | L616 | (없음) | `{obv}` | 2 | 루프 기반 누적합 — Kotlin port 시 fold/scan 방식 변환 |
| `volume_ma` | `calc_volume_ma` | L643 | `period: int = 20` | `{volume_ma}` | period | volume 컬럼 rolling mean |
| `vwap` | `calc_vwap` | L660 | (없음) | `{vwap}` | 1 | cumsum(close*volume) / cumsum(volume) — 세션 리셋 없음 |
| `mfi` | `calc_mfi` | L680 | `period: int = 14` | `{mfi}` | period | Typical Price 기반 Money Flow Index |
| `cmf` | `calc_cmf` | L974 | `period: int = 20` | `{cmf}` | period | MFM * volume rolling sum / volume rolling sum |
| `cho` | `calc_cho` | L1160 | `fast: int = 3, slow: int = 10` | `{cho}` | slow | ADL EWM(fast) - ADL EWM(slow) |
| `ad` | `calc_ad` | L1350 | (없음) | `{ad}` | 1 | 단일 바 AD = MFM * volume (cumsum 아님) |
| `adl` | `calc_adl` | L1359 | (없음) | `{adl}` | 1 | AD의 cumsum (누적 A/D Line) |
| `force` | `calc_force` | L1367 | `period: int = 13` | `{force}` | period | close.diff() * volume EWM |
| `vwma` | `calc_vwma` | L1375 | `period: int = 20` | `{vwma}` | period | close*volume rolling / volume rolling |
| `eom` | `calc_eom` | L1383 | `period: int = 14` | `{eom}` | period | Distance Moved / Box Ratio rolling mean |
| `kvo` | `calc_kvo` | L1223 | `fast: int = 34, slow: int = 55` | `{kvo}` | slow | Klinger Volume Oscillator — trend 방향 루프 |

### VOLUME 중요 비고

- **`ad` vs `adl`**: `calc_ad` L1350은 단일 바 기여값, `calc_adl` L1359는 cumsum. 별개 지표.
- **`vwap`**: KIS 구현은 전체 기간 누적 VWAP (일별 리셋 없음). 분봉 일별 리셋 필요 시 strategy-engine 추가 처리.
- **`kvo`**: 루프 기반 (L1230-1233) — Kotlin port 시 scan/fold 방식 변환.

---

## VOLATILITY (변동성) — 12개

| id | KIS 함수명 | 줄번호 | 입력 파라미터 | 출력 keys | 최소 캔들 | 비고 |
|----|-----------|--------|--------------|-----------|-----------|------|
| `bb` | `calc_bb_upper` + `calc_bb_middle` + `calc_bb_lower` | L406 + L392 + L427 | `period: int = 20, std_dev: float = 2.0` | `{upper, middle, lower}` | period | **multi-output** — KIS 3 별도 함수. Kotlin port: 합성 |
| `bb_width` | `calc_bb_width` | L448 | `period: int = 20, std_dev: float = 2.0` | `{width}` | period | upper - lower |
| `bb_percent` | `calc_bb_percent` | L469 | `period: int = 20, std_dev: float = 2.0` | `{percent}` | period | (close - lower) / (upper - lower) |
| `atr` | `calc_atr` | L532 | `period: int = 14` | `{atr}` | period+1 | True Range EWM (adjust=False) |
| `natr` | `calc_natr` | L828 | `period: int = 14` | `{natr}` | period+1 | (ATR / close) * 100 |
| `keltner_upper` | `calc_keltner_upper` | L836 | `ema_period: int = 20, atr_period: int = 10, multiplier: float = 2.0` | `{upper}` | max(ema_period, atr_period+1) | **주의**: KIS 코드 atr_period default = **10** (기존 카탈로그 14와 상이 — KIS 코드 우선) |
| `keltner_lower` | `calc_keltner_lower` | L847 | `ema_period: int = 20, atr_period: int = 10, multiplier: float = 2.0` | `{lower}` | max(ema_period, atr_period+1) | **주의**: 동일 — atr_period=10 |
| `donchian_upper` | `calc_donchian_upper` | L858 | `period: int = 20` | `{upper}` | period | N일 최고가 rolling max |
| `donchian_lower` | `calc_donchian_lower` | L865 | `period: int = 20` | `{lower}` | period | N일 최저가 rolling min |
| `chop` | `calc_chop` | L1270 | `period: int = 14` | `{chop}` | period | ATR(1) sum / high-low range (log10 비율) |
| `mass_index` | `calc_mass_index` | L1306 | `period: int = 25` | `{mass}` | period | EMA9(HL) / EMA9(EMA9(HL)) ratio rolling sum |
| `augen` | `calc_augen` | L1501 | `period: int = 14` | `{augen}` | period | log_return / log_return.std rolling |

### VOLATILITY 중요 비고

- **`bb`**: KIS indicators.py는 `calc_bb_upper`(L406), `calc_bb_middle`(L392), `calc_bb_lower`(L427) 3개 별도 함수. strategy-engine Kotlin port 시 합성 필요. `calc_bb_middle`은 `calc_ma` 호출 위임.
- **`keltner_upper/lower`**: 기존 indicator-catalog.md의 `atr_period=14` vs KIS 코드 L836-837 실제값 `atr_period=10`. **KIS 코드 raw fetch 기준 10**이 정합 — strategy-engine IndicatorSpec default params에 반영.
- **`atr`**: ewm(adjust=False) Wilder smoothing. TA-Lib rolling mean 방식과 초기값 수렴 다름.

---

## OSCILLATOR (오실레이터) — 8개

| id | KIS 함수명 | 줄번호 | 입력 파라미터 | 출력 keys | 최소 캔들 | 비고 |
|----|-----------|--------|--------------|-----------|-----------|------|
| `adx` | `calc_adx` | L720 | `period: int = 14` | `{adx}` | period+1 | +DI/-DI → DX → EWM smoothing |
| `adxr` | `calc_adxr` | L1240 | `period: int = 14` | `{adxr}` | period*2 | (ADX + ADX.shift(period)) / 2 |
| `aroon_up` | `calc_aroon_up` | L804 | `period: int = 25` | `{up}` | period+1 | 루프 기반 argmax |
| `aroon_down` | `calc_aroon_down` | L816 | `period: int = 25` | `{down}` | period+1 | 루프 기반 argmin |
| `vortex_plus` | `calc_vortex_plus` | L1248 | `period: int = 14` | `{plus}` | period+1 | VM+ rolling / TR rolling |
| `vortex_minus` | `calc_vortex_minus` | L1259 | `period: int = 14` | `{minus}` | period+1 | VM- rolling / TR rolling |
| `ultosc` | `calc_ultosc` | L1170 | `p1: int = 7, p2: int = 14, p3: int = 28` | `{uo}` | p3 | 3기간 가중합 100*(4*avg1+2*avg2+avg3)/7 |
| `bop` | `calc_bop` | L1462 | (없음) | `{bop}` | 1 | (close - open) / (high - low) — open 컬럼 필요 |

### OSCILLATOR 중요 비고

- **`aroon_up/down`**: Python 루프 구현 (L804-L825). Kotlin port 시 windowed + maxByIndex 방식 권장.
- **`adxr`**: 최소 캔들 = 약 period*2+1 (ADX 선행 계산 후 shift(period) 필요).
- **`bop`**: open 컬럼 필요 (L1466). Candle 엔티티 open 필드 사용.

---

## OTHER (기타) — 8개

| id | KIS 함수명 | 줄번호 | 입력 파라미터 | 출력 keys | 최소 캔들 | 비고 |
|----|-----------|--------|--------------|-----------|-----------|------|
| `std` | `calc_std` | L33 | `period: int` (default 없음) | `{std}` | period | period 필수 파라미터 — IndicatorSpec default period=20 권장 |
| `variance` | `calc_variance` | L1395 | `period: int = 20` | `{variance}` | period | rolling var (pandas ddof=1 default) |
| `disparity` | `calc_disparity` | L68 | `period: int = 20` | `{disparity}` | period | (close / MA) * 100 |
| `midpoint` | `calc_midpoint` | L1441 | `period: int = 14` | `{midpoint}` | period | (close.rolling.max + close.rolling.min) / 2 |
| `midprice` | `calc_midprice` | L1448 | `period: int = 14` | `{midprice}` | period | (high.rolling.max + low.rolling.min) / 2 |
| `logr` | `calc_logr` | L1455 | (없음) | `{logr}` | 2 | log(close / close.shift(1)) — np.log |
| `pivot` | `calc_pivot` | L1494 | (없음) | `{p, r1, r2, r3, s1, s2, s3}` | 1 | **multi-output** — KIS 코드는 P=(H+L+C)/3만 반환. r1~s3는 전일 OHLC 기반 strategy-engine 추가 구현 필요 |
| `regression_intercept` | `calc_regression_intercept` | L1481 | `period: int = 20` | `{intercept}` | period | 선형 회귀 절편 |

### OTHER 중요 비고

- **`pivot`**: KIS `calc_pivot` L1494는 단순 `(high + low + close) / 3` — P값만. spec §5-2의 `{p, r1, r2, r3, s1, s2, s3}` 7-key multi-output 위해 strategy-engine이 전일 OHLC 기반 추가 계산 의무. 표준 공식: `r1=2P-L, s1=2P-H, r2=P+(H-L), s2=P-(H-L), r3=H+2(P-L), s3=L-2(H-P)`.
- **`std`**: `calc_std` L33은 `period: int` (default 없음 — 필수 파라미터). IndicatorCatalog의 defaultParams에 `period=20` 명시 필요.
- **`logr`**: 파라미터 없음. IndicatorSpec.params 빈 Map 허용.

---

## multi-output 지표 5개 요약 (spec §5-2 LOCK 정합)

| id | KIS 구현 방식 | 출력 keys | strategy-engine 처리 의무 |
|----|--------------|-----------|--------------------------|
| `macd` | 3개 별도 함수 (L312 + L339 + L365) | `{macd, signal, histogram}` | 3 함수 호출 후 시계열 합성 |
| `bb` | 3개 별도 함수 (L392 + L406 + L427) | `{upper, middle, lower}` | 3 함수 호출 후 합성 |
| `stochastic_d` | calc_stochastic_k(L490) + calc_stochastic_d(L512) | `{k, d}` | 양쪽 호출 후 합성 |
| `supertrend` | calc_supertrend(L872) — direction만 반환 | `{value, direction}` | direction + value(hl2±multiplier*atr) 별도 계산 |
| `pivot` | calc_pivot(L1494) — P만 반환 | `{p, r1, r2, r3, s1, s2, s3}` | r1~s3 추가 계산 (전일 OHLC 기반 표준 공식) |

---

## KIS 코드 vs 기존 카탈로그 파라미터 불일치 목록

> strategy-engine이 KisStrategyBuilderSource 구현 시 아래 항목은 **KIS raw 코드 기준** 적용 의무 (KIS-first 정책 정합)

| id | 기존 카탈로그 default | KIS 코드 실제 default (raw fetch 정합) | 비고 |
|----|----------------------|---------------------------------------|------|
| `supertrend` | period=14 | period=10 | KIS L872 시그니처 직접 확인 |
| `keltner_upper` | atr_period=14 | atr_period=10 | KIS L836-837 시그니처 직접 확인 |
| `keltner_lower` | atr_period=14 | atr_period=10 | KIS L847-848 시그니처 직접 확인 |
| `std` | period=20 | period 필수 (default 없음) | L33 시그니처 직접 확인 |
| `wma` | 전통 선형가중 WMA | Wilder ewm(alpha=1/period) | L1074-1078 알고리즘 상이 주의 |

---

## Native 구현 지표 5종 (priority=50 — KIS보다 우선 라우팅)

> PR-33b backend-core 담당. 아래 5종은 NativeIndicatorSource가 우선 사용됨 → REST 응답 `"source":"native"`.

| id | NativeIndicatorSource 구현 파일 | KIS cross-check 기준 |
|----|--------------------------------|---------------------|
| `ma` | NativeMa.kt | KIS calc_ma L15-L30 동일 입력 비교 (rolling mean) |
| `ema` | NativeEma.kt | KIS calc_ema L264-L279 (ewm span, adjust=False) |
| `rsi` | NativeRsi.kt | KIS calc_rsi L282-L309 (ewm Wilder 방식 통일) |
| `macd` | NativeMacd.kt | KIS calc_macd L312-L336 + signal L339-L362 + histogram L365-L389 합성 |
| `bb` | NativeBollingerBands.kt | KIS calc_bb_{upper,middle,lower} (L392+L406+L427) 합성 |

drift 허용: 0.001% 미만 (PR-34 R3 정확성 fixture에서 검증).

---

## 합계: 20+20+12+12+8+8 = 80개

---

## 변경 이력

| 날짜 | 변경 | 담당 |
|------|------|------|
| 2026-05-09 | v1 — 최초 카탈로그 작성 (기본 분류·id·name·default params). 출처 koreainvestment/open-trading-api indicators.py | market-data |
| 2026-05-09 | **v2 — KIS-first raw fetch 정합 갱신** (PR-33 T-IND 준비): (1) 각 지표 KIS 함수명·줄번호·입력 파라미터 raw 인용 추가, (2) multi-output 5종 KIS 구현 방식 명시 (macd 3분리·bb 3분리·supertrend direction-only·pivot P-only·stochastic_d 합성 필요), (3) 파라미터 불일치 목록 명시 (supertrend period=10, keltner atr_period=10, std default 없음), (4) Native 5종 cross-check 기준 함수명+줄번호 추가. raw 출처: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/strategy_builder/core/indicators.py (1507 lines, fetch 2026-05-09) | market-data |
