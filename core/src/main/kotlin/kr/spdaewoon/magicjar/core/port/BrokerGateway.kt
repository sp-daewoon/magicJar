package kr.spdaewoon.magicjar.core.port

import kr.spdaewoon.magicjar.core.domain.Candle
import kr.spdaewoon.magicjar.core.domain.Interval
import kr.spdaewoon.magicjar.core.domain.Order
import kr.spdaewoon.magicjar.core.domain.OrderResult
import kr.spdaewoon.magicjar.core.domain.Quote
import java.time.Instant

/**
 * 증권사(KIS) 원격 게이트웨이. order-exec은 이 포트를 통해서만 KIS에 접근한다.
 * 구현체는 market-data 모듈이 제공한다 (KIS REST/WS 클라이언트).
 */
interface BrokerGateway {
    fun fetchQuote(code: String): Quote

    fun fetchCandles(
        code: String,
        interval: Interval,
        from: Instant,
        to: Instant,
    ): List<Candle>

    fun placeOrder(order: Order): OrderResult
}
