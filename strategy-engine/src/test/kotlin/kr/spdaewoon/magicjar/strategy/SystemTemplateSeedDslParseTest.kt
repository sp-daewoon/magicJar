package kr.spdaewoon.magicjar.strategy

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * V89 SYSTEM 전략 템플릿 시드 DSL 파싱 검증 — 프론트-백엔드 직렬화 동치 확인 (plan Task 5 LOCK).
 *
 * frontend serializer(serializeBlockTree)로 채취해 `V89__seed_system_strategy_templates.sql`에 박은
 * entry/exit DSL 10건이 백엔드 [KisYamlDslParser]로 빠짐없이 파싱되는지 검증한다.
 * 한 건이라도 파싱 실패하면 시드 행이 빌더 로드 시 깨지므로 — 시드 작성 시점에 동치를 차단한다(spec §11 리스크).
 *
 * 본 테스트 입력은 V89 SQL과 1:1 동일해야 하며, 시드 변경 시 함께 갱신한다.
 */
class SystemTemplateSeedDslParseTest {
    private val parser =
        KisYamlDslParser(
            astDepthLimit = 32,
            nodeCountLimit = 10000,
            indicatorCallLimit = 50,
        )

    @ParameterizedTest(name = "[{index}] entry `{0}` parses")
    @CsvSource(
        delimiter = '|',
        value = [
            "(fvg_bullish_active(50) == 1) AND (close > fvg_bullish_mid(50))",
            "(ob_bullish_active(20, 20, 50) == 1) AND (close > ob_bullish_mid(20, 20, 50))",
            "(fvg_bearish_active(50) == 1) AND (close < fvg_bearish_mid(50))",
            "(ob_bearish_active(20, 20, 50) == 1) AND (close < ob_bearish_mid(20, 20, 50))",
            "(ob_bullish_active(20, 20, 100, engulfing, body) == 1) AND (close > ob_bullish_mid(20, 20, 100, engulfing, body))",
            "(ob_bearish_active(20, 20, 100, engulfing, body) == 1) AND (close < ob_bearish_mid(20, 20, 100, engulfing, body))",
            "(((close > ma(50)) AND (ma(50) > ma(150))) AND (ma(150) > ma(200))) AND (close > ma(200))",
            "((close >= donchian_upper(252) * 0.85) AND (close > ma(50))) AND (ma(50) > ma(150))",
            "(((close > ma(50)) AND (ma(20) > ma(50))) AND (natr(14) < natr(50))) AND (volume < volume_ma(20))",
            "(((close >= donchian_upper(20)) AND (volume > volume_ma(20) * 1.5)) AND (close > ma(50))) AND (ma(20) > ma(50))",
        ],
    )
    @DisplayName("V89 시드 entry DSL 10건 모두 백엔드 파서로 파싱된다")
    fun `seed entry DSL parses on backend parser`(entry: String) {
        val ast = parser.parse(entry)
        assertNotNull(ast)
        parser.validateStatic(ast)
    }

    @ParameterizedTest(name = "[{index}] exit `{0}` parses")
    @CsvSource(
        delimiter = '|',
        value = [
            "close < fvg_bullish_bottom(50)",
            "close < ob_bullish_bottom(20, 20, 50)",
            "close > fvg_bearish_top(50)",
            "close > ob_bearish_top(20, 20, 50)",
            "close < ob_bullish_bottom(20, 20, 100, engulfing, body)",
            "close > ob_bearish_top(20, 20, 100, engulfing, body)",
            "close < ma(50)",
            "close < ma(50)",
            "close < ma(20)",
            "close < donchian_lower(10)",
        ],
    )
    @DisplayName("V89 시드 exit DSL 10건 모두 백엔드 파서로 파싱된다")
    fun `seed exit DSL parses on backend parser`(exit: String) {
        val ast = parser.parse(exit)
        assertNotNull(ast)
        parser.validateStatic(ast)
    }
}
