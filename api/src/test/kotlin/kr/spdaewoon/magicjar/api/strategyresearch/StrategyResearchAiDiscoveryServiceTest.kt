package kr.spdaewoon.magicjar.api.strategyresearch

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Optional

class StrategyResearchAiDiscoveryServiceTest {
    private val instrumentRepository: InstrumentJpaRepository = mock()
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `discover parses claude json and maps candidate to instrument master`() {
        whenever(instrumentRepository.findById("005930")).thenReturn(Optional.of(instrument("005930", "삼성전자")))
        val service =
            StrategyResearchAiDiscoveryService(
                instrumentJpaRepository = instrumentRepository,
                aiTextClient =
                    StrategyResearchAiTextClient {
                        StrategyResearchAiTextResult(
                            provider = "test-claude",
                            text =
                                """
                                ```json
                                {
                                  "items": [
                                    {
                                      "name": "삼성전자",
                                      "code": "005930",
                                      "market": "KOSPI",
                                      "themes": ["반도체", "AI 인프라"],
                                      "reason": "메모리와 AI 서버 수요 후보",
                                      "confidence": 0.83
                                    }
                                  ]
                                }
                                ```
                                """.trimIndent(),
                        )
                    },
                objectMapper = objectMapper,
            )

        val response =
            service.discover(
                StrategyResearchAiDiscoveryRequestDto(
                    userId = "74683223",
                    query = "반도체 후보",
                    market = "KOSPI",
                    limit = 6,
                ),
            )

        response.items shouldHaveSize 1
        response.items[0].code shouldBe "005930"
        response.items[0].name shouldBe "삼성전자"
        response.items[0].matched shouldBe true
        response.items[0].themes shouldBe listOf("반도체", "AI 인프라")
    }

    private fun instrument(
        code: String,
        name: String,
    ): InstrumentJpaEntity =
        InstrumentJpaEntity(
            code = code,
            name = name,
            market = "KOSPI",
            type = "STOCK",
            sector = "전기전자",
            theme = "반도체",
            active = true,
            securitiesGroup = "ST",
        )
}
