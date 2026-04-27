package com.mj.domain.market.port

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.common.Quote
import java.time.LocalDate

/**
 * KIS REST API 포트.
 *
 * - stub-kis 프로파일: StubKisRestAdapter (키 없이 kis-samples JSON 반환)
 * - real-kis 프로파일: RealKisRestAdapter (실제 KIS REST API 호출)
 *
 * batch, api, consumer 모두 이 포트를 통해 KIS 시세를 조회한다.
 */
interface KisRestPort {
    /** 일/분봉 캔들 조회. from~to는 inclusive. */
    fun fetchCandles(
        code: String,
        interval: Interval,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle>

    /** 현재가 조회. */
    fun fetchQuote(code: String): Quote

    /**
     * 캐시된 access token 반환 (또는 신규 발급).
     * stub 모드에서는 "stub-token"을 반환한다.
     */
    fun token(): String
}
