package kr.spdaewoon.magicjar.strategy.autopilot

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * magicjar-autopilot **페이퍼 섀도 스코어링 순수 함수** (개선 G · 슬롯 공정성 · 2026-06-22).
 *
 * ## 왜 필요한가 (문제 — 2026-06-22 #1101 진단 실증)
 * autopilot 6가설(owner=AUTOPILOT 전략)이 동시에 **실 RiskConfig `maxConcurrentPositions=3`** 슬롯을 다툰다.
 * 3m·조기발화 전략(100 momentum·104 volume·105 vwap)이 11:57~13:00 에 슬롯3 을 선점하면, 5m 후발 전략
 * (102 fvg·103 ob)은 신호가 풍부(10·14건)해도 첫 신호 도착(13:40/13:50)이 늦어 `MAX_CONCURRENT_POSITIONS`·
 * `POSITION_LIMIT` 으로 **전량 BLOCKED**(order_request 0건). 6가설을 켜는 의미인 **다양성 학습이 죽는다** —
 * "어느 메커니즘이 먹히나"의 점수가 starve 전략에선 0 으로 남는다.
 *
 * ## 접근 — paper-parallel (실자본 0, 슬롯 무제한 학습)
 * 실 슬롯을 못 받아 starve 된 owner=AUTOPILOT BUY 를, **실 자본을 1원도 쓰지 않고** 가상으로 "진입했다 치고"
 * 후속 캔들 대비 TP/SL 도달을 판정해 가설별 승/패를 누적한다. 6가설 전부 점수가 쌓여 다음 사이클 승격/강등의
 * 학습 입력이 된다(spec §학습 조건 A~F · Phase 1 §4 측정: 승률·기대값). **실보유 동시 cap 3 은 불변** — 페이퍼는
 * 자본 0 이라 슬롯 cap 과 무관하다.
 *
 * ## 본 함수의 책임 (순수 — 결정론)
 * 입력(진입가·TP%·SL%·후속 캔들 high/low 시퀀스) → 출력([ShadowOutcome]) 결정론. Spring·JPA·IO·난수 0.
 * "starve 판정"·"섀도 행 영속"·"후속 캔들 조회"는 호출자(consumer) 책임이고, 본 함수는 **점수 산정 룰**만 갖는다.
 *
 * ## 판정 룰 (롱 온리 단타 — 보수적·낙관 금지)
 * 진입가 `entry`, 목표가 `tp = entry × (1 + tpPct/100)`, 손절가 `sl = entry × (1 - slPct/100)`.
 * 진입 **다음** 캔들부터 시간순으로 순회하며 같은 캔들 안에서 SL·TP 가 모두 닿을 수 있으면(intrabar 모호) **SL 우선**
 * 채택한다(고가·저가 동시 도달은 어느 쪽이 먼저인지 캔들만으론 모르므로 손실측을 택해 과대평가를 막는다 —
 * 백테스트 정직성 원칙). 한 캔들에서:
 *  1. `low <= sl` → [ShadowOutcome.Loss] (SL 우선).
 *  2. 아니고 `high >= tp` → [ShadowOutcome.Win].
 *  3. 둘 다 아니면 다음 캔들로.
 * 모든 후속 캔들을 소진해도 미도달이면 [ShadowOutcome.Open](아직 미결 — 다음 스코어링 주기 재평가).
 *
 * ## 폐기 철학 (spec §3-bis Tier A)
 * autopilot 전용 패키지(`kr.spdaewoon.magicjar.strategy.autopilot`)에 격리. 폐기 = 패키지 제거 → 기존 매매 동작
 * v3.14.0 그대로(autopilot 미설정 시 zero-regression).
 *
 * // AI 호출 0 — 룰 기반 결정론 스코어링 (ADR 0023 §2-4 정합)
 */
object AutopilotPaperShadowScorer {
    private val ZERO: BigDecimal = BigDecimal.ZERO
    private val HUNDRED: BigDecimal = BigDecimal("100")

    /** 목표가·손절가 산정 정밀도. 가격 비교 전 충분한 자릿수. */
    private const val SCALE: Int = 4

    /**
     * 페이퍼 섀도 1건 판정 — 진입가 + TP%/SL% + 후속 캔들 high/low 시퀀스(시간순)로 승/패/미결을 산정한다.
     *
     * @param entryPrice 가상 진입가(원, > 0). starve 된 BUY 시그널의 `priceAtSignal`.
     * @param takeProfitPct 익절 % (> 0). 전략 RiskPlan 의 takeProfitPct.
     * @param stopLossPct 손절 % (> 0). 전략 RiskPlan 의 fixedStopLoss.
     * @param subsequentBars 진입 **다음** 캔들부터 시간순(ts ASC) high/low 시퀀스. 빈 list → [ShadowOutcome.Open].
     * @return 첫 도달 판정([ShadowOutcome.Win]/[ShadowOutcome.Loss]) 또는 미도달([ShadowOutcome.Open]).
     */
    fun score(
        entryPrice: BigDecimal,
        takeProfitPct: BigDecimal,
        stopLossPct: BigDecimal,
        subsequentBars: List<Bar>,
    ): ShadowOutcome {
        if (entryPrice <= ZERO || takeProfitPct <= ZERO || stopLossPct <= ZERO) {
            // 산정 불가 — 미결로 둔다(낙관 금지 · 0 가격/한도 fabricate 금지).
            return ShadowOutcome.Open
        }

        val tp = entryPrice.multiply(HUNDRED.add(takeProfitPct)).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
        val sl = entryPrice.multiply(HUNDRED.subtract(stopLossPct)).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)

        for (bar in subsequentBars) {
            // SL 우선(intrabar 모호 시 손실측 — 과대평가 방지).
            if (bar.low <= sl) {
                return ShadowOutcome.Loss(exitPrice = sl, hitBarTs = bar.ts)
            }
            if (bar.high >= tp) {
                return ShadowOutcome.Win(exitPrice = tp, hitBarTs = bar.ts)
            }
        }
        return ShadowOutcome.Open
    }

    /**
     * 페이퍼 섀도 1건 손익 산정 — 진입가·청산가·수량으로 net PnL(원).
     *
     * 페이퍼는 학습 신호용이므로 비용(수수료·세금·슬리피지)을 **보수적으로 차감**한다(가설 레지스트리 §C — 왕복 ≈ 0.3%).
     * net 이 비용을 못 넘기면 "먹히는 가설"이 아니라는 정직한 신호를 준다(낙관 금지).
     *
     * @param entryPrice 진입가(원).
     * @param exitPrice 청산가(원 — TP 또는 SL 도달가).
     * @param quantity 가상 수량(주). > 0.
     * @param roundTripCostPct 왕복 비용 % (default 0.3 — 가설 레지스트리 §C 보수값).
     * @return net 실현손익(원, 부호 그대로 — 음수 = 손실).
     */
    fun realizedPnl(
        entryPrice: BigDecimal,
        exitPrice: BigDecimal,
        quantity: Int,
        roundTripCostPct: BigDecimal = DEFAULT_ROUND_TRIP_COST_PCT,
    ): BigDecimal {
        if (quantity <= 0 || entryPrice <= ZERO) return ZERO
        val qty = BigDecimal(quantity)
        val gross = exitPrice.subtract(entryPrice).multiply(qty)
        // 비용 = 진입대금 × 왕복비용% (보수적 — 진입·청산 양변 합산 근사).
        val cost =
            entryPrice
                .multiply(qty)
                .multiply(roundTripCostPct)
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
        return gross.subtract(cost)
    }

    /** 가설 레지스트리 §C 보수값 — 매수 0.014% + 매도 0.014% + 거래세 ~0.18% + 슬리피지 여유 ≈ 왕복 0.3%. */
    val DEFAULT_ROUND_TRIP_COST_PCT: BigDecimal = BigDecimal("0.3")

    /** 스코어링 입력 캔들 — high/low/ts 만 필요(close·volume 불요 — 도달 판정은 고가·저가). */
    data class Bar(
        val high: BigDecimal,
        val low: BigDecimal,
        val ts: java.time.Instant,
    )
}

/**
 * [AutopilotPaperShadowScorer.score] 출력 — 페이퍼 섀도 1건의 판정(sealed).
 *
 * 호출자(consumer 스코어러)는 Win/Loss 면 섀도 행을 RESOLVED 로 마킹 + net PnL 누적, Open 이면 다음 주기 재평가.
 */
sealed interface ShadowOutcome {
    /** 목표가(TP) 먼저 도달 — 가상 익절. */
    data class Win(
        val exitPrice: BigDecimal,
        val hitBarTs: java.time.Instant,
    ) : ShadowOutcome

    /** 손절가(SL) 먼저 도달(또는 intrabar 모호 시 손실 우선) — 가상 손절. */
    data class Loss(
        val exitPrice: BigDecimal,
        val hitBarTs: java.time.Instant,
    ) : ShadowOutcome

    /** TP·SL 모두 미도달 — 아직 미결(다음 스코어링 주기 재평가). */
    data object Open : ShadowOutcome
}
