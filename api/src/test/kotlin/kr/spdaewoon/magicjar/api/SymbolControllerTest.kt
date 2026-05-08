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
import org.mockito.kotlin.anyOrNull
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
 * 검증 4: GET /api/symbols/005930/candles?interval=1wk → 200 OK (화이트리스트 8종 확장)
 * 검증 5: GET /api/symbols/005930/candles?interval=1mo → 200 OK
 * 검증 6: GET /api/symbols/005930/candles?interval=1y → 200 OK
 * 검증 7: GET /api/symbols/005930/candles?interval=INVALID → 400
 *
 * OnboardingGuardFilter mock 의무:
 *   @MockBean UserAccountJpaRepository + existsAny()=true (backlog §11 — InstrumentControllerTest 동일 패턴).
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
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
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

        mockMvc
            .get("/api/symbols/005930")
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
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(candleQueryService.supportedIntervals).thenReturn(setOf("1m", "5m", "30m", "1h", "1d", "1wk", "1mo", "1y"))
        whenever(candleQueryService.getCandles(any(), any(), anyOrNull(), any())).thenReturn(
            CandlesResponseDto(
                code = "005930",
                interval = "1d",
                candles =
                    listOf(
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

        mockMvc
            .get("/api/symbols/005930/candles?interval=1d&limit=200")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("005930") }
                jsonPath("$.interval") { value("1d") }
                jsonPath("$.candles.length()") { value(1) }
                jsonPath("$.hasMore") { value(false) }
            }
    }

    @Test
    fun `GET candles 1wk 200 OK`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(candleQueryService.supportedIntervals).thenReturn(setOf("1m", "5m", "30m", "1h", "1d", "1wk", "1mo", "1y"))
        whenever(candleQueryService.getCandles(any(), any(), anyOrNull(), any())).thenReturn(
            CandlesResponseDto(
                code = "005930",
                interval = "1wk",
                candles =
                    listOf(
                        CandleDto(
                            ts = "2026-04-28T00:00:00Z",
                            open = "60000",
                            high = "61500",
                            low = "59500",
                            close = "60900",
                            volume = 50000000L,
                        ),
                    ),
                hasMore = false,
                nextBefore = null,
            ),
        )

        mockMvc
            .get("/api/symbols/005930/candles?interval=1wk&limit=100")
            .andExpect {
                status { isOk() }
                jsonPath("$.interval") { value("1wk") }
                jsonPath("$.candles.length()") { value(1) }
            }
    }

    @Test
    fun `GET candles 1mo 200 OK`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(candleQueryService.supportedIntervals).thenReturn(setOf("1m", "5m", "30m", "1h", "1d", "1wk", "1mo", "1y"))
        whenever(candleQueryService.getCandles(any(), any(), anyOrNull(), any())).thenReturn(
            CandlesResponseDto(
                code = "005930",
                interval = "1mo",
                candles =
                    listOf(
                        CandleDto(
                            ts = "2026-04-01T00:00:00Z",
                            open = "59000",
                            high = "62000",
                            low = "58500",
                            close = "60900",
                            volume = 200000000L,
                        ),
                    ),
                hasMore = false,
                nextBefore = null,
            ),
        )

        mockMvc
            .get("/api/symbols/005930/candles?interval=1mo&limit=50")
            .andExpect {
                status { isOk() }
                jsonPath("$.interval") { value("1mo") }
            }
    }

    @Test
    fun `GET candles 1y 200 OK`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(candleQueryService.supportedIntervals).thenReturn(setOf("1m", "5m", "30m", "1h", "1d", "1wk", "1mo", "1y"))
        whenever(candleQueryService.getCandles(any(), any(), anyOrNull(), any())).thenReturn(
            CandlesResponseDto(
                code = "005930",
                interval = "1y",
                candles =
                    listOf(
                        CandleDto(
                            ts = "2025-01-01T00:00:00Z",
                            open = "55000",
                            high = "65000",
                            low = "54000",
                            close = "60900",
                            volume = 2000000000L,
                        ),
                    ),
                hasMore = false,
                nextBefore = null,
            ),
        )

        mockMvc
            .get("/api/symbols/005930/candles?interval=1y&limit=20")
            .andExpect {
                status { isOk() }
                jsonPath("$.interval") { value("1y") }
            }
    }

    @Test
    fun `GET candles 지원하지 않는 interval 400`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        whenever(candleQueryService.supportedIntervals).thenReturn(setOf("1m", "5m", "30m", "1h", "1d", "1wk", "1mo", "1y"))

        mockMvc
            .get("/api/symbols/005930/candles?interval=INVALID")
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET symbol 종목코드 형식 오류 400`() {
        whenever(userAccountJpaRepository.existsAny()).thenReturn(true)
        mockMvc
            .get("/api/symbols/999") // 3자리 — 6자리 아님
            .andExpect {
                status { isBadRequest() }
            }
    }
}
