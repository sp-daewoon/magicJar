package kr.spdaewoon.magicjar.api.strategyresearch

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryCorePromptJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryCorePromptJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryPresetJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryPresetJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryStateJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategyresearch.StrategyResearchAiDiscoveryStateJpaRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional

class StrategyResearchAiDiscoveryServiceTest {
    private val instrumentRepository: InstrumentJpaRepository = mock()
    private val candleRepository: CandleJpaRepository = mock()
    private val stateRepository: StrategyResearchAiDiscoveryStateJpaRepository = mock()
    private val presetRepository: StrategyResearchAiDiscoveryPresetJpaRepository = mock()
    private val corePromptRepository: StrategyResearchAiDiscoveryCorePromptJpaRepository = mock()
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `discover parses claude json and maps candidate to instrument master`() {
        whenever(instrumentRepository.findById("005930")).thenReturn(Optional.of(instrument("005930", "삼성전자")))
        whenever(stateRepository.findById("74683223")).thenReturn(Optional.empty())
        whenever(stateRepository.save(any())).thenAnswer { it.arguments[0] }
        val service =
            StrategyResearchAiDiscoveryService(
                instrumentJpaRepository = instrumentRepository,
                candleJpaRepository = candleRepository,
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
                presetRepository = presetRepository,
                corePromptRepository = corePromptRepository,
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
    fun `discover attaches candle availability for selected research window`() {
        val fromTs = Instant.parse("2026-04-28T00:00:00Z")
        val toTs = Instant.parse("2026-06-02T06:30:00Z")
        whenever(instrumentRepository.findById("005930")).thenReturn(Optional.of(instrument("005930", "삼성전자")))
        whenever(candleRepository.countByCodeAndIntervalAndTsBetween(eq("005930"), eq("5m"), eq(fromTs), eq(toTs)))
            .thenReturn(0L)
        whenever(stateRepository.findById("74683223")).thenReturn(Optional.empty())
        whenever(stateRepository.save(any())).thenAnswer { it.arguments[0] }
        val service =
            StrategyResearchAiDiscoveryService(
                instrumentJpaRepository = instrumentRepository,
                candleJpaRepository = candleRepository,
                aiTextClient =
                    StrategyResearchAiTextClient {
                        StrategyResearchAiTextResult(
                            provider = "test-claude",
                            text = """{"items":[{"name":"삼성전자","code":"005930","market":"KOSPI","reason":"AI 후보","confidence":0.8}]}""",
                        )
                    },
                objectMapper = objectMapper,
                stateRepository = stateRepository,
                presetRepository = presetRepository,
                corePromptRepository = corePromptRepository,
            )

        val response =
            service.discover(
                StrategyResearchAiDiscoveryRequestDto(
                    userId = "74683223",
                    query = "반도체 후보",
                    market = "KOSPI",
                    limit = 6,
                    interval = "5m",
                    from = fromTs.toString(),
                    to = toTs.toString(),
                ),
            )

        response.items[0].candleCount shouldBe 0L
        response.items[0].hasResearchCandles shouldBe false
        response.warnings.any { it.contains("candle이 없는 후보 1건") } shouldBe true
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
                candleJpaRepository = candleRepository,
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
                presetRepository = presetRepository,
                corePromptRepository = corePromptRepository,
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

    @Test
    fun `discover skips claude when kept items already fill requested limit`() {
        var calledClaude = false
        val previous =
            StrategyResearchAiDiscoveryStateJpaEntity(
                userId = "74683223",
                query = "반도체 후보",
                market = null,
                candidateLimit = 3,
                provider = "test-claude",
                generatedAt = Instant.parse("2026-06-05T00:00:00Z"),
                warningsJson = "[]",
                itemsJson =
                    objectMapper.writeValueAsString(
                        listOf(
                            item(code = "005930", name = "삼성전자", status = StrategyResearchAiDiscoveryItemStatus.KEPT),
                            item(code = "042700", name = "한미반도체", status = StrategyResearchAiDiscoveryItemStatus.KEPT),
                            item(code = "000660", name = "SK하이닉스", status = StrategyResearchAiDiscoveryItemStatus.DISMISSED),
                        ),
                    ),
            )
        whenever(stateRepository.findById("74683223")).thenReturn(Optional.of(previous))
        whenever(stateRepository.save(any())).thenAnswer { it.arguments[0] }

        val service =
            StrategyResearchAiDiscoveryService(
                instrumentJpaRepository = instrumentRepository,
                candleJpaRepository = candleRepository,
                aiTextClient =
                    StrategyResearchAiTextClient {
                        calledClaude = true
                        StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""")
                    },
                objectMapper = objectMapper,
                stateRepository = stateRepository,
                presetRepository = presetRepository,
                corePromptRepository = corePromptRepository,
            )

        val response =
            service.discover(
                StrategyResearchAiDiscoveryRequestDto(
                    userId = "74683223",
                    query = "핫한 국내주",
                    market = null,
                    limit = 2,
                ),
            )

        calledClaude shouldBe false
        response.provider shouldBe "state-cache"
        response.items.filter { it.status != StrategyResearchAiDiscoveryItemStatus.DISMISSED }.map { it.code } shouldBe
            listOf("005930", "042700")
        response.warnings.any { it.contains("Claude 호출은 건너뛰었습니다") } shouldBe true
    }

    @Test
    fun `discover state-cache path recomputes candle count so stale zero is refreshed`() {
        // 이전 state에는 candle 수집 전이라 candleCount=0(차트 없음)으로 저장돼 있었다.
        val previous =
            StrategyResearchAiDiscoveryStateJpaEntity(
                userId = "74683223",
                query = "반도체 후보",
                market = null,
                candidateLimit = 2,
                provider = "state-cache",
                generatedAt = Instant.parse("2026-06-05T00:00:00Z"),
                warningsJson = "[]",
                itemsJson =
                    objectMapper.writeValueAsString(
                        listOf(
                            item(
                                code = "005930",
                                name = "삼성전자",
                                status = StrategyResearchAiDiscoveryItemStatus.KEPT,
                                candleCount = 0L,
                                hasResearchCandles = false,
                            ),
                            item(
                                code = "042700",
                                name = "한미반도체",
                                status = StrategyResearchAiDiscoveryItemStatus.KEPT,
                                candleCount = 0L,
                                hasResearchCandles = false,
                            ),
                        ),
                    ),
            )
        whenever(stateRepository.findById("74683223")).thenReturn(Optional.of(previous))
        whenever(stateRepository.save(any())).thenAnswer { it.arguments[0] }
        // 사용자가 캔들을 수집 완료 → 이제 두 후보 모두 candle 보유.
        whenever(
            candleRepository.countByCodeAndIntervalAndTsBetween(
                eq("005930"),
                eq("1m"),
                any(),
                any(),
            ),
        ).thenReturn(390L)
        whenever(
            candleRepository.countByCodeAndIntervalAndTsBetween(
                eq("042700"),
                eq("1m"),
                any(),
                any(),
            ),
        ).thenReturn(0L)

        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        val response =
            service.discover(
                StrategyResearchAiDiscoveryRequestDto(
                    userId = "74683223",
                    query = "반도체 후보",
                    market = null,
                    limit = 2,
                    interval = "1m",
                    from = "2026-04-28T00:00:00Z",
                    to = "2026-06-02T06:30:00Z",
                ),
            )

        response.provider shouldBe "state-cache"
        val samsung = response.items.first { it.code == "005930" }
        samsung.candleCount shouldBe 390L
        samsung.hasResearchCandles shouldBe true
        val hanmi = response.items.first { it.code == "042700" }
        hanmi.candleCount shouldBe 0L
        hanmi.hasResearchCandles shouldBe false
        response.warnings.any { it.contains("저장된 candle이 없는 후보") } shouldBe true
    }

    @Test
    fun `discover state-cache path leaves candle count null when interval window absent`() {
        val previous =
            StrategyResearchAiDiscoveryStateJpaEntity(
                userId = "74683223",
                query = "반도체 후보",
                market = null,
                candidateLimit = 1,
                provider = "state-cache",
                generatedAt = Instant.parse("2026-06-05T00:00:00Z"),
                warningsJson = "[]",
                itemsJson =
                    objectMapper.writeValueAsString(
                        listOf(
                            item(code = "005930", name = "삼성전자", status = StrategyResearchAiDiscoveryItemStatus.KEPT),
                        ),
                    ),
            )
        whenever(stateRepository.findById("74683223")).thenReturn(Optional.of(previous))
        whenever(stateRepository.save(any())).thenAnswer { it.arguments[0] }

        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        val response =
            service.discover(
                StrategyResearchAiDiscoveryRequestDto(
                    userId = "74683223",
                    query = "반도체 후보",
                    market = null,
                    limit = 1,
                ),
            )

        response.provider shouldBe "state-cache"
        val samsung = response.items.first { it.code == "005930" }
        samsung.candleCount.shouldBeNull()
        samsung.hasResearchCandles.shouldBeNull()
    }

    // ───────────────────────── core(wrapper) prompt 편집 ─────────────────────────

    @Test
    fun `corePrompt falls back to service default when db row absent`() {
        whenever(corePromptRepository.findById(StrategyResearchAiDiscoveryCorePromptJpaEntity.SINGLETON_ID))
            .thenReturn(Optional.empty())
        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        val dto = service.corePrompt()

        dto.promptText shouldBe StrategyResearchAiDiscoveryService.DEFAULT_CORE_PROMPT
        dto.isDefault shouldBe true
        dto.updatedAt.shouldBeNull()
    }

    @Test
    fun `corePrompt returns db value as authority when row exists`() {
        val edited = "사용자가 편집한 발굴 원칙"
        val now = Instant.parse("2026-06-06T12:00:00Z")
        whenever(corePromptRepository.findById(StrategyResearchAiDiscoveryCorePromptJpaEntity.SINGLETON_ID))
            .thenReturn(Optional.of(StrategyResearchAiDiscoveryCorePromptJpaEntity(promptText = edited, updatedAt = now)))
        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        val dto = service.corePrompt()

        dto.promptText shouldBe edited
        dto.isDefault shouldBe false
        dto.updatedAt shouldBe now.toString()
    }

    @Test
    fun `updateCorePrompt persists trimmed text and reports not default`() {
        whenever(corePromptRepository.findById(StrategyResearchAiDiscoveryCorePromptJpaEntity.SINGLETON_ID))
            .thenReturn(Optional.empty())
        whenever(corePromptRepository.save(any())).thenAnswer { it.arguments[0] }
        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        // save 이후 GET 은 DB 행을 다시 반영해야 하므로 stub 을 갱신한다.
        whenever(corePromptRepository.findById(StrategyResearchAiDiscoveryCorePromptJpaEntity.SINGLETON_ID))
            .thenReturn(Optional.of(StrategyResearchAiDiscoveryCorePromptJpaEntity(promptText = "내 발굴 톤")))

        val dto =
            service.updateCorePrompt(
                StrategyResearchAiDiscoveryCorePromptUpdateRequestDto(promptText = "  내 발굴 톤  "),
            )

        dto.promptText shouldBe "내 발굴 톤"
        dto.isDefault shouldBe false
    }

    @Test
    fun `updateCorePrompt rejects blank text with 400`() {
        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        shouldThrow<ResponseStatusException> {
            service.updateCorePrompt(StrategyResearchAiDiscoveryCorePromptUpdateRequestDto(promptText = "   "))
        }
    }

    @Test
    fun `resetCorePrompt restores service default`() {
        whenever(corePromptRepository.findById(StrategyResearchAiDiscoveryCorePromptJpaEntity.SINGLETON_ID))
            .thenReturn(Optional.of(StrategyResearchAiDiscoveryCorePromptJpaEntity(promptText = "편집본")))
        whenever(corePromptRepository.save(any())).thenAnswer { it.arguments[0] }
        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        // reset 후 GET 은 기본값 행을 반영.
        whenever(corePromptRepository.findById(StrategyResearchAiDiscoveryCorePromptJpaEntity.SINGLETON_ID))
            .thenReturn(
                Optional.of(
                    StrategyResearchAiDiscoveryCorePromptJpaEntity(
                        promptText = StrategyResearchAiDiscoveryService.DEFAULT_CORE_PROMPT,
                    ),
                ),
            )

        val dto = service.resetCorePrompt()

        dto.promptText shouldBe StrategyResearchAiDiscoveryService.DEFAULT_CORE_PROMPT
        dto.isDefault shouldBe true
    }

    @Test
    fun `buildPrompt uses db core prompt as authority`() {
        whenever(corePromptRepository.findById(StrategyResearchAiDiscoveryCorePromptJpaEntity.SINGLETON_ID))
            .thenReturn(Optional.of(StrategyResearchAiDiscoveryCorePromptJpaEntity(promptText = "DB-CORE-MARKER")))
        whenever(stateRepository.findById("74683223")).thenReturn(Optional.empty())
        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        val preview =
            service.previewPrompt(
                StrategyResearchAiDiscoveryPromptPreviewRequestDto(
                    userId = "74683223",
                    query = "반도체 후보",
                    limit = 6,
                ),
            )

        preview.systemPrompt shouldBe "DB-CORE-MARKER"
        preview.finalPrompt.contains("DB-CORE-MARKER") shouldBe true
    }

    // ───────────────────────── query 옵션화 ─────────────────────────

    @Test
    fun `discover allows blank query when valid preset selected`() {
        whenever(presetRepository.findById("scalping_momentum"))
            .thenReturn(Optional.of(preset("scalping_momentum")))
        whenever(instrumentRepository.findById("005930")).thenReturn(Optional.of(instrument("005930", "삼성전자")))
        whenever(stateRepository.findById("74683223")).thenReturn(Optional.empty())
        whenever(stateRepository.save(any())).thenAnswer { it.arguments[0] }
        val service =
            newService {
                StrategyResearchAiTextResult(
                    provider = "test-claude",
                    text = """{"items":[{"name":"삼성전자","code":"005930","market":"KOSPI","reason":"AI 후보","confidence":0.8}]}""",
                )
            }

        val response =
            service.discover(
                StrategyResearchAiDiscoveryRequestDto(
                    userId = "74683223",
                    query = null,
                    presetId = "scalping_momentum",
                    limit = 6,
                ),
            )

        response.items shouldHaveSize 1
        response.presetId shouldBe "scalping_momentum"
        response.query.shouldBeNull()
    }

    @Test
    fun `discover rejects blank query without preset as 400`() {
        whenever(stateRepository.findById("74683223")).thenReturn(Optional.empty())
        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        shouldThrow<ResponseStatusException> {
            service.discover(
                StrategyResearchAiDiscoveryRequestDto(
                    userId = "74683223",
                    query = null,
                    presetId = null,
                    limit = 6,
                ),
            )
        }
    }

    @Test
    fun `buildPrompt replaces empty user input with preset-driven placeholder`() {
        whenever(presetRepository.findById("scalping_momentum"))
            .thenReturn(Optional.of(preset("scalping_momentum")))
        whenever(stateRepository.findById("74683223")).thenReturn(Optional.empty())
        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        val preview =
            service.previewPrompt(
                StrategyResearchAiDiscoveryPromptPreviewRequestDto(
                    userId = "74683223",
                    query = null,
                    presetId = "scalping_momentum",
                    limit = 6,
                ),
            )

        preview.finalPrompt.contains("없음. 프리셋 지시문을 기준으로 후보를 제안한다.") shouldBe true
        preview.userQuery.shouldBeNull()
    }

    // ───────────────────────── 프리셋 삭제 ─────────────────────────

    @Test
    fun `deletePreset removes existing preset including builtin`() {
        whenever(presetRepository.existsById("scalping_momentum")).thenReturn(true)
        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        service.deletePreset("scalping_momentum")

        org.mockito.kotlin
            .verify(presetRepository)
            .deleteById("scalping_momentum")
    }

    @Test
    fun `deletePreset throws 404 when preset missing`() {
        whenever(presetRepository.existsById("ghost")).thenReturn(false)
        val service = newService { StrategyResearchAiTextResult(provider = "test-claude", text = """{"items":[]}""") }

        shouldThrow<ResponseStatusException> {
            service.deletePreset("ghost")
        }
    }

    private fun preset(id: String): StrategyResearchAiDiscoveryPresetJpaEntity =
        StrategyResearchAiDiscoveryPresetJpaEntity(
            id = id,
            label = "단기 거래대금 폭발",
            description = "스캘핑 검증용",
            promptText = "거래대금 집중 종목을 탐색해라.",
            sortOrder = 10,
            active = true,
            builtIn = true,
        )

    private fun newService(complete: () -> StrategyResearchAiTextResult): StrategyResearchAiDiscoveryService =
        StrategyResearchAiDiscoveryService(
            instrumentJpaRepository = instrumentRepository,
            candleJpaRepository = candleRepository,
            aiTextClient = StrategyResearchAiTextClient { complete() },
            objectMapper = objectMapper,
            stateRepository = stateRepository,
            presetRepository = presetRepository,
            corePromptRepository = corePromptRepository,
        )

    private fun item(
        code: String,
        name: String,
        status: StrategyResearchAiDiscoveryItemStatus,
        candleCount: Long? = null,
        hasResearchCandles: Boolean? = null,
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
            candleCount = candleCount,
            hasResearchCandles = hasResearchCandles,
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
