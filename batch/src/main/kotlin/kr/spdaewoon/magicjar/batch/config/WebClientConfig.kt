package kr.spdaewoon.magicjar.batch.config

import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory

/**
 * Batch WebClient Bean 설정.
 *
 * 빈 분리 구조:
 *  - webClient (기본) — KIS·Yahoo·Naver 등 일반 endpoint. JDK 기본 SSL context.
 *  - dartWebClient (DART 전용) — opendart.fss.or.kr TLS cipher suite 호환 문제 해결.
 *    JDK 21 + reactor netty 기본 설정으로는 opendart.fss.or.kr와 TLS handshake 실패
 *    (handshake_failure). DART 전용 HttpClient에 TLSv1.2 + default trust manager를
 *    명시해 cipher suite 협상을 강제 (backlog §22 fix — 옵션 3 채택).
 *    다른 endpoint(KIS·Yahoo·Naver)에는 영향 없음.
 */
@Configuration
class WebClientConfig {
    /** 일반 WebClient — KIS·Yahoo·Naver 등 (JDK 기본 SSL context) */
    @Bean
    fun webClient(): WebClient = WebClient.builder().build()

    /**
     * DART 전용 WebClient — opendart.fss.or.kr TLS handshake_failure 대응 (backlog §22).
     *
     * 문제: JDK 21 + reactor netty 기본 설정에서 opendart.fss.or.kr와 TLS cipher suite 협상 실패.
     * 해결: SslContextBuilder로 TLSv1.2 프로토콜 + JDK 기본 TrustManager 명시 → cipher suite
     *       negotiation 강제화. SslProvider.JDK로 명시해 JDK SSL 스택을 직접 사용.
     *
     * 출처: opendart.fss.or.kr — https://opendart.fss.or.kr/api/corpCode.xml (ADR 0018 §4-1)
     */
    @Bean
    @Qualifier("dartWebClient")
    fun dartWebClient(): WebClient {
        val trustManagerFactory =
            TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .also { it.init(null as KeyStore?) }

        val sslContext =
            SslContextBuilder
                .forClient()
                .sslProvider(SslProvider.JDK)
                .trustManager(trustManagerFactory)
                .protocols("TLSv1.2", "TLSv1.3")
                .build()

        val httpClient =
            HttpClient
                .create()
                .secure { spec -> spec.sslContext(sslContext) }

        return WebClient
            .builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
