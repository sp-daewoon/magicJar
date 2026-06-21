package kr.spdaewoon.magicjar.strategy.autopilot

import kr.spdaewoon.magicjar.domain.strategy.dsl.ArithmeticExpr
import kr.spdaewoon.magicjar.domain.strategy.dsl.CompositeCondition
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.strategy.DslIdentifierMapper
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * magicjar-autopilot Phase 1 첫 전략 세트(6종) DSL 검증 — `scripts/autopilot-seed-strategies.mjs` 와 1:1 동치.
 *
 * **실돈 단타 안전 의무 (spec 2026-06-22 §학습 조건 F·결정론)**:
 * 1. [KisYamlDslParser.parse] 로 entry/exit 6쌍이 모두 파싱된다.
 * 2. [KisYamlDslParser.validateStatic] 화이트리스트·복잡도 통과.
 * 3. **★ 평가 시 폭발 방지** — AST 의 모든 [IndicatorRef] 가 [DslIdentifierMapper.toIndicatorSpec] 로
 *    실제 IndicatorEngine 지표로 매핑된다(=실행 가능). 파서 화이트리스트(ALLOWED_IDENTIFIERS)에는 들어있지만
 *    엔진 미등록인 4종(`highest`·`lowest`·`change`·`returns`)·패턴 함수(`pattern`·`consecutive`·`pattern_detected`)
 *    는 라이브 평가 시 [kr.spdaewoon.magicjar.domain.strategy.StrategyException.UnknownDslSymbolException] 으로
 *    터지므로 autopilot 시드는 **이 함정 식별자를 절대 쓰지 않는다**. 본 테스트가 시드 작성 시점에 차단한다.
 *
 * 시드 DSL 변경 시 이 테스트의 CsvSource 와 `scripts/autopilot-seed-strategies.mjs` 의 STRATEGIES 를 함께 갱신한다.
 */
class AutopilotSeedStrategyDslTest {
    private val parser =
        KisYamlDslParser(
            astDepthLimit = 32,
            nodeCountLimit = 10000,
            indicatorCallLimit = 50,
        )
    private val mapper = DslIdentifierMapper()

    /** 캔들 직접 접근 6종 — IndicatorRef 이지만 IndicatorEngine 매핑 대상 아님(JvmStrategyEvaluator.directCandleAccess). */
    private val candleAccessors = setOf("open", "close", "prev_close", "high", "low", "volume")

    @ParameterizedTest(name = "[{index}] autopilot entry `{0}`")
    @CsvSource(
        delimiter = '|',
        value = [
            // 1. 모멘텀 돌파 (donchian N봉 고가 돌파 + 거래량 + VWAP 상단)
            "((close breaks donchian_upper(20)) AND (volume > volume_ma(20) * 1.5)) AND (close > vwap())",
            // 2. 눌림목 반등 (상승추세 + RSI 과매도 탈출 + ma20 위 재진입)
            "(((close > ma(50)) AND (ma(20) > ma(50))) AND (rsi(14) > 40)) AND (close > ma(20))",
            // 3. FVG bullish reclaim (AUTOPILOT 인스턴스)
            "(fvg_bullish_active(50) == 1) AND (close > fvg_bullish_mid(50))",
            // 4. Order-block bullish retest (AUTOPILOT 인스턴스)
            "(ob_bullish_active(20, 20, 50) == 1) AND (close > ob_bullish_mid(20, 20, 50))",
            // 5. 거래량 급증(RVOL) spike — 거래량 2배 폭증 + 자금유입(MFI) + VWAP 위
            "((volume > volume_ma(20) * 2) AND (mfi(14) > 55)) AND (close > vwap())",
            // 6. VWAP 회귀 (BB 하단 이탈 후 RSI 과매도에서 되돌림 매수)
            "((close < bb_lower(20)) AND (rsi(14) < 30)) AND (close < vwap())",
        ],
    )
    @DisplayName("autopilot 시드 entry DSL 6건 — 파싱 + 정적검증 + 엔진 매핑 통과")
    fun `autopilot entry DSL parses validates and maps`(entry: String) {
        val ast = parser.parse(entry)
        assertNotNull(ast)
        parser.validateStatic(ast)
        assertAllIndicatorRefsMapToEngine(ast)
    }

    @ParameterizedTest(name = "[{index}] autopilot exit `{0}`")
    @CsvSource(
        delimiter = '|',
        value = [
            // 1. 돌파 실패 — VWAP 하향 이탈
            "close < vwap()",
            // 2. 눌림목 무효 — ma20 하향 이탈
            "close < ma(20)",
            // 3. FVG 무효 — gap 하단 이탈
            "close < fvg_bullish_bottom(50)",
            // 4. OB 무효 — block 하단 이탈
            "close < ob_bullish_bottom(20, 20, 50)",
            // 5. spike 소진 — VWAP 하향 이탈
            "close < vwap()",
            // 6. 회귀 달성 — VWAP 복귀(목표 도달)
            "close > vwap()",
        ],
    )
    @DisplayName("autopilot 시드 exit DSL 6건 — 파싱 + 정적검증 + 엔진 매핑 통과")
    fun `autopilot exit DSL parses validates and maps`(exit: String) {
        val ast = parser.parse(exit)
        assertNotNull(ast)
        parser.validateStatic(ast)
        assertAllIndicatorRefsMapToEngine(ast)
    }

    /**
     * AST 의 모든 [IndicatorRef] 가 실제 엔진 지표로 매핑 가능한지(=라이브 평가 가능) 검증.
     * 캔들 직접 접근 6종은 매핑 대상이 아니므로 스킵. 그 외에서 [DslIdentifierMapper.toIndicatorSpec] 가
     * 예외를 던지면(=엔진 미등록 식별자) 테스트 실패 → 시드 발행 차단.
     */
    private fun assertAllIndicatorRefsMapToEngine(node: DslNode) {
        when (node) {
            is IndicatorRef -> {
                if (node.name !in candleAccessors) {
                    // 예외 발생 시 테스트 실패(=발행 금지). 정상 매핑이면 통과.
                    mapper.toIndicatorSpec(node)
                }
            }
            is Condition -> {
                assertAllIndicatorRefsMapToEngine(node.left)
                assertAllIndicatorRefsMapToEngine(node.right)
            }
            is CompositeCondition -> {
                assertAllIndicatorRefsMapToEngine(node.left)
                assertAllIndicatorRefsMapToEngine(node.right)
            }
            is ArithmeticExpr -> {
                assertAllIndicatorRefsMapToEngine(node.left)
                assertAllIndicatorRefsMapToEngine(node.right)
            }
            else -> {} // LiteralValue 등 leaf
        }
    }
}
