package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.CandleDto
import kr.spdaewoon.magicjar.api.controller.CandlesResponseDto
import kr.spdaewoon.magicjar.api.controller.SymbolController
import kr.spdaewoon.magicjar.api.controller.SymbolDto
import kr.spdaewoon.magicjar.api.service.CandleQueryService
import kr.spdaewoon.magicjar.api.service.SymbolMetaService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * SymbolController 슬라이스 테스트 (spec §I smoke 1건, 룰 4 정합).
 * DB·Redis·Kafka·KIS 없이 @MockBean으로 검증.
 *
 * 검증 1: GET /api/symbols/005930 → 200 OK + SymbolDto 응답
 * 검증 2: GET /api/symbols/005930/candles?interval=1d → 200 OK + CandlesResponseDto
 * 검증 3: GET /api/symbols/999 → 400 (종목코드 형식 오류)
 */
@WebMvcTest(
    controllers = [SymbolController::class],
)
class SymbolControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var symbolMetaService: SymbolMetaService

    @MockBean
    lateinit var candleQueryService: CandleQueryService

    @MockBean
    lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @MockBean
    lateinit var userAccountJpaRepository: kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository

    @Test
    fun `GET symbol 200 OK`() {
        whenever(symbolMetaService.getSymbol(any())).thenReturn(
            SymbolDto(
                code = "005930",
                name = "삼성전자",
                market = "KOSPI",
                industry = "전기전자",
                currentPrice = "60900",
                previousClose = "60500",
                change = "400",
                changeRate = "0.66",
                high = "61000",
                low = "60750",
                open = "60800",
                volume = 12345678L,
                marketCap = "4800000",
                per = "12.5",
                pbr = "1.3",
                eps = "4872",
                bps = "46850",
                asOf = "2026-05-07T14:23:45.123Z",
                source = "live",
                stale = false,
            ),
        )

        mockMvc.get("/api/symbols/005930")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("005930") }
                jsonPath("$.name") { value("삼성전자") }
                jsonPath("$.currentPrice") { value("60900") }
                jsonPath("$.per") { value("12.5") }
            }
    }

    @Test
    fun `GET candles 200 OK`() {
        whenever(candleQueryService.supportedIntervals).thenReturn(setOf("1m", "5m", "30m", "1h", "1d"))
        whenever(candleQueryService.getCandles(any(), any(), any(), any())).thenReturn(
            CandlesResponseDto(
                code = "005930",
                interval = "1d",
                candles = listOf(
                    CandleDto(
                        ts = "2026-05-07T15:30:00Z",
                        open = "60800",
                        high = "61000",
                        low = "60750",
                        close = "60900",
                        volume = 12345678L,
                    ),
                ),
                hasMore = false,
                nextBefore = null,
            ),
        )

        mockMvc.get("/api/symbols/005930/candles?interval=1d&limit=200")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("005930") }
                jsonPath("$.interval") { value("1d") }
                jsonPath("$.candles.length()") { value(1) }
                jsonPath("$.hasMore") { value(false) }
            }
    }

    @Test
    fun `GET symbol 종목코드 형식 오류 400`() {
        mockMvc.get("/api/symbols/999") // 3자리 — 6자리 아님
            .andExpect {
                status { isBadRequest() }
            }
    }
}
