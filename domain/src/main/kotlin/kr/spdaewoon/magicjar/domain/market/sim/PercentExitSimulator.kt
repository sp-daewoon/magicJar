package kr.spdaewoon.magicjar.domain.market.sim

/**
 * 진입가 기준 % 청산 시뮬 단일 순수 함수 (2026-09-19 — 중복 시뮬레이션 통합 A).
 *
 * ## 왜 필요한가 (중복 제거)
 * "진입가 대비 targetPct 익절 / stopPct 손절 / maxBars 시간청산, 같은 봉에서 SL·TP 동시 도달 시 SL 우선"
 * 이라는 청산 판정을 [StrategyResearchEngine](발굴 리서치)이 자체 재구현하고 있었다. 이는 백테스트의
 * [kr.spdaewoon.magicjar.strategy.IntradayExitSimulation](TradePlan 기반 intrabar SL 우선)과 **개념이 같은
 * 로직의 중복**이다. TradePlan 기반 시뮬은 무겁고(전략 계획 필요), 발굴은 단순 %만 쓰므로, 본 순수 함수를
 * **% 기반 청산의 단일 출처**로 두어 발굴·리서치·향후 간이 시뮬이 공유한다(IntradayExitSimulation 은
 * TradePlan 경로 단일 출처로 병존 — 두 축이 명확히 분리).
 *
 * ## SL 우선 (보수적 — 낙관 편향 차단, IntradayExitSimulation 과 동일 원칙)
 * 한 봉 안에서 봉 low ≤ stop 이면 TP 도달 여부와 무관하게 **손절 우선 체결**. 같은 봉 SL→반등→TP 순서는
 * 알 수 없으므로 손실을 먼저 인정하는 것이 보수적 정답(mock 으로 메우지 않음 — 구조적 한계 정직).
 *
 * ## 순수성 (LOCK)
 * Spring·IO·Clock 의존 0. 입력 → 출력 결정론. Double 기반(발굴 엔진 정합 — BigDecimal 변환은 caller).
 *
 * // AI 호출 0 — 룰 기반 순수 함수 (ADR 0023 §2-4)
 */
object PercentExitSimulator {
    /** 한 봉의 OHLC(청산 판정에 필요한 high/low/close만). */
    data class Bar(
        val high: Double,
        val low: Double,
        val close: Double,
    )

    enum class ExitReason { TAKE_PROFIT, STOP_LOSS, TIME_EXIT }

    /**
     * 청산 판정 결과.
     * @param barsFromEntry 진입 봉(offset 0)부터 청산 봉까지의 offset(>= 0). caller 가 실제 index/ts 로 환산.
     * @param exitPrice 체결 가격(SL/TP 는 라인, TIME_EXIT 는 마지막 봉 close).
     * @param reason 청산 사유.
     */
    data class Result(
        val barsFromEntry: Int,
        val exitPrice: Double,
        val reason: ExitReason,
    )

    /**
     * 진입가 기준 % 청산 시뮬.
     *
     * @param entryPrice 진입 체결가(> 0, caller 가드).
     * @param targetProfitPct 익절 트리거 %(양수). target = entryPrice × (1 + pct/100).
     * @param stopLossPct 손절 트리거 %(양수). stop = entryPrice × (1 - pct/100).
     * @param bars 진입 봉부터의 OHLC 시퀀스(offset 0 = 진입 봉). 비어 있으면 null 반환.
     * @param maxHoldBars 최대 보유 봉 수 — offset 0..maxHoldBars 까지 평가(시간청산 상한).
     * @return 청산 [Result], bars 가 비면 null.
     */
    fun simulate(
        entryPrice: Double,
        targetProfitPct: Double,
        stopLossPct: Double,
        bars: List<Bar>,
        maxHoldBars: Int,
    ): Result? {
        if (entryPrice <= 0.0 || bars.isEmpty()) return null

        val targetPrice = entryPrice * (1.0 + targetProfitPct / 100.0)
        val stopPrice = entryPrice * (1.0 - stopLossPct / 100.0)
        val lastOffset = minOf(bars.lastIndex, maxHoldBars)

        for (offset in 0..lastOffset) {
            val bar = bars[offset]
            // SL 우선 — 봉 low 가 stop 이하면 손절 체결(같은 봉 TP 도달 무관).
            if (bar.low <= stopPrice) {
                return Result(offset, stopPrice, ExitReason.STOP_LOSS)
            }
            if (bar.high >= targetPrice) {
                return Result(offset, targetPrice, ExitReason.TAKE_PROFIT)
            }
        }
        // 미도달 — 상한 봉 close 로 시간청산.
        return Result(lastOffset, bars[lastOffset].close, ExitReason.TIME_EXIT)
    }
}
