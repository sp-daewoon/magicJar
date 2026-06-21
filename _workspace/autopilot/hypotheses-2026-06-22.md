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
