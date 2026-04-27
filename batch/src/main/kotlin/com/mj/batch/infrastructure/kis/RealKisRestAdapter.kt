package com.mj.batch.infrastructure.kis

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.common.Quote
import com.mj.domain.market.port.KisRestPort
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * real-kis 프로파일용 KisRestPort 구현체.
 *
 * KIS 모의계좌 OpenAPI를 실제로 호출한다.
 * 활성화 조건: `spring.profiles.active=real-kis` + 환경변수 KIS_MOCK_APP_KEY/KIS_MOCK_APP_SECRET 필수.
 *
 * Phase E-P2 현재는 최소 뼈대만 구현 (token 발급, 일봉 조회 trId=FHKST01010400).
 * 실제 KIS 키 수령 후 E-P2-bis에서 검증.
 *
 * @see StubKisRestAdapter stub-kis 프로파일 어댑터
 */
@Component
@Profile("real-kis")
class RealKisRestAdapter(
    private val props: KisRestProperties,
) : KisRestPort {
    private val log = LoggerFactory.getLogger(RealKisRestAdapter::class.java)
    private val zone = ZoneId.of("Asia/Seoul")

    private val restClient: RestClient =
        RestClient
            .builder()
            .baseUrl(props.baseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .build()

    /** 발급된 access_token 캐시 (24h TTL — 간단 구현: JVM 재시작 시 재발급) */
    @Volatile
    private var cachedToken: String? = null

    override fun token(): String {
        cachedToken?.let { return it }
        return issueToken().also { cachedToken = it }
    }

    private fun issueToken(): String {
        log.info("[real-kis] issuing access token (baseUrl={})", props.baseUrl)

        data class TokenRequest(
            val grant_type: String = "client_credentials",
            val appkey: String,
            val appsecret: String,
        )

        data class TokenResponse(
            val access_token: String,
        )

        val resp =
            restClient
                .post()
                .uri("/oauth2/tokenP")
                .body(TokenRequest(appkey = props.appKey, appsecret = props.appSecret))
                .retrieve()
                .body(TokenResponse::class.java)
                ?: error("[real-kis] token issue failed — null response")
        log.info("[real-kis] access token issued")
        return resp.access_token
    }

    override fun fetchCandles(
        code: String,
        interval: Interval,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle> {
        // TODO(E-P2-bis): KIS 일봉 조회 trId=FHKST01010400 구현 후 KIS 실계좌 검증
        log.warn("[real-kis] fetchCandles not fully implemented — returning empty (code={})", code)
        return emptyList()
    }

    override fun fetchQuote(code: String): Quote {
        // TODO(E-P2-bis): KIS 현재가 조회 trId=FHKST01010100 구현
        log.warn("[real-kis] fetchQuote not fully implemented — returning placeholder (code={})", code)
        return Quote(
            code = code,
            at = java.time.Instant.now(),
            price = BigDecimal.ZERO,
            volume = 0L,
        )
    }
}
