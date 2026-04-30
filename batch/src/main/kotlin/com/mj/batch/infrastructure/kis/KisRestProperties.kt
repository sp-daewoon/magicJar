package com.mj.batch.infrastructure.kis

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * KIS REST API 접속 정보.
 *
 * real-kis 프로파일에서 RealKisRestAdapter가 사용한다.
 * stub-kis 프로파일에서는 이 빈이 주입되더라도 실제 호출은 없으므로 값이 없어도 무방.
 */
@ConfigurationProperties(prefix = "magicjar.kis")
data class KisRestProperties(
    val mode: String = "STUB",
    val baseUrl: String = "https://openapivts.koreainvestment.com:29443",
    val appKey: String = "",
    val appSecret: String = "",
    val account: String = "",
    /**
     * 발급된 토큰의 캐시 TTL (초).
     * KIS 발급 유효기간은 24h (86400초)이나 여유 마진을 두어 기본값 82800초(23h)로 설정.
     * JVM 재시작 시 cachedToken=null이 되므로 재발급한다.
     */
    val tokenTtlSeconds: Long = 82800L,
)
