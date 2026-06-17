package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.StrategiesController
import kr.spdaewoon.magicjar.api.controller.dto.StrategyStartItem
import kr.spdaewoon.magicjar.api.controller.dto.StrategyStartResponse
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.api.service.market.LivePriceCacheReader
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.api.service.strategy.StrategyStartService
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyFavoritePort
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * StrategiesController 시작 화면 + 즐겨찾기 슬라이스 테스트 — spec 2026-06-18 §6.
 *
 * 검증:
 *  1. GET /api/strategies/start → 200 + templates·myStrategies·favoriteIds 합성.
 *  2. POST /api/strategies/{id}/favorite → 201 + favoritePort.add(userId, id) 호출.
 *  3. DELETE /api/strategies/{id}/favorite → 204 + favoritePort.remove(userId, id) 호출.
 */
@WebMvcTest(controllers = [StrategiesController::class])
@Import(KisYamlDslParser::class)
class StrategiesControllerStartTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var strategyService: StrategyService

    @MockBean
    lateinit var instrumentJpaRepository: InstrumentJpaRepository

    @MockBean
    lateinit var strategyPositionReadJpaRepository: StrategyPositionReadJpaRepository

    @MockBean
    lateinit var livePriceCacheReader: LivePriceCacheReader

    @MockBean
    lateinit var strategyStartService: StrategyStartService

    @MockBean
    lateinit var favoritePort: StrategyFavoritePort

    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    private fun stubOnboarding() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
    }

    private fun item(
        id: Long,
        name: String,
        favorite: Boolean,
    ) = StrategyStartItem(
        id = id,
        name = name,
        nameKo = name,
        tf = "3m",
        dslEntry = "close > ma(20)",
        dslExit = null,
        takeProfitPct = null,
        fixedStopLossPct = null,
        favorite = favorite,
    )

    @Test
    fun `GET start 는 templates·myStrategies·favoriteIds 를 합성 반환한다`() {
        stubOnboarding()
        whenever(strategyStartService.load(1L)).thenReturn(
            StrategyStartResponse(
                templates = listOf(item(99L, "fvg-bullish-reclaim", favorite = false)),
                myStrategies = listOf(item(10L, "my-strategy-a", favorite = true)),
                favoriteIds = listOf(10L),
            ),
        )

        mockMvc
            .get("/api/strategies/start") { param("userId", "1") }
            .andExpect {
                status { isOk() }
                jsonPath("$.templates[0].id") { value(99) }
                jsonPath("$.templates[0].favorite") { value(false) }
                jsonPath("$.myStrategies[0].id") { value(10) }
                jsonPath("$.myStrategies[0].favorite") { value(true) }
                jsonPath("$.favoriteIds[0]") { value(10) }
            }
    }

    @Test
    fun `POST favorite 는 201 + favoritePort_add 호출`() {
        stubOnboarding()

        mockMvc
            .post("/api/strategies/5/favorite") { param("userId", "1") }
            .andExpect { status { isCreated() } }

        verify(favoritePort).add(eq(1L), eq(5L))
    }

    @Test
    fun `DELETE favorite 는 204 + favoritePort_remove 호출`() {
        stubOnboarding()

        mockMvc
            .delete("/api/strategies/5/favorite") { param("userId", "1") }
            .andExpect { status { isNoContent() } }

        verify(favoritePort).remove(eq(1L), eq(5L))
    }
}
