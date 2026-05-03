package com.mj.domain.strategy.screener

import com.mj.domain.common.Candle
import com.mj.domain.strategy.dsl.StrategyDsl
import com.mj.domain.strategy.dsl.StrategyDslEvaluator
import java.time.Instant
import java.time.LocalDate

/**
 * 스크리너 엔진 — 순수 도메인 (ADR 0040).
 *
 * 유니버스 내 각 종목의 캔들 시계열에 DSL entry 조건을 적용해 매칭 종목을 수집한다.
 *
 * - AI 호출 0
 * - 상태 0 (순수 함수)
 * - 캔들 로딩은 caller([ScreenerExecutor])가 담당 → 엔진은 평가만
 *
 * @param evaluator  DSL 평가기 (IndicatorEngine 포함)
 */
class ScreenerEngine(
    private val evaluator: StrategyDslEvaluator,
) {
    /**
     * 스크리닝 실행.
     *
     * @param request        스크린 요청
     * @param candlesByCode  code → 캔들 시계열 (시간 오름차순, 200봉 권장)
     * @param nameByCode     code → 한글 종목명
     * @return 스크리닝 결과
     */
    fun screen(
        request: ScreenRequest,
        candlesByCode: Map<String, List<Candle>>,
        nameByCode: Map<String, String>,
    ): ScreenResult {
        val executedAt = Instant.now()
        val matches = mutableListOf<ScreenMatch>()
        var totalScanned = 0
        val skippedCodes = mutableListOf<String>()

        for ((code, candles) in candlesByCode) {
            totalScanned++
            if (candles.isEmpty()) {
                skippedCodes += code
                continue
            }

            val result =
                runCatching {
                    evaluator.evaluate(
                        dsl = request.dsl,
                        candles = candles,
                        currentIndex = candles.lastIndex,
                    )
                }.getOrNull()

            if (result == null) {
                skippedCodes += code
                continue
            }

            if (result.entry) {
                val name = nameByCode[code] ?: code
                matches +=
                    ScreenMatch(
                        code = code,
                        name = name,
                        entryReasons = result.entryReasons,
                        matchedAt = result.evaluatedAt,
                    )
            }
        }

        return ScreenResult(
            matches = matches.toList(),
            executedAt = executedAt,
            totalScanned = totalScanned,
            skippedCodes = skippedCodes.toList(),
            asOfDate = request.asOfDate,
        )
    }
}

/**
 * 스크리닝 요청.
 *
 * @property universe  대상 유니버스
 * @property dsl       평가할 전략 DSL
 * @property asOfDate  기준일 (default = 오늘). 백테스트용으로 과거 날짜도 허용
 */
data class ScreenRequest(
    val universe: Universe,
    val dsl: StrategyDsl,
    val asOfDate: LocalDate = LocalDate.now(),
)

/**
 * 스크리닝 결과.
 *
 * @property matches       entry 조건을 만족한 종목 목록
 * @property executedAt    실행 시각
 * @property totalScanned  실제 평가한 종목 수 (캔들 부재로 스킵된 종목 포함)
 * @property skippedCodes  평가 불가(캔들 없음·지표 계산 오류) 종목 코드 목록
 * @property asOfDate      기준일
 */
data class ScreenResult(
    val matches: List<ScreenMatch>,
    val executedAt: Instant,
    val totalScanned: Int,
    val skippedCodes: List<String>,
    val asOfDate: LocalDate,
) {
    val matchCount: Int get() = matches.size
    val skipCount: Int get() = skippedCodes.size
}
