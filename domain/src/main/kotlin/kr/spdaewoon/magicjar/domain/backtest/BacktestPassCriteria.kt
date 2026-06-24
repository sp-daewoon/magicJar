package kr.spdaewoon.magicjar.domain.backtest

import kr.spdaewoon.magicjar.domain.market.candle.CandleConfidence
import java.math.BigDecimal

/**
 * autopilot 전략 ACTIVE 검증 게이트 합격 기준 — ADR 0133 §3-B1 (#5 · 값객체 LOCK).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지(헥사고날 정합).
 *
 * owner=AUTOPILOT 전략을 ACTIVE 로 승격(자본 노출)하기 전, 그 전략 룰로 INTRADAY_REALISTIC 백테스트(ADR 0132)를
 * 돌려 본 기준 **전부(AND)** 를 만족할 때만 PASS. 하나라도 미달이면 FAIL(+사유). 임계치는
 * `magicjar.autopilot.activation-gate.*` ENV 단일 출처에서 주입(drift 0 — R2b).
 *
 * ## 기준 (AND 결합 · ADR 0133 §3-B1)
 * | 기준 | default | 사유 |
 * |---|---|---|
 * | [minTrades] | 20 | 표본 부족 시 승률 우연 → [ActivationFailReason.INSUFFICIENT_SAMPLE]. |
 * | [minWinRate] | 0.40 | 손익비(R:R) 승부 단타 — 고승률 요구는 과적합 유인. 기대값과 AND. |
 * | [minExpectancy] | 0 | ★ 핵심 — net(수수료·세금·슬리피지 차감) 1거래당 순기대 수익 > 0 이 진짜 알파. |
 * | [maxMdd] | 0.15 | MTM equity 곡선 기준 15% — 자본 안전. |
 * | [requireConfidence] | OK | ★ ADR 0132 정합 — 결측률 높으면 결과 자체 신뢰 불가. mock 보간 0 위에서만 판정. |
 *
 * ## 진보 페르소나 결정 메모 (ADR 0087)
 * - 💡 임계치를 단일 불변 값객체로 응집 → 평가기(R2b)가 boolean 곱을 직접 안 하고 본 객체에 위임(DRY).
 * - ✨ 합격/미달 판정을 [evaluate] 단일 함수로 — caller 가 임계치 비교 로직 중복 작성 0(SRP).
 *
 * @param minTrades 최소 거래수(이상). 미달 시 INSUFFICIENT_SAMPLE.
 * @param minWinRate 최소 승률(이상). 0.0~1.0.
 * @param minExpectancy 최소 net 기대값(초과 — 1거래당 순기대 수익 > 이 값). default 0(양수 요구).
 * @param maxMdd 최대 MDD(이하). 양수값(0.15 = 15%).
 * @param requireConfidence 최소 정합성 등급. 본 등급보다 낮으면(결측률 큼) PASS 불가.
 */
data class BacktestPassCriteria(
    val minTrades: Int = DEFAULT_MIN_TRADES,
    val minWinRate: BigDecimal = DEFAULT_MIN_WIN_RATE,
    val minExpectancy: BigDecimal = DEFAULT_MIN_EXPECTANCY,
    val maxMdd: BigDecimal = DEFAULT_MAX_MDD,
    val requireConfidence: CandleConfidence = DEFAULT_REQUIRE_CONFIDENCE,
) {
    init {
        require(minTrades >= 0) { "minTrades must be >= 0: $minTrades" }
        require(minWinRate >= BigDecimal.ZERO && minWinRate <= BigDecimal.ONE) {
            "minWinRate must be in [0,1]: $minWinRate"
        }
        require(maxMdd >= BigDecimal.ZERO) { "maxMdd must be >= 0: $maxMdd" }
    }

    /**
     * 측정 메트릭을 본 기준과 대조해 PASS/FAIL 판정 — ADR 0133 §3-B1 AND 결합.
     *
     * 미달 사유는 **전부 수집**(첫 미달에서 중단하지 않음 — 운영 가시성: 어떤 기준이 몇 개 깨졌는지 한 번에 노출).
     * 백테스트 자체가 산출 불가(에러)거나 결측률 과다(INSUFFICIENT_DATA) 인 경우는 caller(평가기 R2b)가
     * [BacktestPassResult.insufficientData]/[BacktestPassResult.error] 팩토리로 보수적 FAIL 처리한다
     * (거짓 PASS 0 — fail-safe).
     *
     * @param metrics 측정된 백테스트 메트릭(거래수·승률·net 기대값·MDD·정합성 등급).
     * @return PASS(미달 0건) 또는 FAIL(미달 사유 리스트 동봉).
     */
    fun evaluate(metrics: BacktestPassMetrics): BacktestPassResult {
        val reasons =
            buildList {
                if (metrics.trades < minTrades) add(ActivationFailReason.INSUFFICIENT_SAMPLE)
                if (metrics.winRate < minWinRate) add(ActivationFailReason.LOW_WIN_RATE)
                // net expectancy 는 minExpectancy 초과(>)여야 통과 — default 0 이면 "양수" 요구.
                if (metrics.netExpectancy <= minExpectancy) add(ActivationFailReason.NEGATIVE_EXPECTANCY)
                if (metrics.maxDrawdown > maxMdd) add(ActivationFailReason.MDD_EXCEEDED)
                // 정합성 등급은 낮을수록 ordinal 이 큼(OK=0 < LOW_CONFIDENCE=1 < INSUFFICIENT_DATA=2).
                if (metrics.confidence == CandleConfidence.INSUFFICIENT_DATA) {
                    add(ActivationFailReason.INSUFFICIENT_DATA)
                } else if (metrics.confidence.ordinal > requireConfidence.ordinal) {
                    add(ActivationFailReason.LOW_CONFIDENCE)
                }
            }
        return if (reasons.isEmpty()) {
            BacktestPassResult(AutopilotActivationVerdict.PASS, emptyList(), metrics)
        } else {
            BacktestPassResult(AutopilotActivationVerdict.FAIL, reasons, metrics)
        }
    }

    companion object {
        const val DEFAULT_MIN_TRADES: Int = 20
        val DEFAULT_MIN_WIN_RATE: BigDecimal = BigDecimal("0.40")
        val DEFAULT_MIN_EXPECTANCY: BigDecimal = BigDecimal.ZERO
        val DEFAULT_MAX_MDD: BigDecimal = BigDecimal("0.15")
        val DEFAULT_REQUIRE_CONFIDENCE: CandleConfidence = CandleConfidence.OK
    }
}
