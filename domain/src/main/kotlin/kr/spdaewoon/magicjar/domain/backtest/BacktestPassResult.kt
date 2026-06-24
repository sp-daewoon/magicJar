package kr.spdaewoon.magicjar.domain.backtest

import kr.spdaewoon.magicjar.domain.market.candle.CandleConfidence
import java.math.BigDecimal

/**
 * 검증 게이트 입력 메트릭 — ADR 0133 §3-B1 ([BacktestPassCriteria.evaluate] 대조 대상).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지(헥사고날 정합).
 *
 * 백테스트 성과([BacktestPerformance]) + 정합성([CandleConfidence]) 에서 게이트 판정에 필요한 5개 메트릭만 추린
 * 순수 값객체. net expectancy 는 비용(수수료·세금·슬리피지) 차감 후 1거래당 순기대 수익으로, 평가기(R2b)가
 * 백테스트 결과에서 산출해 주입한다([fromWinLoss] 헬퍼로 avgWin/avgLoss/winRate 합성도 가능).
 *
 * @param trades 총 거래 수(매수+매도 쌍).
 * @param winRate 승률(0.0~1.0).
 * @param netExpectancy net 1거래당 순기대 수익(KRW · 비용 차감 후). 음수 가능.
 * @param maxDrawdown MDD(양수값 · 0.15 = 15% · MTM equity 기준).
 * @param confidence 정합성 등급(결측률 기반).
 */
data class BacktestPassMetrics(
    val trades: Int,
    val winRate: BigDecimal,
    val netExpectancy: BigDecimal,
    val maxDrawdown: BigDecimal,
    val confidence: CandleConfidence,
) {
    init {
        require(trades >= 0) { "trades must be >= 0: $trades" }
        require(winRate >= BigDecimal.ZERO) { "winRate must be >= 0: $winRate" }
        require(maxDrawdown >= BigDecimal.ZERO) { "maxDrawdown must be >= 0: $maxDrawdown" }
    }

    companion object {
        /**
         * 승률·평균손익에서 net expectancy 합성 — E = winRate·avgWin − (1−winRate)·avgLoss.
         *
         * [avgWin]/[avgLoss] 는 비용(수수료·세금·슬리피지) 차감 후 net 금액(둘 다 양수). ADR 0132 비용 모델이
         * 백테스트 거래에 이미 반영돼 있으므로 그 평균을 그대로 넣으면 net expectancy 가 된다.
         *
         * @param trades 총 거래 수.
         * @param winRate 승률(0.0~1.0).
         * @param avgWin 평균 이익(net · 양수 · KRW).
         * @param avgLoss 평균 손실(net · 양수 절대값 · KRW).
         * @param maxDrawdown MDD.
         * @param confidence 정합성 등급.
         */
        fun fromWinLoss(
            trades: Int,
            winRate: BigDecimal,
            avgWin: BigDecimal,
            avgLoss: BigDecimal,
            maxDrawdown: BigDecimal,
            confidence: CandleConfidence,
        ): BacktestPassMetrics {
            val lossRate = BigDecimal.ONE.subtract(winRate)
            val netExpectancy =
                winRate.multiply(avgWin).subtract(lossRate.multiply(avgLoss))
            return BacktestPassMetrics(
                trades = trades,
                winRate = winRate,
                netExpectancy = netExpectancy,
                maxDrawdown = maxDrawdown,
                confidence = confidence,
            )
        }
    }
}

/**
 * autopilot 검증 게이트 판정 결과 — ADR 0133 §3-B1 (verdict + 미달 사유 + 측정 메트릭 echo).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지(헥사고날 정합).
 *
 * @param verdict PASS/FAIL.
 * @param failReasons 미달 사유 코드(PASS 면 빈 리스트).
 * @param metrics 판정 근거가 된 측정 메트릭 echo(운영 가시성·SystemEvent·배지). 데이터 부족/에러 fail-safe 시 null.
 */
data class BacktestPassResult(
    val verdict: AutopilotActivationVerdict,
    val failReasons: List<ActivationFailReason>,
    val metrics: BacktestPassMetrics?,
) {
    init {
        require(verdict == AutopilotActivationVerdict.FAIL || failReasons.isEmpty()) {
            "PASS verdict must have empty failReasons: $failReasons"
        }
        require(verdict == AutopilotActivationVerdict.PASS || failReasons.isNotEmpty()) {
            "FAIL verdict must have at least one failReason"
        }
    }

    val passed: Boolean get() = verdict == AutopilotActivationVerdict.PASS

    companion object {
        /**
         * 결측률 과다(INSUFFICIENT_DATA) 보수 FAIL — fail-safe(거짓 PASS 0).
         *
         * @param metrics 측정 메트릭(있으면 echo · 없으면 null).
         */
        fun insufficientData(metrics: BacktestPassMetrics? = null): BacktestPassResult =
            BacktestPassResult(
                verdict = AutopilotActivationVerdict.FAIL,
                failReasons = listOf(ActivationFailReason.INSUFFICIENT_DATA),
                metrics = metrics,
            )

        /**
         * 백테스트 실행 에러 보수 FAIL — fail-safe(거짓 PASS 0). 검증 중 예외 시 BUY_BLOCKED 측으로.
         */
        fun error(): BacktestPassResult =
            BacktestPassResult(
                verdict = AutopilotActivationVerdict.FAIL,
                failReasons = listOf(ActivationFailReason.BACKTEST_ERROR),
                metrics = null,
            )
    }
}
