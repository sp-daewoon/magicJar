package kr.spdaewoon.magicjar.consumer.ws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisApprovalAdapter
import kr.spdaewoon.magicjar.consumer.order.KisExecutionWsListener
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.candle.CandleSession
import kr.spdaewoon.magicjar.domain.market.port.QuoteCachePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import java.time.Instant
import java.time.ZoneId

/**
 * KisWebSocketBridge 무tick self-heal 재구독 단위테스트 (REAL canary 2026-06-18 후성 093370 결함).
 *
 * silent 구독 누락(session.subscribedCodes에 남아 RealtimeSubscriptionRouter가 재구독을 단락하는 stuck 상태)을
 * watchdog 2차 단계 self-heal로 자가 복구하는지 검증한다.
 *
 * 검증 매트릭스 (정규장 세션 게이트 의존 — 세션 외엔 게이트 분기만 검증):
 *   - age ≥ resubscribe 임계 + cooldown 통과 → 재구독 발사(lastResubscribeAtMsByCode 마킹).
 *   - 직후 2차 점검 → cooldown 내 재발사 skip(마킹 시각 불변).
 *   - watchdog 임계 ≤ age < resubscribe 임계 → WARN만, 재구독 보류(마킹 0).
 *   - noTickResubscribeSeconds ≤ 0 → self-heal 비활성(마킹 0).
 *   - tick 복구(recordTickReceived) → cooldown 해제(마킹 제거 → 재무장).
 *
 * AI 호출 0 — 룰 5.
 */
class KisWebSocketBridgeNoTickResubscribeTest {
    private val approvalAdapter = mockk<KisApprovalAdapter>(relaxed = true)
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val quoteCachePort = mockk<QuoteCachePort>(relaxed = true)
    private val wsClient = mockk<ReactorNettyWebSocketClient>(relaxed = true)
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)

    @BeforeEach
    fun setUp() {
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        every { approvalAdapter.getApprovalKey(any(), any()) } returns "mock-approval-key"
    }

    private fun newBridge(
        noTickWatchdogSeconds: Long,
        noTickResubscribeSeconds: Long,
        noTickResubscribeCooldownMs: Long = 120_000L,
    ): KisWebSocketBridge {
        val executionListenerProvider = mockk<ObjectProvider<KisExecutionWsListener>>(relaxed = true)
        every { executionListenerProvider.getIfAvailable() } returns null
        val priceMetricsPortProvider =
            mockk<ObjectProvider<kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort>>(relaxed = true)
        every { priceMetricsPortProvider.getIfAvailable() } returns null
        return KisWebSocketBridge(
            approvalAdapter = approvalAdapter,
            redisTemplate = redisTemplate,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            liveCandleUpdater = mockk(relaxed = true),
            quoteCachePort = quoteCachePort,
            wsClient = wsClient,
            wsUrlProd = "ws://localhost:21000",
            wsUrlVps = "ws://localhost:31000",
            magicjarMode = "REAL",
            defaultUserId = "user001",
            noTickWatchdogSeconds = noTickWatchdogSeconds,
            noTickResubscribeSeconds = noTickResubscribeSeconds,
            noTickResubscribeCooldownMs = noTickResubscribeCooldownMs,
            executionListenerProvider = executionListenerProvider,
            systemEventLogger = systemEventLogger,
            priceMetricsPortProvider = priceMetricsPortProvider,
            credentialRepositoryProvider =
                mockk<ObjectProvider<ConsumerUserKisCredentialJpaRepository>>(relaxed = true)
                    .also { every { it.ifAvailable } returns null },
            slotLedgerProvider =
                mockk<ObjectProvider<kr.spdaewoon.magicjar.consumer.ingestion.WsSlotBudgetLedger>>(relaxed = true)
                    .also { every { it.ifAvailable } returns null },
        )
    }

    private fun getSession(bridge: KisWebSocketBridge): KisWebSocketSession {
        val field = KisWebSocketBridge::class.java.getDeclaredField("session")
        field.isAccessible = true
        return field.get(bridge) as KisWebSocketSession
    }

    @Suppress("UNCHECKED_CAST")
    private fun lastTickMap(bridge: KisWebSocketBridge): MutableMap<String, Long> {
        val f = KisWebSocketBridge::class.java.getDeclaredField("lastTickAtMsByCode")
        f.isAccessible = true
        return f.get(bridge) as MutableMap<String, Long>
    }

    @Suppress("UNCHECKED_CAST")
    private fun resubscribeMap(bridge: KisWebSocketBridge): MutableMap<String, Long> {
        val f = KisWebSocketBridge::class.java.getDeclaredField("lastResubscribeAtMsByCode")
        f.isAccessible = true
        return f.get(bridge) as MutableMap<String, Long>
    }

    private fun invokeRecordTickReceived(
        bridge: KisWebSocketBridge,
        code: String,
    ) {
        val m = KisWebSocketBridge::class.java.getDeclaredMethod("recordTickReceived", String::class.java)
        m.isAccessible = true
        m.invoke(bridge, code)
    }

    private fun isInSessionNow(): Boolean {
        val now = Instant.now().atZone(ZoneId.of("Asia/Seoul"))
        return CandleSession.isInIntegratedSession(now.hour * 60 + now.minute)
    }

    @Test
    fun `resubscribe 임계 초과 + cooldown 통과 시 재구독 발사 (마킹) + 직후 cooldown skip`() {
        // watchdog 1s · resubscribe 2s · cooldown 60s
        val bridge = newBridge(noTickWatchdogSeconds = 1L, noTickResubscribeSeconds = 2L, noTickResubscribeCooldownMs = 60_000L)
        getSession(bridge).onConnected()
        bridge.subscribe(listOf("093370"))
        // 5초 전 마지막 tick — resubscribe 임계(2s) 초과.
        lastTickMap(bridge)["093370"] = System.currentTimeMillis() - 5_000L

        bridge.runNoTickWatchdogCheck()

        if (isInSessionNow()) {
            // 정규장 세션 — 재구독 발사로 마킹.
            assertThat(resubscribeMap(bridge)).containsKey("093370")
            val firstMark = resubscribeMap(bridge)["093370"]!!

            // 2차 점검 — cooldown(60s) 내라 재발사 skip(마킹 시각 불변).
            bridge.runNoTickWatchdogCheck()
            assertThat(resubscribeMap(bridge)["093370"]!!).isEqualTo(firstMark)
        } else {
            // 정규장 외 — 세션 게이트로 점검 skip. 마킹 0.
            assertThat(resubscribeMap(bridge)).doesNotContainKey("093370")
        }
    }

    @Test
    fun `watchdog 임계 이상 resubscribe 임계 미만이면 WARN만 - 재구독 보류`() {
        // watchdog 1s · resubscribe 100s — age 3s는 watchdog WARN은 들어가나 resubscribe 보류.
        val bridge = newBridge(noTickWatchdogSeconds = 1L, noTickResubscribeSeconds = 100L)
        getSession(bridge).onConnected()
        bridge.subscribe(listOf("093370"))
        lastTickMap(bridge)["093370"] = System.currentTimeMillis() - 3_000L

        bridge.runNoTickWatchdogCheck()

        // 세션 여부 무관 — resubscribe 임계 미달이므로 마킹 0이어야 함.
        assertThat(resubscribeMap(bridge)).doesNotContainKey("093370")
    }

    @Test
    fun `noTickResubscribeSeconds 0 이면 self-heal 비활성 (마킹 0)`() {
        val bridge = newBridge(noTickWatchdogSeconds = 1L, noTickResubscribeSeconds = 0L)
        getSession(bridge).onConnected()
        bridge.subscribe(listOf("093370"))
        lastTickMap(bridge)["093370"] = System.currentTimeMillis() - 10_000L

        bridge.runNoTickWatchdogCheck()

        assertThat(resubscribeMap(bridge)).doesNotContainKey("093370")
    }

    @Test
    fun `tick 복구 시 self-heal cooldown 해제 (마킹 제거 - 재무장)`() {
        val bridge = newBridge(noTickWatchdogSeconds = 1L, noTickResubscribeSeconds = 2L)
        bridge.subscribe(listOf("093370"))
        // 재구독 마킹 시뮬레이션.
        resubscribeMap(bridge)["093370"] = System.currentTimeMillis()

        invokeRecordTickReceived(bridge, "093370")

        // tick 복구 → cooldown 해제(다음 stale 윈도우 즉시 재구독 가능).
        assertThat(resubscribeMap(bridge)).doesNotContainKey("093370")
    }
}
