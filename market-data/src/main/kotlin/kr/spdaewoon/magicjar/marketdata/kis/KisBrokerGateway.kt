package kr.spdaewoon.magicjar.marketdata.kis

import kr.spdaewoon.magicjar.core.domain.Candle
import kr.spdaewoon.magicjar.core.domain.Interval
import kr.spdaewoon.magicjar.core.domain.Order
import kr.spdaewoon.magicjar.core.domain.OrderResult
import kr.spdaewoon.magicjar.core.domain.Quote
import kr.spdaewoon.magicjar.core.port.BrokerGateway
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * KIS REST/WS 를 묶어 core.port.BrokerGateway 로 노출.
 *
 * - fetchQuote: KisQuoteApi.fetchCurrentPrice
 * - fetchCandles: KisQuoteApi.fetchDailyCandles (현재 D1 만 지원, 그 외는 D1 폴백)
 * - placeOrder: KisOrderApi.placeOrder
 *
 * `@ConditionalOnMissingBean` 으로 backtest 프로파일에서 FakeBrokerGateway 가 우선되도록 한다.
 */
@Component
@ConditionalOnMissingBean(name = ["fakeBrokerGateway"])
class KisBrokerGateway(
    private val quoteApi: KisQuoteApi,
    private val orderApi: KisOrderApi,
) : BrokerGateway {
    override fun fetchQuote(code: String): Quote = quoteApi.fetchCurrentPrice(code)

    override fun fetchCandles(
        code: String,
        interval: Interval,
        from: Instant,
        to: Instant,
    ): List<Candle> {
        val fromDate = LocalDate.ofInstant(from, KST)
        val toDate = LocalDate.ofInstant(to, KST)
        return quoteApi.fetchDailyCandles(code, fromDate, toDate)
    }

    override fun placeOrder(order: Order): OrderResult = orderApi.placeOrder(order)

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
