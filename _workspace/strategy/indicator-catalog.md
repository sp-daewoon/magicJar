# KIS Strategy Builder 80지표 카탈로그

출처: `koreainvestment/open-trading-api` strategy_builder/core/indicators.py (97 함수, 보조 유틸 제외 80개)

## TREND (추세) — 20개

| id | name | default params |
|---|---|---|
| ma | 이동평균(MA) | period=20 |
| ema | 지수이동평균(EMA) | period=20 |
| wma | 가중이동평균(WMA) | period=20 |
| hma | 헐이동평균(HMA) | period=20 |
| dema | 이중지수이동평균(DEMA) | period=20 |
| tema | 삼중지수이동평균(TEMA) | period=20 |
| trima | 삼각이동평균(TRIMA) | period=20 |
| lwma | 선형가중이동평균(LWMA) | period=20 |
| t3 | T3 이동평균 | period=20, volume_factor=0.7 |
| zlema | 지연제거지수이동평균(ZLEMA) | period=20 |
| kama | 카우프만적응이동평균(KAMA) | period=20 |
| alma | 아르노-레그루 이동평균(ALMA) | period=20, sigma=6.0, offset=0.85 |
| frama | 프랙탈적응이동평균(FRAMA) | period=20 |
| vidya | 변동성 인덱스 이동평균(VIDYA) | period=20 |
| macd | MACD | fast=12, slow=26, signal=9 |
| ichimoku_tenkan | 이치모쿠 전환선 | period=9 |
| ichimoku_kijun | 이치모쿠 기준선 | period=26 |
| supertrend | 슈퍼트렌드 | period=14, multiplier=3.0 |
| sar | 포물선형 SAR | af_start=0.02, af_max=0.2 |
| regression_slope | 선형회귀 기울기 | period=20 |

## MOMENTUM (모멘텀) — 20개

| id | name | default params |
|---|---|---|
| rsi | RSI | period=14 |
| stochastic_k | 스토캐스틱 %K | period=14 |
| stochastic_d | 스토캐스틱 %D | k_period=14, d_period=3 |
| stochrsi | 스토캐스틱 RSI | rsi_period=14, stoch_period=14 |
| cci | 상품채널지수(CCI) | period=20 |
| williams_r | 윌리엄스 %R | period=14 |
| roc | 변화율(ROC) | period=10 |
| momentum | 모멘텀 | period=10 |
| apo | 절대가격오실레이터(APO) | fast=12, slow=26 |
| ppo | 퍼센트가격오실레이터(PPO) | fast=12, slow=26 |
| cmo | 찬드 모멘텀 오실레이터(CMO) | period=14 |
| ao | 어썸 오실레이터(AO) | — |
| trix | TRIX | period=15 |
| tsi | 진정강도지수(TSI) | long=25, short=13 |
| rvi | 상대활력지수(RVI) | period=10 |
| dpo | 차이가격오실레이터(DPO) | period=20 |
| kst | Know Sure Thing(KST) | — |
| coppock | 코폭 커브 | — |
| schaff | 샤프 추세 사이클(STC) | period=23, fast=10, slow=50 |
| fisher | 피셔 변환 | period=9 |

## VOLUME (거래량) — 12개

| id | name | default params |
|---|---|---|
| obv | 에너지이동지표(OBV) | — |
| volume_ma | 거래량 이동평균 | period=20 |
| vwap | 거래량가중평균가격(VWAP) | — |
| mfi | 자금흐름지수(MFI) | period=14 |
| cmf | 차이킨 머니플로우(CMF) | period=20 |
| cho | 차이킨 오실레이터 | fast=3, slow=10 |
| ad | A/D 라인 | — |
| adl | 누적/분배 라인(ADL) | — |
| force | 힘 지수(Force Index) | period=13 |
| vwma | 거래량가중이동평균(VWMA) | period=20 |
| eom | 시장 이동 편의(EOM) | period=14 |
| kvo | 클링거 볼륨 오실레이터 | fast=34, slow=55 |

## VOLATILITY (변동성) — 12개

| id | name | default params |
|---|---|---|
| bb | 볼린저 밴드 | period=20, std_dev=2.0 |
| bb_width | 볼린저 밴드 폭 | period=20, std_dev=2.0 |
| bb_percent | 볼린저 밴드 %B | period=20, std_dev=2.0 |
| atr | 평균진실범위(ATR) | period=14 |
| natr | 정규화 ATR | period=14 |
| keltner_upper | 켈트너 채널 상단 | ema_period=20, atr_period=14, multiplier=2.0 |
| keltner_lower | 켈트너 채널 하단 | ema_period=20, atr_period=14, multiplier=2.0 |
| donchian_upper | 돈치안 채널 상단 | period=20 |
| donchian_lower | 돈치안 채널 하단 | period=20 |
| chop | 채핀 지수(Choppiness) | period=14 |
| mass_index | 질량 지수(Mass Index) | period=25 |
| augen | 오겐 변동성 | period=14 |

## OSCILLATOR (오실레이터) — 8개

| id | name | default params |
|---|---|---|
| adx | ADX | period=14 |
| adxr | ADXR | period=14 |
| aroon_up | 아룬 상단 | period=25 |
| aroon_down | 아룬 하단 | period=25 |
| vortex_plus | 소용돌이 +VI | period=14 |
| vortex_minus | 소용돌이 -VI | period=14 |
| ultosc | 최종 오실레이터(UO) | p1=7, p2=14, p3=28 |
| bop | 매수-매도 균형(BOP) | — |

## OTHER (기타) — 8개

| id | name | default params |
|---|---|---|
| std | 표준편차 | period=20 |
| variance | 분산 | period=20 |
| disparity | 이격도 | period=20 |
| midpoint | 중간점 | period=14 |
| midprice | 중간 가격 | period=14 |
| logr | 로그 수익률 | — |
| pivot | 피봇 포인트 | — |
| regression_intercept | 선형회귀 절편 | period=20 |

## 합계: 20+20+12+12+8+8 = 80개

## Native 구현 지표 (T5 R1, priority=50)

14개 Kotlin 직접 구현 (domain/NativeIndicatorCalculator):
`ma, ema, rsi, macd, bb, vwap, stochastic_k, stochastic_d, atr, cci, obv, disparity, adx, volume_ma`

나머지 66개: KIS Strategy Builder descriptor 등록 + R2에서 native 추가 구현 예정.
