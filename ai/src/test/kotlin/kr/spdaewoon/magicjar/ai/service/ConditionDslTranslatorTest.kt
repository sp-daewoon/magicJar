package kr.spdaewoon.magicjar.ai.service

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.ai.prompt.PromptLoader
import kr.spdaewoon.magicjar.core.port.AiClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ConditionDslTranslatorTest {
    private val promptLoader =
        mockk<PromptLoader> {
            every { load(any()) } returns "SYSTEM"
        }
    private val aiClient = mockk<AiClient>()
    private val translator = ConditionDslTranslator(aiClient, promptLoader)

    @Test
    fun `빈 입력은 empty`() {
        translator.translate("").isPresent shouldBe false
        translator.translate("   ").isPresent shouldBe false
    }

    @ParameterizedTest
    @CsvSource(
        "RSI 30 밑에서 거래량 평소보다 1.5배 많을 때 매수|RSI(14) < 30 AND VOLUME > AVG_VOLUME(20) * 1.5|RSI",
        "골든크로스|SMA(CLOSE,5) > SMA(CLOSE,20) AND PREV(SMA(CLOSE,5),1) <= PREV(SMA(CLOSE,20),1)|SMA",
        "볼린저 밴드 하단 터치|PRICE <= BB_LOWER(20,2)|BB_LOWER",
        "MACD 히스토그램 양전환|MACD_HIST() > 0 AND PREV(MACD_HIST(),1) <= 0|MACD_HIST",
        "5일 이동평균선 위에서 돌파|CLOSE > SMA(CLOSE,5)|SMA",
        delimiterString = "|",
    )
    fun `자연어 질의에서 핵심 키워드가 DSL에 포함된다`(
        query: String,
        mockedDsl: String,
        keyword: String,
    ) {
        val mockJson =
            """
            {"dsl": "$mockedDsl", "confidence": 0.82, "ambiguities": [], "warnings": [], "reasoning": "test"}
            """.trimIndent()
        every { aiClient.chatJson(any(), any(), any()) } returns mockJson
        val result = translator.translate(query).get()
        result.dsl shouldContain keyword
        result.parsingFailed shouldBe false
    }

    @Test
    fun `잘못된 JSON은 parsingFailed true`() {
        every { aiClient.chatJson(any(), any(), any()) } returns "{{broken"
        val result = translator.translate("anything").get()
        result.parsingFailed.shouldBeTrue()
        result.warnings.contains("parsing_failed") shouldBe true
    }

    @Test
    fun `null 응답은 Optional empty`() {
        every { aiClient.chatJson(any(), any(), any()) } returns null
        translator.translate("anything").isPresent shouldBe false
    }
}
