package com.mj.consumer.infrastructure.kis

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * KIS OpenAPI 접속·인증 설정.
 *
 * mockMode=true 일 때 모의계좌 베이스 URL/주문 trId(VTTC...)를 사용한다.
 * appKey/appSecret 누락 시 클라이언트는 부팅은 성공하되 호출 시점에 명시적 예외를 던진다(샘플 폴백 사용).
 */
@ConfigurationProperties(prefix = "kis")
data class KisProperties(
    val appKey: String? = null,
    val appSecret: String? = null,
    val baseUrl: String = "https://openapivts.koreainvestment.com:29443",
    val wsUrl: String = "ws://ops.koreainvestment.com:31000",
    val accountCano: String? = null,
    val accountPrdt: String = "01",
    val mockMode: Boolean = true,
    val tokenTtlSeconds: Long = 23 * 60 * 60,
    val rate: RateLimit = RateLimit(),
) {
    data class RateLimit(
        val capacityPerSecond: Int = 15,
        val orderPerSecond: Int = 5,
    )

    /** appKey/appSecret이 모두 채워져 있을 때만 실제 KIS 호출이 가능하다. */
    fun hasCredentials(): Boolean = !appKey.isNullOrBlank() && !appSecret.isNullOrBlank()
}
