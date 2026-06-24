package kr.spdaewoon.magicjar.domain.market.candle

import java.math.BigDecimal

/**
 * 캔들 정합성 신뢰 등급 — ADR 0132 §D3-2 (mock 절대 금지 LOCK).
 *
 * backfill 후 기대 봉 수 대비 실제 봉 수(결측률)로 백테스트 결과의 신뢰도를 등급화한다. 결측 봉을 직전봉 복제·
 * 선형보간 등으로 **절대 메우지 않고**(`feedback_no_mock_data`), 등급으로만 정직하게 노출한다.
 */
enum class CandleConfidence {
    /** 결측률 ≤ 5% — 정상 백테스트. */
    OK,

    /** 5% < 결측률 ≤ 20% — 진행하되 결과에 신뢰도 경고 배지 동봉. */
    LOW_CONFIDENCE,

    /** 결측률 > 20% 또는 최소 거래일 미달 — 종목 skip(전부면 Aborted). */
    INSUFFICIENT_DATA,
}

/**
 * 종목 × interval 캔들 정합성 보고 — ADR 0132 §D3-1 (기대/실제 봉수·결측률·등급).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지(헥사고날 정합).
 *
 * @param code 종목코드.
 * @param interval 캔들 interval 코드("1m"·"3m"·"5m" 등).
 * @param expectedBars 기대 봉 수 — interval별 거래일당 bar 수 × 거래일(candle-data-policy §3-B 근사). >= 0.
 * @param actualBars DB 에 존재하는 실제 봉 수. >= 0.
 * @param missingBars 결측 봉 수 = max(0, expectedBars - actualBars).
 * @param missingRatio 결측률 = missingBars / expectedBars (expectedBars=0 이면 0.0). 0.0~1.0.
 * @param confidence 판정 등급([CandleConfidence]).
 * @param tradingDaysCovered 보고 범위에 포함된 거래일 수(근사).
 * @param lookbackClamped 요청 from 이 KIS 1m lookback bound(최대 1년·ENV)보다 과거여서 clamp 됐는지(거짓 결과 차단 경고).
 */
data class CandleIntegrityReport(
    val code: String,
    val interval: String,
    val expectedBars: Int,
    val actualBars: Int,
    val missingBars: Int,
    val missingRatio: BigDecimal,
    val confidence: CandleConfidence,
    val tradingDaysCovered: Int,
    val lookbackClamped: Boolean = false,
) {
    init {
        require(expectedBars >= 0) { "expectedBars must be >= 0: $expectedBars" }
        require(actualBars >= 0) { "actualBars must be >= 0: $actualBars" }
        require(missingBars >= 0) { "missingBars must be >= 0: $missingBars" }
        require(missingRatio >= BigDecimal.ZERO) { "missingRatio must be >= 0: $missingRatio" }
        require(tradingDaysCovered >= 0) { "tradingDaysCovered must be >= 0: $tradingDaysCovered" }
    }

    companion object {
        /** 결측률 LOW_CONFIDENCE 임계(>5% 결측) — ADR 0132 §D3-2(ENV 조정 가능 default). */
        val LOW_CONFIDENCE_THRESHOLD: BigDecimal = BigDecimal("0.05")

        /** 결측률 INSUFFICIENT_DATA 임계(>20% 결측 → skip) — ADR 0132 §D3-2(ENV 조정 가능 default). */
        val INSUFFICIENT_THRESHOLD: BigDecimal = BigDecimal("0.20")

        /**
         * 결측률(0.0~1.0)을 등급으로 판정 — 임계는 default 또는 caller(ENV) 주입값.
         *
         * @param missingRatio 결측률(>= 0).
         * @param lowThreshold LOW 경계(초과 시 LOW). default 5%.
         * @param insufficientThreshold INSUFFICIENT 경계(초과 시 skip). default 20%.
         */
        fun classify(
            missingRatio: BigDecimal,
            lowThreshold: BigDecimal = LOW_CONFIDENCE_THRESHOLD,
            insufficientThreshold: BigDecimal = INSUFFICIENT_THRESHOLD,
        ): CandleConfidence =
            when {
                missingRatio > insufficientThreshold -> CandleConfidence.INSUFFICIENT_DATA
                missingRatio > lowThreshold -> CandleConfidence.LOW_CONFIDENCE
                else -> CandleConfidence.OK
            }
    }
}
