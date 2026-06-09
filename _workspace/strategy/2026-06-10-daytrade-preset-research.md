# 빌트인 단타 발굴 프리셋 — 연구 방법론 + 후보 가설 설계

> **본 문서의 지위 (정직 LOCK)**: 이 문서는 **연구·설계 문서**다. 코드·시드·마이그레이션 변경 0건.
> 여기 적힌 모든 프리셋은 **가설(hypothesis)**이며, "확률이 높다 / 우위가 있다"는 주장은 **R3 ④검증(IS/OOS + 파라미터 민감도) 통과 전까지 금지**한다.
> 데이터 분포로 임계의 합리성만 근거화하고, 수익 우위 판정은 R3 백테스트 run의 몫이다.
> 작성자: strategy-engine · 일자: 2026-06-10 · task: T-20260610-0129

---

## §0. 핵심 철학 — 프리셋은 증명 전까지 가설이다

단타 발굴 프리셋을 "확률 높게" 만드는 일의 본질은 **좋은 식을 발견하는 것이 아니라, 과최적화된 가짜 우위를 거르는 것**이다.

1. **임계 합리성(분포 근거)** ≠ **수익 우위(백테스트 근거)**. 본 문서는 전자만 책임진다. 임계가 종목-일의 몇 %를 통과시키는지(빈도)·분포 어디에 위치하는지(p50/p90/p95)는 데이터로 확정할 수 있다. 그 식이 돈을 버는지는 R3에서만 판정한다.
2. **In-Sample 핏은 증거가 아니다.** 과거 전체에 식을 맞춰 좋은 수익이 나와도 그것은 곡선 맞추기(curve fitting)일 수 있다. 승격 관문은 **OOS(out-of-sample) 기대값 > 0 + IS→OOS 저하율 임계 이내 + 파라미터 plateau**다.
3. **희소성·과다성 양쪽 다 실패다.** 통과율 0.05%는 표본 부족(거래 100건 미달 → 검증 불가)이고, 통과율 70%는 필터 부재(시장 베타와 구분 불가)다. 본 문서는 각 가설의 실측 통과율을 명시한다.
4. **체결강도(tick) 조건은 일봉 백테스트로 재현 불가**(spec §1 LOCK). 본 문서의 모든 가설은 **일봉 표현 가능 식별자만** 사용한다. 체결강도 A/B는 post-R3 MOCK 페이퍼 트랙.

---

## §1. 추가 메커니즘 — V76 시드 절차 (재현 가능)

### 1-1. 권위 구조 (현행 LOCK)

- **프리셋 권위 = Flyway 시드 row** (최고 관리자 결정 2, 2026-06-09). `strategy_research_candidate` 테이블(V74 스키마)에 INSERT.
- 코드(`ResearchCandidateProvider`)는 **read만** 한다. 코드 하드코딩 프리셋(옵션 A) 폐기.
- 현행 V75 = `DAYTRADE_*` prefix 3종(sort_order 200·210·220). 다음 가용 = **V76**(`api/src/main/resources/db/migration/` ls 기준 최신 V75 확인 — 충돌 0).

### 1-2. V74 스키마 컬럼 계약 (V75 시드가 따른 형식 1:1)

| 컬럼 | 타입 | V76 단타 시드 값 |
|---|---|---|
| `candidate_key` | TEXT UNIQUE | `DAYTRADE_*` prefix (V75 3종 key + V74 18종 key와 UNIQUE 충돌 0) |
| `title` | TEXT | 한국어 setup 제목 |
| `market_fit` | TEXT | 단타 적합 시장 설명(사용자 가독) |
| `entry_rules` | JSONB | 사람이 읽는 조건 배열 |
| `entry_dsl` | TEXT | strategy-engine 평가 권위 DSL — **화이트리스트 식별자만** |
| `target_profit_pct` / `stop_loss_pct` / `max_hold_bars` | NUMERIC/INT | exit 파라미터 |
| `sort_order` | INT | V75 3종(200~220) 뒤 — **230부터** 부여(충돌 0) |
| `is_active` | BOOL | `TRUE` |
| `is_builtin` | BOOL | `TRUE`(시스템 소유 — userId 비귀속) |
| `created_at`/`updated_at` | — | DEFAULT NOW() 위임(INSERT 목록 생략 — V74:40·V75:10 패턴) |

### 1-3. V76 시드 골격 (R3 통과분만 채워 발행 — 지금은 템플릿)

```sql
-- V76: 단타 발굴 프리셋 증설 (R3 ④검증 통과 가설만 승격 — ADR 0117 §2-1 연장)
-- 권위 = 이 row. 코드 read만. ON CONFLICT DO NOTHING (V74:186·V75:45 선례).
INSERT INTO strategy_research_candidate
    (candidate_key, title, market_fit, entry_rules, entry_dsl,
     target_profit_pct, stop_loss_pct, max_hold_bars, sort_order, is_active, is_builtin)
VALUES
    (
        'DAYTRADE_<NAME>',
        '<한국어 제목>',
        '<시장 적합 설명>',
        '["<조건1>","<조건2>", ...]'::jsonb,
        '<entry_dsl — 화이트리스트 식별자만>',
        <target>, <stop>, <hold>, 230, TRUE, TRUE
    )
ON CONFLICT (candidate_key) DO NOTHING;
```

### 1-4. 발굴 엔진 노출 흐름 (현행, 검증됨)

`DaytradeDiscoverService.discover()` (읽기로 확인한 실제 흐름):
1. `candidateProvider.candidates()` → `presetKey` null이면 `DAYTRADE_*` prefix **전체 합집합**(default).
2. 각 프리셋 `entry_dsl` → `parser.parse()` + `validateStatic()`. 파싱 실패 프리셋은 **graceful skip**(전체 발굴을 깨지 않음).
3. universe = `InstrumentLookupPort.findActive(filter)` (KIS 호출 0 — instrument 영속 reuse).
4. 종목별 `CandleRepository.findLatestByCodeAndInterval(code, '1d', lookback)` 후 `minCandles`(default 20) 미만이면 skip 매트릭스 누적.
5. `StrategyEvaluatorPort.evaluate(-1L, ast, slice)` — 어느 프리셋이든 첫 BUY 매칭이면 후보 채택(프리셋 OR 매칭).
6. `MultiCriteriaRanker.rank()` — signal 0.40 / volume 0.30 / atr 0.20 / cap 0.10 weighted sum, 동점은 code 사전순(결정성 LOCK).
7. `startRealtimeWatch=true`면 owner=STRATEGY WS 수요 발행(watchlist write 0 LOCK).

→ **V76 시드 추가만으로 default 발굴에 자동 합류**한다. 코드 변경 0. 단 `entry_dsl`이 화이트리스트를 통과해야 graceful skip을 피한다.

> **랭킹 상호작용 주의(설계 함정)**: ranker는 프리셋과 무관하게 매칭 슬라이스의 turnover/ATR을 다시 점수화한다. 프리셋이 BUY를 내도 turnover가 낮거나 ATR이 sweet spot(0.5%~20%, peak 4%) 밖이면 랭킹 하위로 밀린다. 즉 **프리셋 entry_dsl의 유동성·변동성 임계는 ranker의 normalize 산식과 정합**시켜야 후보가 상위로 노출된다. (예: turnover 임계를 ranker MIN_TURNOVER 100만~MAX 1조 로그 스케일의 어디에 둘지 의식.)

---

## §2. 작성 방법 — 일봉 가용 식별자 표 + DSL 조합 규칙

### 2-1. DSL 평가 의미론(읽기로 확인)

- 비교 연산: `> < >= <= == !=`, 논리 `and / or`, 산술 `+ - * /`(우선순위 정상), 그룹 `( )`.
- `crosses_above` / `crosses_below`: **직전 봉 vs 현재 봉** 2-시점 교차(예: `close crosses_above sma(20)`).
- `breaks`: 현재값 > 기준값(BREAKOUT 분류 — 의미상 `>`와 동일 평가).
- `consecutive("up"|"down")`: 연속 양봉/음봉 수 반환(예: `consecutive(up) >= 2`).
- `prev_close`: candle slice의 직전 봉 종가(size<2면 0 fallback).
- 자체 함수 `highest`·`lowest`·`returns`·`change`: IndicatorEngine 등록 함수(period 인자).

### 2-2. 일봉에서 실제 평가 가능 식별자 (화이트리스트 ∩ 일봉 컨텍스트)

| 식별자 | 일봉 평가 | 단타 발굴 용도 | 비고 |
|---|---|---|---|
| `open` `high` `low` `close` `volume` `prev_close` | ✅ | OHLCV 직접 접근. 갭=`(open-prev_close)/prev_close`, 거래대금=`close*volume` | 핵심 |
| `volume_ma(n)` | ✅ | RVOL=`volume/volume_ma(20)` | 핵심 |
| `atr(n)` `natr(n)` | ✅ | 변동성=`atr(14)/close` 또는 `natr(14)` 직접 | NATR이 정규화값 |
| `sma(n)` `ema(n)` | ✅ | 추세·눌림목 기준선 | |
| `rsi(n)` | ✅ | 과매수/과매도 | 단타는 보조 |
| `adx(n)` | ✅ | 추세 강도 | 횡보 배제용 |
| `disparity(n)` | ✅ | 이격도(close/sma×100) | 과열 배제 |
| `obv` | ✅ | 누적 거래량 추세 | 보조 |
| `consecutive("up"/"down")` | ✅ | 연속 양봉 모멘텀 | JvmStrategyEvaluator 명시 처리(line 540) |
| `donchian_upper(n)` `donchian_lower(n)` | ✅ | **n봉 신고가/신저가 돌파**(채널 상/하단) | catalog 205·206 — breakout 정식 표현 |
| `macd` `bb_upper/lower/middle` `stoch_k/d` | ✅ | 보조 지표 | 단타 핵심 아님 |

### 2-3. 일봉에서 **표현 불가** (가설에 쓰면 안 됨 — 명시 LOCK)

| 미지원 개념 | 사유 |
|---|---|
| `trade_intensity`(체결강도/CTTR) | **tick-only**. 일봉 평가 시 식별자 미해결 → false. V75·V76 장전 entry_dsl 절대 미포함(spec §3-R2 LOCK). 장중 확인 경로 전용. |
| VWAP(세션 누적) | 일봉 1봉에 세션 내 누적 정보 없음. `vwap` 식별자는 존재하나 일봉에서는 단일봉 근사라 단타 세션 VWAP 의미 상실. 단타 가설에 사용 금지. |
| ORB(개장 30분 박스 돌파) | 분봉/세션 함수 필요. 일봉 표현 불가. |
| time-of-day RVOL(시간대별 누적 거래량) | 세션 내 시각 정보 필요. 일봉 불가. |
| 호가 잔량·체결 비율 | tick/orderbook 데이터. 일봉 불가. |
| `highest(n)` `lowest(n)` `returns(n)` `change(n)` | **함정**: 파서 화이트리스트(`ALLOWED_IDENTIFIERS`)는 통과하나, `DslIdentifierMapper.toIndicatorSpec`의 CATALOG_IDS(83지표)·directCandleAccess(6종)에 **미등록** → `evaluate` 시 `UnknownDslSymbolException` → 발굴 graceful skip(매칭 0). **현재 엔진에서 평가 불가**. 신고가 돌파는 `donchian_upper(n)`, 수익률은 산술식(`close/prev_close − 1`)으로 대체. (실측 확인: catalog grep 0건 + directCandleAccess case 부재.) |

### 2-4. DSL 조합 규칙(설계 원칙)

1. **유동성 게이트 필수** — 모든 단타 프리셋은 `close > 2000`(동전주 제외) + `close*volume > <임계>`(거래대금)를 AND로 포함. 슬리피지·체결 가능성 보호.
2. **신호 + 수급 동반** — 가격 신호(갭·돌파·양봉) 단독은 약하다. `volume > volume_ma(20) * <배수>`(RVOL)를 동반해 수급 확인.
3. **과열 상한** — 단타 진입은 추격이 위험하다. 필요 시 상한(`natr(14) < close*0.08` 또는 `disparity(20) < 110`)으로 과열 종목 배제.
4. **AND 4~5개, depth 얕게** — 가설은 해석 가능해야 한다. 조건 4~5개·AST depth 낮게(과최적화 표면적 최소화). 조건 8개 이상은 curve fitting 의심.
5. **ranker 정합** — §1-4 함정 참조. turnover·ATR 임계를 ranker normalize와 어긋나지 않게.

---

## §3. 선별 게이트 기준 — 확률 높은 프리셋의 정량 통과 조건

> R3 ④검증 출력(`OverfitDiagnostics`·`SensitivityResult`)과 **1:1 매핑**. 게이트 전부 통과한 가설만 V76 승격.

### 3-1. 통과 게이트 (LOCK — spec §3-R3 + ADR 0056 정합)

| # | 게이트 | 기준 | R3 출력 필드 매핑 | 의미 |
|---|---|---|---|---|
| G1 | **OOS 기대값 양(+)** | `outOfSample.totalReturn > 0` | `OverfitDiagnostics.outOfSample` | 미본 구간에서도 돈을 번다 |
| G2 | **표본 충분** | OOS `totalTrades >= 100` | `outOfSample.totalTrades` | 통계적 유의성 최소 표본. 100 미만은 운(noise)과 구분 불가 → 판정 보류 |
| G3 | **MDD 한도** | OOS `maxDrawdown <= 8%` | `outOfSample.maxDrawdown` | 단타 자본 보호 한도 |
| G4 | **IS→OOS 저하 제한** | `returnDegradation <= 0.50` | `OverfitDiagnostics.returnDegradation` | 저하율 50% 초과 = 과최적화 강한 의심. (degradation = (IS−OOS)/IS) |
| G5 | **파라미터 plateau** | `SensitivityResult.isPlateau == true` | `SensitivityResult` | 임계 ±1~2 grid 점 성과 분산 작음 = robust. 한 점만 좋으면 우연 |
| G6 | **Profit Factor 하한** | OOS `profitFactor >= 1.2`(또는 null 아님) | `outOfSample.profitFactor` | 손익비×승률 종합 우위 |
| G7 | **유동성·NATR sweet spot** | 후보 universe 통과율 0.3%~5% | (분포 측정 — 본 문서 §2 데이터) | 너무 희소(표본부족)/너무 과다(필터부재) 둘 다 탈락 |
| G8 | **레짐 독립성** | 상승장·하락장·횡보장 분할 OOS 각각 G1 충족(또는 최소 음(−) 아님) | (다구간 OOS run) | 특정 장세 의존 우위 배제 |

### 3-2. 게이트 판정 규칙

- **하드 게이트(필수)**: G1·G2·G3·G4·G5. 하나라도 실패 시 **V76 승격 불가**(가설 폐기 또는 임계 재설계 후 재검증).
- **소프트 게이트(권장)**: G6·G7·G8. 실패 시 폐기 아닌 "주의 라벨"(market_fit 문구에 한계 명시) + 사용자 노출 시 경고.
- **G2 미달(거래<100)**: 통과/실패 아닌 **"판정 불가(insufficient)"**. universe 확대(종목·기간)로 표본 확보 재시도. 그래도 미달이면 가설 자체가 너무 희소(G7 실패와 동치).

### 3-3. 정직성 규칙

- 게이트 통과 = "이 데이터·기간에서 OOS 우위가 관측됨". **미래 보장 아님**. market_fit·문서에 "과거 검증 기준 / 미래 보장 없음" 명시.
- 게이트 미통과 가설도 문서에 **실패 기록 보존**(왜 떨어졌는지). 같은 실수 반복 방지.

---

## §4. 후보 프리셋 가설 (8종) + V75 3종 재평가

> 각 가설: **논리 · DSL · 데이터 근거 임계 · 예상 실패모드 · 검증 시 볼 지표**.
> 통과율은 **2024-06-01 이후 KOSPI+KOSDAQ 일봉, close≥2000원, 20봉 워밍업** universe 실측(종목-일 기준, n≈100만~117만).

### 4-A. 데이터 근거 — 핵심 분포 (2024-06 ~ 2026-06 실측)

| 지표 | p10 | p50 | p75 | p90 | p95 | p99 |
|---|---|---|---|---|---|---|
| 갭상승률 (open/prev_close−1) | — | 0.00% | — | 1.60% | 2.58% | 6.11% |
| RVOL (volume/volume_ma20) | — | 0.71 | — | 1.91 | 2.94 | 11.74 |
| 거래대금 (close×volume, 원) | — | 6.3억 | 33억 | 165억 | 406억 | — |
| 일중 고저폭 ((h−l)/c) | — | 3.50% | 5.51% | 8.59% | 11.48% | — |
| **NATR (atr14/close)** | **2.10%** | **4.16%** | — | **8.12%** | **9.83%** | — |

**핵심 통찰**: NATR 중앙값이 4.16%다. 한국 주식 단타 universe는 본질적으로 변동성이 크다(미국 대형주와 다름). 따라서 "변동성 1.5% 이상"은 거의 모든 종목을 통과시키는 무의미 조건이다(아래 V75 재평가 참조).

---

### 4-B. 후보 가설 8종

#### H1. 갭앤고 (Gap-and-Go) — `DAYTRADE_GAP_AND_GO`
- **논리**: 전일 종가 대비 의미 있는 갭상승(p90~p95 구간)으로 출발 + 수급 폭발 + 충분한 거래대금. 갭이 메워지지 않고 추세로 이어지는 단타 셋업.
- **DSL**: `open >= prev_close * 1.025 and volume > volume_ma(20) * 2.5 and close * volume > 16000000000 and close > 2000`
- **데이터 근거**: 갭 2.5%는 p95(2.58%) 근방 — 상위 5% 갭만 채택. RVOL 2.5는 p90~p95(1.91~2.94). 거래대금 160억은 p90(165억). → 압축 강함.
- **예상 실패모드**: 갭 후 즉시 되메움(gap fill), 시초가 고점 후 음봉. 갭이 클수록 추격 매수 손실 risk.
- **검증 시**: OOS winRate·profitFactor·avgWin/avgLoss. 갭 크기 2.0%~3.0% sweep으로 plateau 확인(G5).

#### H2. 거래량 폭발 신고가 돌파 (Volume Breakout) — `DAYTRADE_VOLUME_BREAKOUT`
- **논리**: 20봉 신고가를 거래량 폭발과 함께 돌파. 박스권 상단 돌파 모멘텀.
- **DSL**: `close > donchian_upper(20) and volume > volume_ma(20) * 2.0 and close * volume > 10000000000 and close > 2000`
  - (`donchian_upper(20)` = 20봉 채널 상단 = 신고가 기준. `highest()`는 엔진 미등록이라 사용 불가 — §2-3 함정 참조.)
- **데이터 근거**: 통과율 **1.05%** 실측(직전 20봉 max high 근사 — 돈치안 현재봉 포함 여부로 미세 차이 가능). RVOL 2.0=p90, 거래대금 100억=p90 근방.
- **예상 실패모드**: 가짜 돌파(false breakout) 후 재진입 실패. 신고가에서 매물 출회.
- **검증 시**: 돌파 다음날 follow-through 비율. lookback period 10·15·20·25·30 sweep plateau.

#### H3. 모멘텀 양봉 연속 (Momentum Continuation) — `DAYTRADE_MOMENTUM_CONT`
- **논리**: 연속 양봉으로 매수세 지속 + 당일도 강한 양봉 + 수급. 추세 추종 단타.
- **DSL**: `consecutive(up) >= 2 and close >= prev_close * 1.008 and volume > volume_ma(20) * 1.8 and close * volume > 8000000000 and close > 2000`
- **데이터 근거**: `consecutive(up)>=2`는 2일 연속 양봉. RVOL 1.8≈p88, 거래대금 80억≈p87. V75 MOMENTUM_BREAKOUT(2.52%)에 연속 조건 추가 → 더 압축 예상.
- **예상 실패모드**: 연속 상승 후 차익실현 반락. 과열 진입.
- **검증 시**: consecutive 2 vs 3 비교. 과열 상한(`disparity(20) < 115`) 추가 효과.

#### H4. MA 눌림목 반등 (Pullback to MA) — `DAYTRADE_MA_PULLBACK`
- **논리**: 상승 추세 종목이 SMA20으로 눌렸다 되찾는(reclaim) 양봉 + 수급. 추세 내 저가 매수.
- **DSL**: `close > sma(20) and close < sma(20) * 1.03 and close > open and volume > volume_ma(20) * 1.5 and close * volume > 5000000000 and close > 2000`
- **데이터 근거**: 통과율 **0.23%** 실측 — 다소 빡빡(G2 거래 100건 충족 가능하나 universe 확대 권장). SMA20 위 0~3% 밴드.
- **예상 실패모드**: 눌림이 추세 전환의 시작일 수 있음(MA 이탈). 횡보장에서 잦은 whipsaw.
- **검증 시**: 밴드 폭 0~3% vs 0~5% sweep(완화 시 통과율↑·우위 유지되는지). adx(14)>20 추세 게이트 추가 효과.

#### H5. 변동성 수축 후 확장 (Squeeze Expansion) — `DAYTRADE_SQUEEZE_EXPANSION`
- **논리**: 직전 N봉 변동성이 수축(횡보 압축)했다가 당일 거래량과 함께 범위 확장. 에너지 응축 후 분출.
- **DSL(1차)**: `atr(14) < atr(28) * 0.85 and close > open and volume > volume_ma(20) * 2.0 and close * volume > 5000000000 and close > 2000`
  - (단기 ATR < 장기 ATR×0.85 = 변동성 수축 상태에서 양봉 분출. NR-range를 직접 못 쓰므로 ATR ratio로 근사.)
- **데이터 근거**: 직접 NR<2% 조건은 통과율 **0.045%**로 과희소(G7 실패). → ATR 단/장기 비율 근사로 임계 완화 권장. **임계 sweep 필수**(0.80·0.85·0.90).
- **예상 실패모드**: 수축이 길어지면 분출 없이 추가 횡보. 거짓 분출.
- **검증 시**: ratio 임계 plateau. 가장 큰 과최적화 risk 가설(임계 민감 → G5 엄격 적용).

#### H6. 박스권 상단 돌파 + 추세 게이트 (Range Breakout) — `DAYTRADE_RANGE_BREAKOUT`
- **논리**: H2와 유사하나 추세 강도(ADX) 게이트로 횡보장 가짜 돌파 배제.
- **DSL**: `close > donchian_upper(20) and adx(14) > 20 and volume > volume_ma(20) * 2.0 and close * volume > 8000000000 and close > 2000`
- **데이터 근거**: H2(1.05%)에 adx>20 게이트 추가 → 더 압축. adx>20은 추세 형성 통상 기준.
- **예상 실패모드**: ADX 후행성으로 진입 지연. 강추세 끝물 진입.
- **검증 시**: H2 대비 adx 게이트의 OOS 개선 여부(게이트가 실제로 가짜 돌파를 거르는지). adx 임계 15·20·25 sweep.

#### H7. 거래대금 급증 + RSI 비과열 (Turnover Surge, Not Overbought) — `DAYTRADE_TURNOVER_SURGE`
- **논리**: 거래대금이 평소 대비 급증(시장 관심 유입)하되 RSI가 과열 전(추격 회피). 관심 초입 포착.
- **DSL**: `close * volume > 20000000000 and volume > volume_ma(20) * 3.0 and rsi(14) < 70 and rsi(14) > 50 and close > open and close > 2000`
- **데이터 근거**: 거래대금 200억(p90~p95 사이), RVOL 3.0≈p95. RSI 50~70 = 상승 모멘텀 있되 과열 전.
- **예상 실패모드**: 거래대금 급증이 고점 분산일 수 있음(상투). RSI 후행.
- **검증 시**: RSI 상한 65·70·75 sweep. 거래대금 임계 효과.

#### H8. 강한 마감 + 익일 모멘텀 (Strong Close Carry) — `DAYTRADE_STRONG_CLOSE`
- **논리**: 당일 고가 근처에서 강하게 마감(종가가 일중 고저폭 상위) + 양봉 + 수급. 익일 갭/모멘텀 carry 기대.
- **DSL**: `close > open and close >= low + (high - low) * 0.8 and close >= prev_close * 1.01 and volume > volume_ma(20) * 2.0 and close * volume > 8000000000 and close > 2000`
  - (`close >= low + (high-low)*0.8` = 종가가 당일 범위 상위 20%에 마감 = 강한 마감.)
- **데이터 근거**: 산술식으로 "고가 마감" 표현(close 위치). RVOL 2.0=p90, 거래대금 80억≈p87.
- **예상 실패모드**: 강한 마감 후 익일 갭다운. 마감 동시호가 왜곡.
- **검증 시**: 마감 위치 0.7·0.8·0.9 sweep. 익일 시초 갭 분포와 결합 분석.

---

### 4-C. V75 현행 3종 재평가 (데이터 기반 — 유지/수정/폐기)

#### V75-1. `DAYTRADE_GAP_RVOL_TURNOVER` (sort 200) — **유지(미세 수정 권장)**
- DSL: `open >= prev_close * 1.015 and volume > volume_ma(20) * 2.5 and close * volume > 1e10 and close > 2000`
- 실측 통과율 **1.11%** — 적정 압축. 임계가 합리적.
- **수정 의견**: 갭 1.5%는 p90(1.60%) 근방으로 적정하나, H1(갭 2.5%, p95)과 차별화하려면 그대로 두고 H1을 별도 "더 공격적" 프리셋으로 병치 권장. RVOL 2.5=p90~p95로 양호.
- **판정**: R3 검증 대상으로 **유지**. 단 갭+RVOL+거래대금 3중 AND라 표본이 1.11%로 적정하니 G2(거래≥100) 충족 가능성 높음.

#### V75-2. `DAYTRADE_VOLATILITY_EXPANSION` (sort 210) — **수정 필수(임계 부적합)**
- DSL: `atr(14) > close*0.015 and atr(14) < close*0.06 and volume > volume_ma(20)*2.0 and close*volume > 5e9`
- **결정적 문제**: ATR sweet spot **1.5%~6% 밴드는 종목-일의 72.56%를 통과**(실측). NATR 중앙값이 4.16%라 한국 단타 universe 대부분이 이 밴드에 들어간다.
  - `atr14 > close*0.015` 단독: **96.41% 통과**(거의 무의미).
  - `atr14 < close*0.06` 단독: 76.14% 통과.
  - 전체 프리셋 통과율 2.28%는 ATR 밴드가 아니라 **RVOL 2.0 + 거래대금 50억이 만들어낸 것**. ATR 조건은 사실상 일하지 않는다.
- **수정 의견(R3 sweep 대상)**: 변동성을 **차별화하려면 하한을 중앙값 위로** 올려야 한다. 데이터 기반 후보 밴드:
  - 옵션 A: `atr(14) > close*0.04 and atr(14) < close*0.10` (p50~p90 — 고변동 단타 종목 표적, 통과율 추정 ~20% 이하)
  - 옵션 B: `natr(14) > 5 and natr(14) < 12` (NATR 직접 — 가독성↑)
  - R3에서 하한 0.03·0.04·0.05 sweep으로 plateau 확인.
- **판정**: **수정 필수**. 현 밴드는 "변동성 필터"라는 이름값을 못 한다. R3 검증 전 임계 재설계 권장.

#### V75-3. `DAYTRADE_MOMENTUM_BREAKOUT` (sort 220) — **유지(H3로 강화 검토)**
- DSL: `close > open and close >= prev_close*1.008 and volume > volume_ma(20)*1.8 and close*volume > 8e9`
- 실측 통과율 **2.52%** — 적정~약간 느슨. 양봉+0.8% 상승+RVOL 1.8.
- **수정 의견**: `close > open`(양봉) + 0.8% 상승은 약한 모멘텀 조건. H3(`consecutive(up)>=2` 추가)가 연속성으로 신호를 강화한 변형. R3에서 V75-3 vs H3 직접 비교 → 우월한 쪽 유지.
- **판정**: **유지하되 H3와 A/B 검증**. 둘 중 OOS 우위 높은 것을 최종 채택, 나머지 폐기 또는 차별화.

#### 재평가 요약
| 프리셋 | 통과율 | 판정 | 핵심 사유 |
|---|---|---|---|
| GAP_RVOL_TURNOVER | 1.11% | 유지 | 임계 합리적, 적정 압축 |
| VOLATILITY_EXPANSION | 2.28% | **수정 필수** | ATR 1.5~6% 밴드 72.56% 통과 = 필터 부재. 하한 상향 필요 |
| MOMENTUM_BREAKOUT | 2.52% | 유지(H3 비교) | 양호하나 H3로 강화 검토 |

---

## §5. R3 검증 계획 — 가설→게이트 판정 plan

> **전제**: R3 ④검증(`BacktestRequest.outOfSampleSplit` + `OverfitDiagnostics` + `SensitivityRequest/Result`)이 병렬 구현 중. **머지 후** 본 plan 실행. 본 문서는 코드 변경 0 — 검증은 R3 엔진 산출물 활용.

### 5-1. 데이터·기간 설계

- **universe**: KOSPI+KOSDAQ active STOCK, close≥2000원(동전주 제외 — 프리셋 게이트와 동일).
- **전체 기간**: 2021-01-01 ~ 2026-06-09(약 5.4년, 일봉 적재 확인됨 — 2006~2026).
- **IS/OOS 분할(`outOfSampleSplit`)**:
  - IS: 2021-01-01 ~ 2024-06-01
  - OOS: 2024-06-01 ~ 2026-06-09 (분포 측정에 쓴 구간과 동일 — 최근 레짐)
  - degradation = (IS.totalReturn − OOS.totalReturn) / IS.totalReturn (G4).
- **레짐 분할 OOS(G8)**: 전체 기간을 KOSPI 지수 추세로 상승/하락/횡보 구간 라벨링 후 각 구간 OOS run. (구간 라벨링은 별도 분석 — 지수 candle 추세로.)

### 5-2. exit 모델(백테스트 entry/exit 일관)

- 각 프리셋의 `target_profit_pct` / `stop_loss_pct` / `max_hold_bars`(현 V75 = 2.0/1.2/8 등)를 백테스트 exit으로 사용.
- 슬리피지·수수료·세금은 ADR 0056 엔진 기본 모델 그대로(임의 변경 0).
- **단타 = 일봉 max_hold 8봉**은 사실상 8 거래일 보유. "단타"라는 이름과 일봉 백테스트의 괴리를 리포트에 명시(일봉 백테스트는 분봉 단타의 근사 — 정직 LOCK).

### 5-3. 파라미터 sweep (G5 — 가설별 1축)

| 가설 | sweep 파라미터 | 그리드 | plateau 기대 |
|---|---|---|---|
| H1 갭앤고 | 갭 임계 | 2.0·2.25·2.5·2.75·3.0(%) | 인접 점 totalReturn 분산 작음 |
| H2/H6 돌파 | highest lookback | 10·15·20·25·30 | |
| H3 모멘텀 | consecutive 수 | 2·3·4 + RVOL 1.6·1.8·2.0 | |
| H4 눌림목 | SMA 밴드 폭 | 0~3%·0~4%·0~5% | |
| H5 squeeze | atr ratio 임계 | 0.80·0.85·0.90 | **가장 민감 — 엄격 적용** |
| H7 surge | RSI 상한 | 65·70·75 | |
| H8 강한마감 | 마감 위치 | 0.7·0.8·0.9 | |
| V75-2 수정 | atr 하한 | 0.03·0.04·0.05 | |

- `SensitivityResult.isPlateau == true` 아니면 G5 실패 → 폐기 또는 robust 구간으로 임계 이동.

### 5-4. 게이트 판정 → 승격 절차

1. 각 가설 IS/OOS run → `OverfitDiagnostics` 수집.
2. G1~G6 하드·소프트 판정. G2 미달이면 universe 기간 확대(2018~) 재시도 1회.
3. sweep → G5 plateau 판정.
4. 레짐 분할 OOS → G8.
5. **G1·G2·G3·G4·G5 전부 통과한 가설만** V76 시드로 발행(§1-3 골격). 통과 못 한 가설은 실패 사유와 함께 본 문서에 기록 보존.
6. 결과 리포트 → `_workspace/strategy/sample-backtest/`에 1~2건 샘플 run 산출물 첨부(spec §3-R3-측정).

### 5-5. 검증 시 우선순위

- **1순위**: V75-2 수정안(임계 부적합 확정 — 가장 시급) + V75-3 vs H3 A/B.
- **2순위**: H1·H2(통과율 적정·구현 단순 — 빠른 판정 가능).
- **3순위**: H5(과최적화 risk 최대 — sweep 가장 엄격) + H4(희소 — 표본 확보 확인).

### 5-6. 정직성 가드 (전 과정 LOCK)

- OOS 결과 좋아도 "확률 높다" 단정 금지. "본 검증 기간·universe에서 OOS 우위 관측" 표현.
- 백테스트는 일봉 근사 — 실제 분봉 단타 체결·슬리피지와 다름 명시.
- 통과 가설도 사용자 노출 시 "후보 출발점·자동매매 결정권 0"(CLAUDE.md FVG/OrderBlock 가드) 유지.

---

## 부록 A. 데이터 탐색 쿼리 재현

분포·통과율은 `docker exec magicjar-postgres psql -U magicjar -d magicjar`로 재현 가능. 모든 측정은 2024-06-01 이후 일봉, close≥2000, 20봉 워밍업, vol_ma20>0 조건. 윈도우 함수(LAG/AVG/MAX OVER PARTITION BY code ORDER BY ts)로 갭·RVOL·거래대금·ATR14(TR 14봉 평균)·신고가·SMA를 계산하고 percentile_cont / FILTER count로 분포·통과율 산출. (쿼리는 task 세션 로그 보존 — DB 상태는 적재 시점 의존이므로 재실행 시 수치 미세 변동 가능.)

## 부록 B. 미해결·후속

- 체결강도(trade_intensity) A/B는 R1 tick 흡수 + post-R3 MOCK 페이퍼 트랙(spec §1 B층). 본 문서 범위 밖.
- 분봉(5m) 백테스트는 전 종목 분봉 미적재(spec — 일봉만 완비)로 현재 불가. 일봉 근사로 진행, 분봉 적재 시 재검증 후보.
- 레짐 라벨링(상승/하락/횡보) 산식은 별도 분석 필요(KOSPI 지수 SMA 기울기·ADX 등).
