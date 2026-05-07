package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.InstrumentController
import kr.spdaewoon.magicjar.api.persistence.InstrumentAliasJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.service.InstrumentSearchService
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentSearchResult
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * InstrumentController 슬라이스 테스트 (T-IM1 spec §I smoke 2건 — 룰 4 정합).
 *
 * DB·Redis·Kafka 없이 @MockBean으로 검증.
 * OnboardingGuardFilter mock 의무:
 *   @MockBean UserAccountJpaRepository + existsAny()=true (PR-10 SymbolControllerTest 함정 회피).
 *
 * 검증 1: GET /api/instruments/search?q=삼성전 → 200 OK + results 배열
 * 검증 2: GET /api/instruments/search?q=  (빈 q) → 400 Bad Request
 */
@WebMvcTest(controllers = [InstrumentController::class])
class InstrumentControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var instrumentSearchService: InstrumentSearchService

    @MockBean
    lateinit var instrumentJpaRepository: InstrumentJpaRepository

    @MockBean
    lateinit var instrumentAliasJpaRepository: InstrumentAliasJpaRepository

    // OnboardingGuardFilter mock 의무 (PR-10 SymbolControllerTest @Disabled 함정 회피)
    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    @Test
    fun `GET instruments search 삼성전 200 OK`() {
        // given: OnboardingGuardFilter 통과 + 검색 결과 mock
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(instrumentSearchService.search(any(), any(), anyOrNull())).thenReturn(
            listOf(
                InstrumentSearchResult(
                    code = "005930",
                    name = "삼성전자",
                    market = InstrumentMarket.KOSPI,
                    sector = "전기전자",
                    score = 1.0,
                ),
                InstrumentSearchResult(
                    code = "009150",
                    name = "삼성전기",
                    market = InstrumentMarket.KOSPI,
                    sector = "전기전자",
                    score = 0.95,
                ),
            ),
        )

        // when & then
        mockMvc
            .get("/api/instruments/search?q=삼성전&limit=10")
            .andExpect {
                status { isOk() }
                jsonPath("$.query") { value("삼성전") }
                jsonPath("$.results.length()") { value(2) }
                jsonPath("$.results[0].code") { value("005930") }
                jsonPath("$.results[0].name") { value("삼성전자") }
                jsonPath("$.results[0].market") { value("KOSPI") }
                jsonPath("$.totalMatched") { value(2) }
            }
    }

    @Test
    fun `GET instruments search 빈 q 400 Bad Request`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc
            .get("/api/instruments/search?q=")
            .andExpect {
                status { isBadRequest() }
            }
    }
}
