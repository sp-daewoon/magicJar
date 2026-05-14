package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.SymbolController
import kr.spdaewoon.magicjar.api.service.CandleQueryService
import kr.spdaewoon.magicjar.api.service.CandleRefreshService
import kr.spdaewoon.magicjar.api.service.SymbolMetaService
import kr.spdaewoon.magicjar.api.service.dto.CandleRefreshResponse
import kr.spdaewoon.magicjar.api.service.dto.IntervalRefreshResult
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.server.ResponseStatusException

/**
 * POST /api/symbols/{code}/candles/refresh 슬라이스 테스트.
 *
 * 검증 1: 정상 code + intervals → 200 OK + refreshedIntervals 응답
 * 검증 2: 유효하지 않은 code (6자리 아님) → 400
 * 검증 3: intervals 빈 문자열 → default 적용 200 OK
 * 검증 4: 종목 없음 → 404
 * 검증 5: KIS 인증 실패 (500) → 500
 *
 * OnboardingGuardFilter mock: @MockBean UserAccountJpaRepository + existsAny()=true.
 * DB·Redis·KIS 실호출 없이 @MockBean으로 검증.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@WebMvcTest(controllers = [SymbolController::class])
class CandleRefreshControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var symbolMetaService: SymbolMetaService

    @MockBean
    lateinit var candleQueryService: CandleQueryService

    @MockBean
    lateinit var candleRefreshService: CandleRefreshService

    @MockBean
    lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    /** 검증 1: 정상 code + intervals=1d,1wk,1mo,1y → 200 OK */
    @Test
    fun `POST candles refresh 200 OK`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(candleRefreshService.refresh(eq("006110"), any())).thenReturn(
            CandleRefreshResponse(
                refreshedIntervals =
                    listOf(
                        IntervalRefreshResult("1d", 500, 22, "2026-05-13T15:00:00Z", "OK"),
                        IntervalRefreshResult("1wk", 100, 5, "2026-05-12T15:00:00Z", "OK"),
                        IntervalRefreshResult("1mo", 24, 1, "2026-05-01T15:00:00Z", "OK"),
                        IntervalRefreshResult("1y", 3, 0, "2025-12-30T15:00:00Z", "OK"),
                    ),
                elapsedMs = 1234L,
            ),
        )

        mockMvc
            .post("/api/symbols/006110/candles/refresh?intervals=1d,1wk,1mo,1y")
            .andExpect {
                status { isOk() }
                jsonPath("$.refreshedIntervals.length()") { value(4) }
                jsonPath("$.refreshedIntervals[0].interval") { value("1d") }
                jsonPath("$.refreshedIntervals[0].fetchedCount") { value(500) }
                jsonPath("$.refreshedIntervals[0].insertedCount") { value(22) }
                jsonPath("$.refreshedIntervals[0].status") { value("OK") }
                jsonPath("$.elapsedMs") { value(1234) }
            }
    }

    /** 검증 2: 유효하지 않은 code (4자리) → 400 Bad Request */
    @Test
    fun `POST candles refresh 잘못된 code 400`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)

        mockMvc
            .post("/api/symbols/1234/candles/refresh")
            .andExpect {
                status { isBadRequest() }
            }
    }

    /** 검증 3: intervals 빈 문자열 → default 적용 200 OK */
    @Test
    fun `POST candles refresh intervals 빈 문자열 default 적용 200 OK`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(candleRefreshService.refresh(eq("005930"), any())).thenReturn(
            CandleRefreshResponse(
                refreshedIntervals =
                    listOf(
                        IntervalRefreshResult("1d", 200, 10, "2026-05-13T15:00:00Z", "OK"),
                        IntervalRefreshResult("1wk", 50, 2, "2026-05-12T15:00:00Z", "OK"),
                        IntervalRefreshResult("1mo", 12, 0, "2026-05-01T15:00:00Z", "OK"),
                        IntervalRefreshResult("1y", 2, 0, "2025-12-30T15:00:00Z", "OK"),
                    ),
                elapsedMs = 800L,
            ),
        )

        mockMvc
            .post("/api/symbols/005930/candles/refresh")
            .andExpect {
                status { isOk() }
                jsonPath("$.refreshedIntervals.length()") { value(4) }
            }
    }

    /** 검증 4: 종목 없음 → 404 Not Found */
    @Test
    fun `POST candles refresh 종목 없음 404`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(candleRefreshService.refresh(eq("999999"), any())).thenThrow(
            ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다: 999999"),
        )

        mockMvc
            .post("/api/symbols/999999/candles/refresh?intervals=1d")
            .andExpect {
                status { isNotFound() }
            }
    }

    /** 검증 5: KIS 인증 실패 (500) → 500 Internal Server Error */
    @Test
    fun `POST candles refresh KIS 인증 실패 500`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(candleRefreshService.refresh(eq("005930"), any())).thenThrow(
            ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "KIS 자격증명이 등록되지 않았습니다"),
        )

        mockMvc
            .post("/api/symbols/005930/candles/refresh?intervals=1d")
            .andExpect {
                status { isInternalServerError() }
            }
    }
}
