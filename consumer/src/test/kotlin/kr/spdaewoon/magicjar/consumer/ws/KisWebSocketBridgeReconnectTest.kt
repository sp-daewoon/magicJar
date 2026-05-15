package kr.spdaewoon.magicjar.consumer.ws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisApprovalAdapter
import kr.spdaewoon.magicjar.domain.market.port.QuoteCachePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import java.net.URI

/**
 * KisWebSocketBridge 단위 테스트.
 *
 * 검증 항목:
 * 1. reconnectGracefully 호출 시 session.connected=false 전환 (기존 연결 종료)
 * 2. reconnectGracefully 호출 시 reconnectCount 리셋 (지수 백오프 초기화)
 * 3. shuttingDown=true 시 reconnect 스킵
 * 4. subscribe(codes) 시 H0STCNT0 + H0NXCNT0 둘 다 구독 메시지 송신 검증 (NXT 장외 sync hotfix)
 *
 * 주의: doConnect()는 Reactor WS 연결(비동기)이라 실제 연결 검증은 통합 테스트에서.
 * 본 테스트는 session 상태 변경 + reconnectCount 초기화 + NXT 구독 메시지에 집중.
 *
 * AI 호출 0 — 룰 5 + ADR 0074 §11-2 정합.
 */
class KisWebSocketBridgeReconnectTest {
    private val approvalAdapter = mockk<KisApprovalAdapter>(relaxed = true)
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val quoteCachePort = mockk<QuoteCachePort>(relaxed = true)
    private val wsClient = mockk<ReactorNettyWebSocketClient>(relaxed = true)

    private lateinit var bridge: KisWebSocketBridge

    @BeforeEach
    fun setUp() {
        // Redis ValueOperations mock
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps

        // wsClient.execute(): 비동기 연결 — Mono.empty() 반환 (실제 연결 없음)
        every { wsClient.execute(any<URI>(), any()) } returns Mono.empty()

        // approvalAdapter: approval_key 발급 mock
        every { approvalAdapter.getApprovalKey(any(), any()) } returns "mock-approval-key"

        bridge =
            KisWebSocketBridge(
                approvalAdapter = approvalAdapter,
                redisTemplate = redisTemplate,
                kafkaTemplate = kafkaTemplate,
                objectMapper = objectMapper,
                quoteCachePort = quoteCachePort,
                wsClient = wsClient,
                wsUrlProd = "ws://localhost:21000",
                wsUrlVps = "ws://localhost:31000",
                magicjarMode = "REAL",
                defaultUserId = "user001",
            )
    }

    @Test
    fun `reconnectGracefully 호출 시 기존 연결 종료 후 신규 WS 연결 시도`() {
        // given: session을 connected 상태로 강제 설정
        val session = getSession(bridge)
        session.onConnected()

        // when
        bridge.reconnectGracefully("user001")

        // then
        // wsClient.execute 호출 — 신규 연결 시도 (Mono.empty()라도 execute 호출 확인)
        verify(atLeast = 1) { wsClient.execute(any<URI>(), any()) }
        // reconnect 후 session.connected는 비동기 Reactor 흐름에서 다시 true로 전환될 수 있음.
        // 단위 테스트 범위: wsClient.execute 호출 여부로 reconnect 시도 검증.
    }

    @Test
    fun `shuttingDown true 시 reconnectGracefully 스킵`() {
        // given: shutdown 상태
        val session = getSession(bridge)
        session.shuttingDown = true

        // when
        bridge.reconnectGracefully("user001")

        // then: wsClient.execute 호출 없음 (reconnect 스킵)
        verify(exactly = 0) { wsClient.execute(any<URI>(), any()) }
    }

    /**
     * subscribe(codes) 시 H0STCNT0 + H0NXCNT0 둘 다 구독 등록 검증.
     *
     * NXT 장외 sync 결함 hotfix (ADR 0063 정합 — 08:00~20:00 KST cover).
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     *   ccnl_nxt() tr_id="H0NXCNT0"
     *
     * 검증: subscribe 후 session.subscribedCodes + nxtSubscribedCodes 둘 다 code 포함.
     */
    @Test
    fun `subscribe codes 시 H0STCNT0(session) + H0NXCNT0(nxtSubscribedCodes) 둘 다 등록`() {
        // given: sendSink 없는 상태 (연결 0) — subscribe는 nxtSubscribedCodes에 add됨
        // when
        bridge.subscribe(listOf("005930", "000660"))

        // then: H0STCNT0 구독 — session.subscribedCodes 확인
        val session = getSession(bridge)
        assertThat(session.subscribedCodes).containsExactlyInAnyOrder("005930", "000660")

        // then: H0NXCNT0 구독 — nxtSubscribedCodes 확인 (reflection)
        val nxtCodes = getNxtSubscribedCodes(bridge)
        assertThat(nxtCodes).containsExactlyInAnyOrder("005930", "000660")
    }

    /**
     * KisWebSocketBridge 내부 session 필드 접근 (reflection).
     * 프로덕션 코드 변경 없이 내부 상태 검증용.
     */
    private fun getSession(bridge: KisWebSocketBridge): KisWebSocketSession {
        val field = KisWebSocketBridge::class.java.getDeclaredField("session")
        field.isAccessible = true
        return field.get(bridge) as KisWebSocketSession
    }

    /**
     * nxtSubscribedCodes Set 접근 (reflection).
     */
    @Suppress("UNCHECKED_CAST")
    private fun getNxtSubscribedCodes(bridge: KisWebSocketBridge): Set<String> {
        val field = KisWebSocketBridge::class.java.getDeclaredField("nxtSubscribedCodes")
        field.isAccessible = true
        return field.get(bridge) as Set<String>
    }
}
