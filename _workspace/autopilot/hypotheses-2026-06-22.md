# magicjar-autopilot — Phase 1 첫 전략 세트 가설 레지스트리 (2026-06-22 월)

> **권위**: spec `_workspace/specs/2026-06-22-autopilot-self-augmentation.md` §학습 조건 A~F.
> **발행 주체**: strategy-engine (agent/strategy/autopilot-strategies).
> **상태**: 6전략 전부 `owner=AUTOPILOT` · `status=PAUSED` 로 시드(`scripts/autopilot-seed-strategies.mjs`).
> 오케스트라가 개장 전 harness 스모크(칭 위반 거부·kill·롤백 실증) 통과 후에만 ACTIVE 전환.
> **DSL 검증**: 6 entry + 6 exit 전부 `AutopilotSeedStrategyDslTest`(strategy-engine) GREEN — 파싱 + 정적검증 + **엔진 매핑(라이브 평가 가능)** 통과.

## 공통 전제 (학습 조건 — 협상 대상 아님)

- **A. 가설 + 반증 조건**: 각 전략에 "이 가설은 무엇이 참이면 먹히고, 무엇이 관측되면 틀렸다"를 명시(아래 표).
- **B. 결과 라벨링**: 진입/청산 근거가 결정론 DSL 1줄 → 매매일지(T-J `trading_journal`)에 trigger reason 그대로 기록되어 사후 라벨링 가능.
- **C. 비용 차감 net**: KIS REAL 왕복비용 ≈ 매수수수료 0.014% + 매도수수료 0.014% + 매도세(거래세) ≈ 0.18% + 슬리피지 여유 ≈ **왕복 0.25~0.4%**. 모든 TP ≥ 2.0% 로 비용을 충분히 초과(net ≥ ~1.6%).
- **D. 레짐 태깅**: 각 전략이 추세장/횡보장/변동성확대 중 어디서 먹히는 가설인지 명시(아래 표 "레짐").
- **E. 과적합 회피**: 지표 2~4개·정수 파라미터·표준값(ma 20/50, rsi 14, bb 20, donchian 20)만 사용. 곡선맞춤 0.
- **F. 결정론**: 난수 0. 동일 캔들 입력 → 동일 진입/청산. (백테스트 재현성 LOCK.)

## 하드 칭 상호작용 (코드 강제 — 이미 배포)

- 6전략이 신호를 쏴도 **동시 보유는 Guard 가 최대 3종목으로 캡**(`MAGICJAR_AUTOPILOT_MAX_POSITIONS=3`).
- 풀=순자산 5% / 건당=풀 20% / 일손실 풀 30% halt / 누적 풀 50% → kill switch.
- 따라서 6전략은 "슬롯 3개를 두고 경합하는 다양한 진입 가설 풀"이다. 어느 메커니즘이 먼저·자주·net+ 로 먹히는지가 첫날 학습 신호.

## 6 전략 (서로 다른 메커니즘 — 중복 0)

| # | 이름(name) | tf | 가설 (먹히는 이유) | 진입 논리(dsl_entry) | 청산 논리(dsl_exit) | TP% | SL% | **반증 조건 (틀렸다고 증명될 것)** | 레짐 |
|---|-----------|----|----|----|----|----|----|----|----|
| 1 | `autopilot-momentum-breakout` | 3m | 직전 N봉 고가를 거래량 동반해 돌파하면 단기 추세가 이어진다(돌파 추종) | `((close breaks donchian_upper(20)) AND (volume > volume_ma(20) * 1.5)) AND (close > vwap())` | `close < vwap()` | 2.5 | 1.5 | 돌파 직후 즉시 VWAP 아래로 되말림(가짜 돌파 fakeout)이 승률 다수 → net 음수면 가설 기각 | 추세장·변동성확대 |
| 2 | `autopilot-pullback-reclaim` | 5m | 상승추세(ma20>ma50, close>ma50) 중 단기 눌림(RSI 저점) 후 ma20 재탈환은 추세 재개 진입점이다 | `(((close > ma(50)) AND (ma(20) > ma(50))) AND (rsi(14) > 40)) AND (close > ma(20))` | `close < ma(20)` | 2.0 | 1.5 | ma20 재탈환 후에도 추세가 죽어 ma20 재이탈로 SL 다발 → 눌림이 추세재개가 아니라 추세종료 신호면 기각 | 추세장(상승) |
| 3 | `autopilot-fvg-bullish-reclaim` | 5m | 상승 FVG(미체결 갭) 중간선 위 재진입은 매수세 우위 회복을 뜻한다(스마트머니 gap reclaim) | `(fvg_bullish_active(50) == 1) AND (close > fvg_bullish_mid(50))` | `close < fvg_bullish_bottom(50)` | 2.5 | 2.0 | gap mid 재진입이 지지로 작동 안 하고 bottom 이탈 다발 → FVG가 단타 지지 레벨로 무의미하면 기각 | 추세장·되돌림 |
| 4 | `autopilot-orderblock-bullish-retest` | 5m | 강세 오더블록(기관 매집 구간) 리테스트는 반등 진입점이다 | `(ob_bullish_active(20, 20, 50) == 1) AND (close > ob_bullish_mid(20, 20, 50))` | `close < ob_bullish_bottom(20, 20, 50)` | 2.5 | 2.0 | OB mid 위 재진입이 지지 안 되고 block bottom 이탈 다발 → OB가 단타 지지로 무의미하면 기각 | 횡보장·되돌림 |
| 5 | `autopilot-volume-spike` | 3m | 거래량 2배 폭증 + 자금유입(MFI) + VWAP 위는 단기 수급 쏠림 초입이다(RVOL spike) | `((volume > volume_ma(20) * 2) AND (mfi(14) > 55)) AND (close > vwap())` | `close < vwap()` | 3.0 | 2.0 | spike 직후 바로 소진(VWAP 하향)되어 고점 매수가 되는 경우 다수 → spike가 진입이 아니라 청산 신호면 기각 | 변동성확대·뉴스성 |
| 6 | `autopilot-vwap-reversion` | 5m | BB 하단 이탈 + RSI 과매도 + VWAP 아래는 과매도 되돌림(평균회귀) 매수 기회다 | `((close < bb_lower(20)) AND (rsi(14) < 30)) AND (close < vwap())` | `close > vwap()` | 2.0 | 2.0 | 과매도에서 추가 하락이 이어져(추세 하락) 평균회귀 실패·SL 다발 → 횡보 아닌 추세하락장에서 칼 받기면 기각 | 횡보장(역추세) |

### 메커니즘 다양성 매트릭스 (중복 0 증명)

| 메커니즘 축 | 1 모멘텀 | 2 눌림목 | 3 FVG | 4 OB | 5 spike | 6 회귀 |
|---|---|---|---|---|---|---|
| 방향 성격 | 추세추종 | 추세추종 | 추세추종 | 되돌림 | 추세추종 | **역추세** |
| 1차 신호원 | 가격 돌파 | 이평 구조 | smart-money gap | smart-money OB | **거래량** | **변동성 밴드** |
| 핵심 지표 | donchian+volume+vwap | ma+rsi | fvg | order_block | volume+mfi+vwap | bb+rsi+vwap |
| 청산 트리거 | VWAP | ma20 | FVG bottom | OB bottom | VWAP | VWAP(목표) |
| 먹히는 레짐 | 추세·변동성↑ | 상승추세 | 추세·되돌림 | 횡보·되돌림 | 변동성↑ | 횡보 |

→ 6전략이 **추세추종 4 + 되돌림 1 + 역추세 1** 로 레짐 분산. 신호원도 가격·이평·gap·OB·거래량·변동성으로 분산 → 어떤 시장 상태에서 무엇이 먹히는지 직교 학습.

## universe (종목 선정 방식)

`scripts/autopilot-seed-strategies.mjs` 가 codes/intervals 를 다음 결정론 규칙으로 채운다(자본 안전 + 학습 신호 균형):

1. **유동성 앵커 (필수)** — KOSPI 대형주 고유동성 8종 고정: 삼성전자 005930, SK하이닉스 000660, NAVER 035420, 현대차 005380, 기아 000270, KB금융 105560, 카카오 035720, LG화학 051910. (실돈 단타에서 체결·슬리피지 안전 — 깊은 호가.)
2. **발굴 후보 (학습 신호)** — `discovery_scorecard` 최신 snapshot_date 의 preset 별 TOP 3 코드 union 을 추가(중복 제거). 단타 발굴 레이어(forward-tested)가 "오늘 뭐가 움직이나"를 공급. snapshot 부재(휴장 직후 등)면 앵커만으로 가동(빈 universe 방지).
3. **상한** — universe 총 코드수는 안전상 상한(default 24)으로 cap. 앵커 우선 채움 후 발굴 후보로 채움.

→ **유동성 깊은 대형주(체결 안전) + 발굴 후보(학습 신호)** 의 합집합. 첫날은 앵커 비중이 슬리피지 리스크를 낮추고, 발굴 후보가 "단타가 먹히는 종목"을 탐색한다.

## 첫날 학습 가설 (메타)

- **Q1**: 추세추종(1·2·3·4·5) vs 역추세(6) 중 첫날 시장 레짐에서 무엇이 net+ 인가?
- **Q2**: smart-money(3 FVG·4 OB) gap/block 지지가 단타 3~5m 타임프레임에서 실제 작동하는가, 아니면 일봉 개념이라 단타엔 노이즈인가?
- **Q3**: 거래량 신호(5 spike)가 진입 alpha 인가 청산 신호(고점 매수)인가?
- **Q4**: Guard 3-슬롯 경합에서 어느 메커니즘이 먼저/자주 슬롯을 차지하고 그 성과는?

→ 답은 매매일지(T-J) + autopilot 저널(`_workspace/autopilot/journal/`) 에 사이클 단위로 누적, 다음 사이클 튜닝/강등의 입력.

## ⚠ 발행 시 발견 — 오케스트라 인계 (ACTIVE 전환 전 해소 의무)

**consumer 실시간 매매 평가 hot path 가 `owner = 'USER'` 만 조회한다.**
`ConsumerStrategyJpaRepository.findActiveByCodeAndInterval`·`findActiveWithExplicitCodes`(BUY 평가)·
`findDisabledWithPositionByCodeAndInterval`(SELL 평가)가 전부 `WHERE owner = 'USER'` 필터.

→ **owner=AUTOPILOT 전략은 status=ACTIVE 로 바꿔도 consumer 가 평가하지 않아 매수 신호가 0** 이다(현행).
이는 Phase 1 wiring 갭(strategy-engine 시드 deliverable 범위 밖)이며, 다음 중 하나로 해소해야 autopilot 루프가 실제로 매매한다:

- (권장) 위 3쿼리의 owner 필터를 `owner IN ('USER','AUTOPILOT')` 로 확장(add-only) — 단, AUTOPILOT 매수 intent 는 반드시 `AutonomousTradingGuard.evaluate` 통과 의무(spec §2-0-A·Task3 이미 wiring). owner 분기로 Guard 적용/풀 분리는 유지.
- 또는 Phase 1 전용 AUTOPILOT 평가 경로 별도 추가.

**이 갭이 해소되기 전엔 ACTIVE 전환해도 무해(무신호)하나, 매매가 실제로 일어나지 않으므로 학습 신호 0** 이다. 오케스트라가 Phase 1 R 단계에서 backend-core/consumer 에 위 wiring 을 디스패치할 것을 권고.

## 🔬 첫 거래일 휴면 진단 (2026-06-22 월 · strategy-engine)

> **트리거**: 첫날 owner=AUTOPILOT 6전략 중 발화 3(100 momentum·104 volume·105 vwap), 휴면 인식 3(**101 pullback·102 fvg·103 orderblock**).
> **방법**: 라이브 5m/3m 캔들 500봉 replay(`SmartMoneyIndicators.kt` FVG/OB 로직 충실 재현) + `signal`·`risk_event`·`order_request` DB 실증. 추측 0.

### 결론: 세 전략의 DSL entry 는 **건강하다**. 휴면은 DSL이 아니라 **신호 발생 시점 지연 + risk gateway 슬롯/풀 경합**이다.

| 전략 | entry 발화(today true 봉) | signal BUY | order_request | risk_event 차단 | 1차 원인 | 조치 |
|------|--------------------------|-----------|--------------|----------------|---------|------|
| **101** pullback | 거의 0 (005930 17·**035720 0**·105560 1) | 1 | 0 | BLOCKED | 약세장(anchor 7/8 하락 −0.7~−2.4%) → `ma20>ma50 & close>ma50` 상승추세 정당 미충족 | **보존**(정당 휴면) |
| **102** fvg | 빈번 (005930 9·**000660 36**) | 10 | 0 | MAX_CONCURRENT_POSITIONS 10 BLOCKED | 첫 신호 13:50 → 슬롯3 이미 선점 | **보존**(DSL 정상) |
| **103** ob | 빈번 (005930 32·**000660 54**) | 14 | 0 | POSITION_LIMIT/MAX_CONCURRENT BLOCKED | 첫 신호 13:40 → 슬롯3 이미 선점 | **보존**(DSL 정상) |

### 진단 근거 (실데이터)
1. **entry 충족 빈도**(라이브 5m replay): 102·103은 entry 조건이 **매우 자주 true**(000660 fvg 36봉·ob 54봉/today 54봉). "조건이 너무 엄격"·"지표 미작동"·"논리적 불가능" 셋 다 **반증**됨.
2. **risk_event**: 101·102·103 BUY 신호 **전량 BLOCKED**(MAX_CONCURRENT_POSITIONS 10 + POSITION_LIMIT 15). `order_request` 0건 = 실제 매수 0 = 인식상 "휴면". 차단 지점은 entry 평가가 아니라 **하드 칭(슬롯3 cap + 풀 한도)**.
3. **슬롯 선점 타임라인**: 발화군 100·104·105가 **11:57~13:00 에 FILLED BUY 로 슬롯3 선점**. 102·103 의 첫 신호는 그 후(13:40/13:50)라 도착 시 슬롯 0 → 전량 차단. → "다양성 손실"의 직접 메커니즘은 **시점 경합**이다.
4. **파라미터 수정 반려**(replay): ob `swingLookback` 20→10 단축해도 105560 첫 발화 13:40 **불변**(원인은 warmup 아닌 BOS=약세장 늦은 상승돌파 시점). 005930 은 sw10/se5 에서 0건으로 붕괴 → 과적합 위험만 증가. **무효 + 위험 → 반려**.
5. **DSL 임계 완화 반려**: entry 가 이미 충분히 발화하므로 완화 근거 0. 약세장에서 추세 필터(101)·gap/OB 지지 필터(102·103)를 풀면 칼받기 매수만 양산 → **억지 발화 회피 원칙**으로 반려.

### 구조적 근본(strategy-engine 영역 밖 — 인계)
- 장중 intraday 캔들 backfill 이 **10:25 에야 시작**(09:00 개장 후 ~1.5h 지연) + 5m/3m 캔들 **6/13~6/19 갭**. → 오전 내내 fvg/ob zone 이 오늘 가격대에서 미형성 → 102·103 신호가 오후로 밀림 → 슬롯 경합 패배. **market-data**(intraday backfill 조기 가동·갭 보정)에 인계.
- 슬롯3 선착순(`MAX_CONCURRENT_POSITIONS=3`) 정책이 "먼저 신호 낸 메커니즘"에 유리. 다양성 학습을 원하면 **오케스트라/backend-core** 가 ① 슬롯 라운드로빈/메커니즘 다양성 보너스 ② autopilot 평가를 paper(슬롯 무제한) 병행으로 학습 신호 분리 ③ 슬롯 cap 상향 중 택일 검토. (단, 실돈 안전 cap 은 보수 유지 권고.)

#### ✅ 해소 — 개선 G(슬롯 공정성 paper-parallel, 2026-06-22 strategy-engine)
위 ② 채택. starve 된 owner=AUTOPILOT BUY(slot/pool BLOCKED)를 **실 자본 0** 으로 `autopilot_paper_shadow`(V93)에
가상 진입 1행 기록 → 후속 캔들 TP/SL 도달을 60초 워커가 판정(WIN/LOSS) + net 가상 PnL 누적. 102·103 같이 슬롯에
막힌 가설도 **점수가 쌓여 학습 신호**가 된다(전략별 `scoreByStrategy` rollup). **실보유 동시 cap 3 불변**(섀도는 자본 0).
순수 스코어러(`AutopilotPaperShadowScorer`)는 SL 우선 intrabar 판정·비용차감(0.3%)으로 과대평가를 막는다. 격리 LOCK:
enabled + BUY + owner=AUTOPILOT + slot/pool starve 일 때만 기록(non-AUTOPILOT·SELL·kill·dedup no-op·zero-regression).
구조적 근본 중 **데이터 지연(intraday backfill)** 은 여전히 market-data 인계(섀도는 오후 도착 신호도 스코어링하나, 오전
zone 미형성 자체는 데이터 토대 문제). 슬롯 라운드로빈(①)은 실보유 경합 자체를 건드려 더 침습적이라 보류 — 페이퍼
스코어링으로 "어느 가설이 먹히나"는 cap 변경 없이 학습되므로 interim 으로 충분.

### 메타 답(첫날)
- **Q1**(추세 vs 역추세): 약세장이라 추세추종(101 상승추세)은 정당 휴면, 발화한 추세추종(100 momentum)도 SELL 신호가 BUY 대비 폭증(812:16) = 약세에서 fakeout 다발 시사. 역추세(105 vwap-reversion)는 BUY 25 로 가장 활발 = 약세 되돌림 매수가 첫날 레짐에 적합.
- **Q4**(슬롯 경합): 3m 전략(100·104)이 5m 전략(102·103)보다 boundary 빈도 2배 + 더 일찍 신호 → 슬롯 선점. **타임프레임이 슬롯 경합의 숨은 결정변수**임이 첫날 학습 신호로 드러남.

→ **이 세 전략은 코드/DSL/파라미터 변경 없이 보존한다.** 적절한 레짐(101=상승추세일·102/103=오전 데이터 충분 + 슬롯 가용)에서 발화함이 실증됐다. 다양성 손실은 정당(약세장 추세 필터)이거나 구조적(데이터 지연·슬롯 선점)이며 strategy-engine 의 DSL 책임 밖이다.
