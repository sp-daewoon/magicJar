package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.common.Order
import com.mj.domain.common.OrderResult
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.Quote
import com.mj.domain.order.port.BrokerGateway
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 백테스트·테스트용 Fake BrokerGateway. classpath kis-samples 에서 응답 JSON 을 읽어준다.
 *
 * 주문은 항상 즉시 FILLED 결과를 반환한다(샘플 평균체결가는 quote.json 가격 사용).
 */
@Component("fakeBrokerGateway")
@Profile("backtest")
class FakeBrokerGateway(
    private val resourceLoader: ResourceLoader,
    private val objectMapper: ObjectMapper,
) : BrokerGateway {
    private val log = LoggerFactory.getLogger(FakeBrokerGateway::class.java)

    override fun fetchQuote(code: String): Quote {
        val sample =
            resourceLoader
                .getResource("classpath:kis-samples/quote.json")
                .inputStream
                .use { objectMapper.readValue(it, KisQuoteApi.SampleQuote::class.java) }
        return Quote(
            code = code,
            at = Instant.parse(sample.at),
            price = BigDecimal(sample.price),
            volume = sample.volume,
        )
    }

    override fun fetchCandles(
        code: String,
        interval: Interval,
        from: Instant,
        to: Instant,
    ): List<Candle> {
        val sample =
            resourceLoader
                .getResource("classpath:kis-samples/candles-d1.json")
                .inputStream
                .use { objectMapper.readValue(it, Array<KisQuoteApi.SampleCandle>::class.java) }
        return sample
            .map {
                Candle(
                    code = code,
                    interval = Interval.D1,
                    openTime = LocalDate.parse(it.date).atStartOfDay(KST).toInstant(),
                    open = BigDecimal(it.open),
                    high = BigDecimal(it.high),
                    low = BigDecimal(it.low),
                    close = BigDecimal(it.close),
                    volume = it.volume,
                    tradeCount = it.tradeCount,
                )
            }.filter { !it.openTime.isBefore(from) && !it.openTime.isAfter(to) }
    }

    override fun placeOrder(order: Order): OrderResult {
        log.info("FakeBrokerGateway placeOrder {}", order)
        val price = order.price ?: fetchQuote(order.code).price
        return OrderResult(
            order = order.copy(status = OrderStatus.FILLED),
            resultStatus = OrderStatus.FILLED,
            filledQty = order.qty,
            avgFillPrice = price,
            filledAt = Instant.now(),
            rejectReason = null,
        )
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
