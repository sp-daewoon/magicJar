package com.mj.consumer.infrastructure.kis

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.common.Order
import com.mj.domain.common.OrderResult
import com.mj.domain.common.Quote
import com.mj.domain.order.port.BrokerGateway
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * KIS REST/WS 를 묶어 domain.order.port.BrokerGateway 로 노출.
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
