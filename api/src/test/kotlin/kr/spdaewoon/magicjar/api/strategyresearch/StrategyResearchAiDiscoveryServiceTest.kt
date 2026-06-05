package kr.spdaewoon.magicjar.api.strategyresearch

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional

class StrategyResearchAiDiscoveryServiceTest {
    private val instrumentRepository: InstrumentJpaRepository = mock()
    private val stateRepository: StrategyResearchAiDiscoveryStateJpaRepository = mock()
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `discover parses claude json and maps candidate to instrument master`() {
        whenever(instrumentRepository.findById("005930")).thenReturn(Optional.of(instrument("005930", "삼성전자")))
        whenever(stateRepository.findById("74683223")).thenReturn(Optional.empty())
        whenever(stateRepository.save(any())).thenAnswer { it.arguments[0] }
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
                stateRepository = stateRepository,
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
        response.items[0].itemKey shouldBe "CODE:005930"
        response.items[0].code shouldBe "005930"
        response.items[0].name shouldBe "삼성전자"
        response.items[0].matched shouldBe true
        response.items[0].themes shouldBe listOf("반도체", "AI 인프라")
    }

    @Test
    fun `discover keeps pinned items and filters dismissed items on rediscovery`() {
        whenever(instrumentRepository.findById("005930")).thenReturn(Optional.of(instrument("005930", "삼성전자")))
        whenever(instrumentRepository.findById("000660")).thenReturn(Optional.of(instrument("000660", "SK하이닉스")))
        whenever(instrumentRepository.findById("042700")).thenReturn(Optional.of(instrument("042700", "한미반도체")))
        val previous =
            StrategyResearchAiDiscoveryStateJpaEntity(
                userId = "74683223",
                query = "반도체 후보",
                market = "KOSPI",
                candidateLimit = 2,
                provider = "test-claude",
                generatedAt = Instant.parse("2026-06-05T00:00:00Z"),
                warningsJson = "[]",
                itemsJson =
                    objectMapper.writeValueAsString(
                        listOf(
                            item(code = "005930", name = "삼성전자", status = StrategyResearchAiDiscoveryItemStatus.KEPT),
                            item(code = "000660", name = "SK하이닉스", status = StrategyResearchAiDiscoveryItemStatus.DISMISSED),
                        ),
                    ),
            )
        whenever(stateRepository.findById("74683223")).thenReturn(Optional.of(previous))
        whenever(stateRepository.save(any())).thenAnswer { it.arguments[0] }

        val service =
            StrategyResearchAiDiscoveryService(
                instrumentJpaRepository = instrumentRepository,
                aiTextClient =
                    StrategyResearchAiTextClient {
                        StrategyResearchAiTextResult(
                            provider = "test-claude",
                            text =
                                """
                                {
                                  "items": [
                                    {"name": "SK하이닉스", "code": "000660", "market": "KOSPI", "themes": ["HBM"], "reason": "제외했던 후보", "confidence": 0.91},
                                    {"name": "한미반도체", "code": "042700", "market": "KOSPI", "themes": ["HBM 장비"], "reason": "신규 후보", "confidence": 0.8}
                                  ]
                                }
                                """.trimIndent(),
                        )
                    },
                objectMapper = objectMapper,
                stateRepository = stateRepository,
            )

        val response =
            service.discover(
                StrategyResearchAiDiscoveryRequestDto(
                    userId = "74683223",
                    query = "반도체 후보",
                    market = "KOSPI",
                    limit = 2,
                ),
            )

        response.items.filter { it.status != StrategyResearchAiDiscoveryItemStatus.DISMISSED }.map { it.code } shouldBe
            listOf("005930", "042700")
        response.items.first { it.code == "005930" }.status shouldBe StrategyResearchAiDiscoveryItemStatus.KEPT
        response.items.first { it.code == "000660" }.status shouldBe StrategyResearchAiDiscoveryItemStatus.DISMISSED
    }

    private fun item(
        code: String,
        name: String,
        status: StrategyResearchAiDiscoveryItemStatus,
    ): StrategyResearchAiDiscoveryItemDto =
        StrategyResearchAiDiscoveryItemDto(
            itemKey = "CODE:$code",
            code = code,
            name = name,
            market = "KOSPI",
            sector = "전기전자",
            theme = "반도체",
            aiReason = "이전 후보",
            themes = listOf("반도체"),
            confidence = 0.8,
            matched = true,
            rawName = name,
            rawCode = code,
            tradingHalt = false,
            managementStock = false,
            status = status,
        )

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
