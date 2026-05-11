package kr.spdaewoon.magicjar.consumer.config

import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.netty.http.client.HttpClient
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory

/**
 * Consumer WebClient / WebSocketClient Bean 설정.
 *
 * 빈 분리 구조:
 *  - webClient (기본) — KIS REST 등 일반 endpoint. JDK 기본 SSL context.
 *  - kisWebSocketClient (KIS WS 전용) — ops.koreainvestment.com:21000 TLS cipher suite 호환 문제 해결.
 *    JDK 21 + reactor netty 기본 설정으로는 KIS WS 서버와 SSL handshake timeout 발생
 *    (SslHandshakeTimeoutException after 10000ms). batch 모듈 PR #278 DART fix 동일 패턴 적용.
 *    SslProvider.JDK + TLSv1.2/1.3 프로토콜 명시로 cipher suite 협상 강제화.
 *
 * KIS WS endpoint: wss://ops.koreainvestment.com:21000 (prod) / :31000 (VTS)
 * 출처: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/websocket/
 */
@Configuration
class WebClientConfig {
    /** 일반 WebClient — KIS REST 호출 (JDK 기본 SSL context) */
    @Bean
    fun webClient(): WebClient = WebClient.builder().build()

    /**
     * KIS WS 전용 WebSocketClient — ops.koreainvestment.com TLS handshake timeout 대응.
     *
     * 문제: JDK 21 + reactor netty 기본 설정에서 KIS WS 서버와 SSL handshake 10초 timeout.
     *       connect 직후 SslHandshakeTimeoutException → disconnect/reconnect 무한 루프.
     * 해결: SslContextBuilder로 TLSv1.2/1.3 프로토콜 + JDK 기본 TrustManager 명시 →
     *       cipher suite negotiation 강제화. SslProvider.JDK로 JDK SSL 스택 직접 사용.
     *
     * 패턴 출처: batch WebClientConfig dartWebClient (PR #278 backlog §22 fix) — 1:1 정합.
     * KIS WS endpoint 출처: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/websocket/
     */
    @Bean
    @Qualifier("kisWebSocketClient")
    fun kisWebSocketClient(): ReactorNettyWebSocketClient {
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

        return ReactorNettyWebSocketClient(httpClient)
    }
}
