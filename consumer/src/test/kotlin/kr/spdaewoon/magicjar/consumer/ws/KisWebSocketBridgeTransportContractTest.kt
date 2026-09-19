package kr.spdaewoon.magicjar.consumer.ws

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.config.KisWsTransportProperties
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisApprovalAdapter
import kr.spdaewoon.magicjar.consumer.order.KisExecutionWsListener
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort
import kr.spdaewoon.magicjar.domain.market.port.QuoteCachePort
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class KisWebSocketBridgeTransportContractTest {
    @ParameterizedTest
    @ValueSource(strings = ["REAL", "MOCK", "PAPER"])
    fun `market socket은 runtime mode와 무관하게 prod 21000을 선택한다`(mode: String) {
        val bridge = buildBridge(mode = mode)

        assertThat(invokePrivate<String>(bridge, "getWsUrl")).isEqualTo("ws://ops.koreainvestment.com:21000")
    }

    @ParameterizedTest
    @ValueSource(strings = ["MOCK", "PAPER"])
    fun `MOCK과 PAPER는 prod market session에 H0STCNI9을 emit하지 않는다`(mode: String) {
        val bridge = buildBridge(mode = mode, htsId = "mock-hts")

        assertThat(captureExecutionSubscribe(bridge)).isEmpty()
        val status = bridge.wsStatusSnapshot()
        assertThat(status.executionState).isEqualTo("NOT_CONFIGURED")
        assertThat(status.executionTransport).isEqualTo("NONE")
        assertThat(status.executionSubscribed).isFalse()
    }

    @Test
    fun `REAL은 explicit HTS ID가 없으면 H0STCNI0을 emit하지 않는다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "", legacyUserId = "legacy-owner")

        assertThat(captureExecutionSubscribe(bridge)).isEmpty()
        assertThat(bridge.wsStatusSnapshot().executionState).isEqualTo("NOT_CONFIGURED")
    }

    @Test
    fun `REAL은 explicit HTS ID를 H0STCNI0 tr_key로 emit한다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts", legacyUserId = "legacy-owner")

        assertThat(captureExecutionSubscribe(bridge).single())
            .contains("\"tr_id\":\"H0STCNI0\"")
            .contains("\"tr_key\":\"explicit-hts\"")
            .doesNotContain("H0STCNI9")
    }

    @Test
    fun `execution subscribe emit 실패는 attempted 또는 READY를 열지 않는다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        session(bridge).onConnected()
        val sink = Sinks.many().multicast().directBestEffort<String>()
        val method =
            KisWebSocketBridge::class.java.getDeclaredMethod(
                "subscribeExecutionNoticeIfPossible",
                String::class.java,
                Sinks.Many::class.java,
            )
        method.isAccessible = true

        method.invoke(bridge, "approval", sink)

        val status = bridge.wsStatusSnapshot()
        assertThat(status.executionSubscribed).isFalse()
        assertThat(status.executionAck).isFalse()
        assertThat(status.executionAes).isFalse()
        assertThat(status.executionDegradedReason).isEqualTo("SUBSCRIBE_NOT_ATTEMPTED")
    }

    @Test
    fun `approval은 credential owner ID와 REAL account type을 사용한다`() {
        val approvalAdapter = mockk<KisApprovalAdapter>(relaxed = true)
        every { approvalAdapter.getApprovalKey(any(), any()) } returns "approval"
        val bridge =
            buildBridge(
                mode = "MOCK",
                credentialOwnerId = "credential-owner",
                legacyUserId = "legacy-owner",
                approvalAdapter = approvalAdapter,
            )

        bridge.subscribe(listOf("005930"))

        verify { approvalAdapter.getApprovalKey("credential-owner", AccountType.REAL) }
        verify(exactly = 0) { approvalAdapter.getApprovalKey("legacy-owner", any()) }
    }

    @Test
    fun `today candle seed owner는 repository 순서와 무관하게 configured owner exact match다`() {
        val repository = mockk<ConsumerUserKisCredentialJpaRepository>()
        val other = credential("other-owner")
        val configured = credential("credential-owner")
        every { repository.findAllByAccountType("REAL") } returns listOf(other, configured)
        val bridge = buildBridge(mode = "REAL", credentialRepository = repository)

        assertThat(invokePrivate<String>(bridge, "resolveSeedUserId")).isEqualTo("credential-owner")
    }

    @Test
    fun `configured owner row가 없으면 today candle seed는 다른 REAL credential로 fallback하지 않는다`() {
        val repository = mockk<ConsumerUserKisCredentialJpaRepository>()
        every { repository.findAllByAccountType("REAL") } returns listOf(credential("other-owner"))
        val priceMetricsPort = mockk<KisRestPriceMetricsPort>(relaxed = true)
        val bridge =
            buildBridge(
                mode = "REAL",
                credentialRepository = repository,
                priceMetricsPort = priceMetricsPort,
            )

        assertThat(invokePrivate<String?>(bridge, "resolveSeedUserId")).isNull()
        bridge.subscribe(listOf("005930"))

        verify(exactly = 0) { priceMetricsPort.fetchPriceMetrics(any(), any()) }
    }

    // ── 감사 2026-09-19 §5 B — WS credential owner 자동연결 (env 의존 제거) ──────

    @Test
    fun `env owner 미설정 + 등록된 REAL이 유일하면 자동 연결한다`() {
        val repository = mockk<ConsumerUserKisCredentialJpaRepository>()
        every { repository.findAllByAccountType("REAL") } returns listOf(credential("74683223"))
        // credentialOwnerId/legacyUserId 모두 blank → env 미설정 상태
        val bridge =
            buildBridge(
                mode = "REAL",
                credentialOwnerId = "",
                legacyUserId = "",
                credentialRepository = repository,
            )

        assertThat(invokePrivate<String?>(bridge, "resolveWsCredentialOwnerId")).isEqualTo("74683223")
    }

    @Test
    fun `env owner 미설정 + REAL이 2개 이상(모호)이면 자동 연결하지 않는다`() {
        val repository = mockk<ConsumerUserKisCredentialJpaRepository>()
        every { repository.findAllByAccountType("REAL") } returns
            listOf(credential("74683223"), credential("11112222"))
        val bridge =
            buildBridge(
                mode = "REAL",
                credentialOwnerId = "",
                legacyUserId = "",
                credentialRepository = repository,
            )

        assertThat(invokePrivate<String?>(bridge, "resolveWsCredentialOwnerId")).isNull()
    }

    @Test
    fun `env owner 미설정 + REAL이 0개면 자동 연결하지 않는다`() {
        val repository = mockk<ConsumerUserKisCredentialJpaRepository>()
        every { repository.findAllByAccountType("REAL") } returns emptyList()
        val bridge =
            buildBridge(
                mode = "REAL",
                credentialOwnerId = "",
                legacyUserId = "",
                credentialRepository = repository,
            )

        assertThat(invokePrivate<String?>(bridge, "resolveWsCredentialOwnerId")).isNull()
    }

    @Test
    fun `env owner 명시 설정이 있으면 자동 연결보다 우선(exact-match 존중)한다`() {
        val repository = mockk<ConsumerUserKisCredentialJpaRepository>()
        // REAL이 여러 개여도 configured owner를 그대로 존중
        every { repository.findAllByAccountType("REAL") } returns
            listOf(credential("74683223"), credential("11112222"))
        val bridge =
            buildBridge(
                mode = "REAL",
                credentialOwnerId = "credential-owner",
                credentialRepository = repository,
            )

        assertThat(invokePrivate<String?>(bridge, "resolveWsCredentialOwnerId")).isEqualTo("credential-owner")
    }

    @Test
    fun `approval 발급 실패여도 모든 market local demand는 reconnect replay용으로 보존한다`() {
        val bridge = buildBridge(mode = "REAL", approvalFailure = true)

        bridge.subscribe(listOf("035420"))
        bridge.subscribeOrderbook(listOf("051910"))
        bridge.subscribeMarketStatus(listOf("005930"))
        bridge.subscribeOvertimeSinglePrice(listOf("000660"))

        assertThat(bridge.subscribedCodes()).containsExactly("035420")
        assertThat(bridge.subscribedOrderbookCodes()).containsExactly("051910")
        assertThat(bridge.subscribedMarketStatusCodes()).containsExactly("005930")
        assertThat(bridge.subscribedOvertimeSinglePriceCodes()).containsExactly("000660")
    }

    @Test
    fun `approval 지연 중 unsubscribe된 demand는 late subscribe를 emit하지 않는다`() {
        MarketChannel.entries.forEach(::assertNoLateSubscribe)
    }

    @Test
    fun `runtime REAL mode는 trim과 case에 무관하게 canonical snapshot으로 투영한다`() {
        val bridge = buildBridge(mode = " real ", htsId = "explicit-hts")
        session(bridge).onConnected()
        captureExecutionSubscribe(bridge)
        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
        )

        val status = bridge.wsStatusSnapshot()
        assertThat(status.executionMode).isEqualTo("REAL")
        assertThat(status.executionState).isEqualTo("READY")
    }

    @Test
    fun `execution readiness는 matching TR ACK와 AES key가 모두 있어야 READY다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        session(bridge).onConnected()
        captureExecutionSubscribe(bridge)

        assertThat(bridge.wsStatusSnapshot().executionSubscribed).isFalse()

        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0STCNI0","tr_key":"other-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
        )
        assertThat(bridge.wsStatusSnapshot().executionAck).isFalse()

        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{}}}""",
        )
        assertThat(bridge.wsStatusSnapshot().executionAck).isTrue()
        assertThat(bridge.wsStatusSnapshot().executionAes).isFalse()
        assertThat(bridge.wsStatusSnapshot().executionSubscribed).isFalse()

        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
        )
        val ready = bridge.wsStatusSnapshot()
        assertThat(ready.executionAck).isTrue()
        assertThat(ready.executionAes).isTrue()
        assertThat(ready.executionSubscribed).isTrue()
        assertThat(ready.executionState).isEqualTo("READY")

        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0STCNI0","tr_key":"other-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"6543210987654321","key":"21098765432109876543210987654321"}}}""",
        )

        assertThat(bridge.wsStatusSnapshot().executionSubscribed).isTrue()
        assertThat(bridge.executionAesMaterial())
            .isEqualTo("1234567890123456" to "12345678901234567890123456789012")
    }

    @Test
    fun `emit과 동시에 도착한 fast ACK도 attempt publish 이후 처리되어 READY를 연다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        session(bridge).onConnected()
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        sink.asFlux().subscribe {
            handleMessage(
                bridge,
                """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
            )
        }
        val method =
            KisWebSocketBridge::class.java.getDeclaredMethod(
                "subscribeExecutionNoticeIfPossible",
                String::class.java,
                Sinks.Many::class.java,
                java.lang.Long.TYPE,
            )
        method.isAccessible = true

        method.invoke(bridge, "approval", sink, 0L)

        assertThat(bridge.wsStatusSnapshot().executionState).isEqualTo("READY")
        assertThat(bridge.wsStatusSnapshot().executionAck).isTrue()
        assertThat(bridge.wsStatusSnapshot().executionAes).isTrue()
    }

    @Test
    fun `subscribe 미시도 상태의 matching ACK와 AES는 READY를 열지 않는다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        session(bridge).onConnected()

        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
        )

        val status = bridge.wsStatusSnapshot()
        assertThat(status.executionAck).isFalse()
        assertThat(status.executionAes).isFalse()
        assertThat(status.executionSubscribed).isFalse()
        assertThat(status.executionState).isEqualTo("DEGRADED")
        assertThat(status.executionDegradedReason).isEqualTo("SUBSCRIBE_NOT_ATTEMPTED")
    }

    @Test
    fun `decoy 성공 필드와 execution channel은 READY를 열지 않는다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        session(bridge).onConnected()
        captureExecutionSubscribe(bridge)

        handleMessage(
            bridge,
            """{"decoy":{"tr_id":"H0STCNI0","tr_key":"explicit-hts","rt_cd":"0"},"header":{"tr_id":"H0UNASP0","tr_key":"005930"},"body":{"rt_cd":"1","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
        )

        val status = bridge.wsStatusSnapshot()
        assertThat(status.executionAck).isFalse()
        assertThat(status.executionAes).isFalse()
        assertThat(status.executionState).isEqualTo("DEGRADED")
    }

    @Test
    fun `성공 응답이어도 wrong TR ACK는 READY를 열지 않는다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        session(bridge).onConnected()
        captureExecutionSubscribe(bridge)

        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0UNASP0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
        )

        assertThat(bridge.wsStatusSnapshot().executionAck).isFalse()
        assertThat(bridge.wsStatusSnapshot().executionState).isEqualTo("DEGRADED")
    }

    @Test
    fun `성공 unsubscribe ACK는 일반 success보다 먼저 destroy latch를 해제한다`() {
        val bridge = buildBridge(mode = "REAL")
        val latch = CountDownLatch(1)

        @Suppress("UNCHECKED_CAST")
        val pending = privateField(bridge, "pendingUnsubAckLatch") as AtomicReference<CountDownLatch?>
        pending.set(latch)

        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0UNCNT0","tr_key":"005930"},"body":{"rt_cd":"0","msg_cd":"OPSP0002","msg1":"UNSUBSCRIBE SUCCESS"}}""",
        )

        assertThat(latch.count).isZero()
    }

    @Test
    fun `5개 채널 unsubscribe outbound JSON은 각각 tr_type 0이다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        val messages = mutableListOf<String>()
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        sink.asFlux().subscribe(messages::add)
        setPrivateField(bridge, "sendSink", sink)
        session(bridge).onConnected()
        bridge.subscribe(listOf("005930"))
        bridge.subscribeOrderbook(listOf("005930"))
        bridge.subscribeMarketStatus(listOf("005930"))
        bridge.subscribeOvertimeSinglePrice(listOf("005930"))
        messages.clear()

        bridge.unsubscribe(listOf("005930"))
        bridge.unsubscribeOrderbook(listOf("005930"))
        bridge.unsubscribeMarketStatus(listOf("005930"))
        bridge.unsubscribeOvertimeSinglePrice(listOf("005930"))
        invokePrivate<Any?>(bridge, "unsubscribeExecutionNoticeIfPossible", "approval")

        val outbound =
            messages.map { json ->
                val root = ObjectMapper().readTree(json)
                Triple(
                    root.path("header").path("tr_type").asText(),
                    root
                        .path("body")
                        .path("input")
                        .path("tr_id")
                        .asText(),
                    root
                        .path("body")
                        .path("input")
                        .path("tr_key")
                        .asText(),
                )
            }
        assertThat(outbound)
            .containsExactlyInAnyOrder(
                Triple("0", "H0UNCNT0", "005930"),
                Triple("0", "H0UNASP0", "005930"),
                Triple("0", "H0UNMKO0", "005930"),
                Triple("0", "H0STOUP0", "005930"),
                Triple("0", "H0STCNI0", "explicit-hts"),
            )
    }

    @Test
    fun `execution notice만 남은 현재 generation도 destroy에서 unsubscribe한다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        val messages = mutableListOf<String>()
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        sink.asFlux().subscribe { message ->
            messages.add(message)
            val root = ObjectMapper().readTree(message)
            if (root.path("header").path("tr_type").asText() == "0") {
                handleMessage(
                    bridge,
                    """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0002","msg1":"UNSUBSCRIBE SUCCESS"}}""",
                )
            }
        }
        setPrivateField(bridge, "sendSink", sink)
        session(bridge).onConnected()
        captureExecutionSubscribe(bridge)

        bridge.destroy()

        assertThat(messages).anyMatch { message ->
            val root = ObjectMapper().readTree(message)
            root.path("header").path("tr_type").asText() == "0" &&
                root
                    .path("body")
                    .path("input")
                    .path("tr_id")
                    .asText() == "H0STCNI0"
        }
    }

    @Test
    fun `이전 connection generation의 matching ACK는 READY를 열지 않는다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        session(bridge).onConnected()
        captureExecutionSubscribe(bridge)
        setAtomicLong(bridge, "connectionGeneration", 1L)

        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
            generation = 0L,
        )

        val status = bridge.wsStatusSnapshot()
        assertThat(status.executionAck).isFalse()
        assertThat(status.executionAes).isFalse()
        assertThat(status.executionSubscribed).isFalse()
        assertThat(status.executionState).isEqualTo("DEGRADED")
    }

    @Test
    fun `이전 connection generation의 실제 terminal callback은 current READY를 reset하지 않는다`() {
        val terminalSink = AtomicReference<reactor.core.publisher.MonoSink<Void>>()
        val subscribed = CountDownLatch(1)
        val wsClient = mockk<ReactorNettyWebSocketClient>()
        every { wsClient.execute(any<java.net.URI>(), any()) } returns
            Mono.create<Void> { sink ->
                terminalSink.set(sink)
                subscribed.countDown()
            }
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts", wsClient = wsClient)

        try {
            bridge.connect()
            assertThat(subscribed.await(2, TimeUnit.SECONDS)).isTrue()
            setAtomicLong(bridge, "connectionGeneration", 2L)
            session(bridge).onConnected()
            captureExecutionSubscribe(bridge)
            handleMessage(
                bridge,
                """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
                generation = 2L,
            )
            assertThat(bridge.wsStatusSnapshot().executionState).isEqualTo("READY")

            terminalSink.get().success()

            assertThat(bridge.wsStatusSnapshot().executionState).isEqualTo("READY")
            assertThat(session(bridge).connected).isTrue()
        } finally {
            bridge.destroy()
        }
    }

    @Test
    fun `graceful reconnect 실제 경로는 execution READY를 reset한다`() {
        val subscribed = CountDownLatch(1)
        val wsClient = mockk<ReactorNettyWebSocketClient>()
        every { wsClient.execute(any<java.net.URI>(), any()) } returns
            Mono.never<Void>().doOnSubscribe { subscribed.countDown() }
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts", wsClient = wsClient)

        try {
            bridge.connect()
            assertThat(subscribed.await(2, TimeUnit.SECONDS)).isTrue()
            session(bridge).onConnected()
            captureExecutionSubscribe(bridge)
            handleMessage(
                bridge,
                """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
                generation = 1L,
            )
            assertThat(bridge.wsStatusSnapshot().executionState).isEqualTo("READY")

            bridge.reconnectGracefully("credential-owner")

            assertThat(bridge.wsStatusSnapshot().executionState).isEqualTo("DEGRADED")
            assertThat(bridge.wsStatusSnapshot().executionAck).isFalse()
            assertThat(bridge.wsStatusSnapshot().executionAes).isFalse()
        } finally {
            bridge.destroy()
        }
    }

    @Test
    fun `shutdown 실제 경로는 execution READY를 reset한다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        session(bridge).onConnected()
        captureExecutionSubscribe(bridge)
        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
        )
        assertThat(bridge.wsStatusSnapshot().executionState).isEqualTo("READY")

        bridge.destroy()

        assertThat(bridge.wsStatusSnapshot().executionState).isEqualTo("DEGRADED")
        assertThat(bridge.wsStatusSnapshot().executionAck).isFalse()
        assertThat(bridge.wsStatusSnapshot().executionAes).isFalse()
    }

    @Test
    fun `잘못된 길이의 AES material은 ACK여도 READY를 열지 않는다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        session(bridge).onConnected()
        captureExecutionSubscribe(bridge)

        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"short-iv","key":"short-key"}}}""",
        )

        val status = bridge.wsStatusSnapshot()
        assertThat(status.executionAck).isTrue()
        assertThat(status.executionAes).isFalse()
        assertThat(status.executionSubscribed).isFalse()
        assertThat(status.executionState).isEqualTo("DEGRADED")
        assertThat(status.executionDegradedReason).isEqualTo("AES_KEY_PENDING")
    }

    @Test
    fun `execution readiness snapshot과 ACK AES 갱신은 동일 atomic lock을 공유한다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        session(bridge).onConnected()
        captureExecutionSubscribe(bridge)
        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
        )
        assertThat(bridge.wsStatusSnapshot().executionState).isEqualTo("READY")

        val lock = privateField(bridge, "executionReadinessLock")
        val started = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            lateinit var snapshotFuture: java.util.concurrent.Future<WsStatusSnapshot>
            lateinit var ackFuture: java.util.concurrent.Future<*>
            synchronized(lock) {
                snapshotFuture =
                    executor.submit<WsStatusSnapshot> {
                        started.countDown()
                        bridge.wsStatusSnapshot()
                    }
                ackFuture =
                    executor.submit {
                        started.countDown()
                        handleMessage(
                            bridge,
                            """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","output":{"iv":"short","key":"short"}}}""",
                        )
                    }
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue()
                assertThat(snapshotFuture.isDone).isFalse()
                assertThat(ackFuture.isDone).isFalse()
            }

            snapshotFuture.get(1, TimeUnit.SECONDS)
            ackFuture.get(1, TimeUnit.SECONDS)
            val degraded = bridge.wsStatusSnapshot()
            assertThat(degraded.executionState).isEqualTo("DEGRADED")
            assertThat(degraded.executionAes).isFalse()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `disconnect는 execution ACK와 AES readiness를 reset한다`() {
        val bridge = buildBridge(mode = "REAL", htsId = "explicit-hts")
        session(bridge).onConnected()
        captureExecutionSubscribe(bridge)
        handleMessage(
            bridge,
            """{"header":{"tr_id":"H0STCNI0","tr_key":"explicit-hts"},"body":{"rt_cd":"0","output":{"iv":"1234567890123456","key":"12345678901234567890123456789012"}}}""",
        )
        assertThat(bridge.wsStatusSnapshot().executionSubscribed).isTrue()

        invokePrivate<Unit>(bridge, "resetExecutionReadiness")
        session(bridge).onDisconnected()

        val disconnected = bridge.wsStatusSnapshot()
        assertThat(disconnected.executionAck).isFalse()
        assertThat(disconnected.executionAes).isFalse()
        assertThat(disconnected.executionSubscribed).isFalse()
    }

    @Test
    fun `subscription response log summary는 HTS ID와 AES material을 노출하지 않는다`() {
        val bridge = buildBridge(mode = "REAL")
        val raw =
            """{"header":{"tr_id":"H0STCNI0","tr_key":"secret-hts-id"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","msg1":"secret-message","output":{"iv":"secret-iv","key":"secret-aes-key"}}}"""

        val summary = invokePrivate<String>(bridge, "subscriptionResponseSummary", raw)

        assertThat(summary)
            .isEqualTo("trId=H0STCNI0,rtCd=0,msgCd=OPSP0000")
            .doesNotContain("secret-hts-id", "secret-message", "secret-iv", "secret-aes-key")
    }

    @Test
    fun `unknown frame log는 raw content를 노출하지 않는다`() {
        val bridge = buildBridge(mode = "REAL")
        val logger = LoggerFactory.getLogger(KisWebSocketBridge::class.java) as Logger
        val parserLogger = LoggerFactory.getLogger(KisWsMessageParser::class.java) as Logger
        val originalLevel = logger.level
        val originalParserLevel = parserLogger.level
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.level = Level.DEBUG
        parserLogger.level = Level.DEBUG
        logger.addAppender(appender)
        parserLogger.addAppender(appender)

        try {
            handleMessage(bridge, "X|unexpected|1|secret-frame-marker")
        } finally {
            logger.detachAppender(appender)
            parserLogger.detachAppender(appender)
            logger.level = originalLevel
            parserLogger.level = originalParserLevel
            appender.stop()
        }

        val messages = appender.list.map { it.formattedMessage }
        assertThat(messages).anyMatch { it.startsWith("KIS WS 알 수 없는 메시지 수신: length=") }
        assertThat(messages).noneMatch { it.contains("secret-frame-marker") }
    }

    private fun captureExecutionSubscribe(bridge: KisWebSocketBridge): List<String> {
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        val messages = mutableListOf<String>()
        sink.asFlux().subscribe(messages::add)
        val method =
            KisWebSocketBridge::class.java.getDeclaredMethod(
                "subscribeExecutionNoticeIfPossible",
                String::class.java,
                Sinks.Many::class.java,
            )
        method.isAccessible = true
        method.invoke(bridge, "approval", sink)
        return messages
    }

    private fun handleMessage(
        bridge: KisWebSocketBridge,
        raw: String,
    ) {
        val method = KisWebSocketBridge::class.java.getDeclaredMethod("handleMessage", String::class.java)
        method.isAccessible = true
        method.invoke(bridge, raw)
    }

    private fun handleMessage(
        bridge: KisWebSocketBridge,
        raw: String,
        generation: Long,
    ) {
        val method =
            KisWebSocketBridge::class.java.getDeclaredMethod(
                "handleMessage",
                String::class.java,
                java.lang.Long.TYPE,
            )
        method.isAccessible = true
        method.invoke(bridge, raw, generation)
    }

    private fun setAtomicLong(
        bridge: KisWebSocketBridge,
        name: String,
        value: Long,
    ) {
        val field = KisWebSocketBridge::class.java.getDeclaredField(name)
        field.isAccessible = true
        (field.get(bridge) as AtomicLong).set(value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun KisWebSocketBridge.executionAesMaterial(): Pair<String, String>? {
        val field = KisWebSocketBridge::class.java.getDeclaredField("trIdToAesKey")
        field.isAccessible = true
        val material = (field.get(this) as Map<String, Any>)["H0STCNI0"] ?: return null
        val ivField = material.javaClass.getDeclaredField("iv").also { it.isAccessible = true }
        val keyField = material.javaClass.getDeclaredField("key").also { it.isAccessible = true }
        return ivField.get(material) as String to keyField.get(material) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokePrivate(
        bridge: KisWebSocketBridge,
        name: String,
    ): T {
        val method = KisWebSocketBridge::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        return method.invoke(bridge) as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokePrivate(
        bridge: KisWebSocketBridge,
        name: String,
        argument: String,
    ): T {
        val method = KisWebSocketBridge::class.java.getDeclaredMethod(name, String::class.java)
        method.isAccessible = true
        return method.invoke(bridge, argument) as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokePrivate(
        bridge: KisWebSocketBridge,
        name: String,
        argument: Long,
    ): T {
        val method = KisWebSocketBridge::class.java.getDeclaredMethod(name, Long::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(bridge, argument) as T
    }

    private fun privateField(
        bridge: KisWebSocketBridge,
        name: String,
    ): Any {
        val field = KisWebSocketBridge::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(bridge)
    }

    private fun setPrivateField(
        bridge: KisWebSocketBridge,
        name: String,
        value: Any,
    ) {
        val field = KisWebSocketBridge::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(bridge, value)
    }

    private fun session(bridge: KisWebSocketBridge): KisWebSocketSession {
        val field = KisWebSocketBridge::class.java.getDeclaredField("session")
        field.isAccessible = true
        return field.get(bridge) as KisWebSocketSession
    }

    private fun buildBridge(
        mode: String,
        credentialOwnerId: String = "credential-owner",
        htsId: String = "explicit-hts",
        legacyUserId: String = "",
        approvalAdapter: KisApprovalAdapter = mockk(relaxed = true),
        approvalFailure: Boolean = false,
        approvalConfiguredExternally: Boolean = false,
        wsClient: ReactorNettyWebSocketClient = mockk(relaxed = true),
        credentialRepository: ConsumerUserKisCredentialJpaRepository? = null,
        priceMetricsPort: KisRestPriceMetricsPort? = null,
    ): KisWebSocketBridge {
        if (approvalConfiguredExternally) {
            // caller-provided concurrent/error answer 유지
        } else if (approvalFailure) {
            every { approvalAdapter.getApprovalKey(any(), any()) } throws IllegalStateException("approval unavailable")
        } else {
            every { approvalAdapter.getApprovalKey(any(), any()) } returns "approval"
        }
        return KisWebSocketBridge(
            approvalAdapter = approvalAdapter,
            redisTemplate = mockk(relaxed = true),
            kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true),
            objectMapper = ObjectMapper().registerKotlinModule(),
            liveCandleUpdater = mockk(relaxed = true),
            quoteCachePort = mockk<QuoteCachePort>(relaxed = true),
            wsClient = wsClient,
            wsTransport =
                KisWsTransportProperties(
                    urlProd = "ws://ops.koreainvestment.com:21000",
                    credentialOwnerId = credentialOwnerId,
                    htsId = htsId,
                    userId = legacyUserId,
                ),
            magicjarMode = mode,
            executionListenerProvider = provider<KisExecutionWsListener>(null),
            systemEventLogger = mockk<SystemEventLogger>(relaxed = true),
            priceMetricsPortProvider = provider(priceMetricsPort),
            credentialRepositoryProvider = provider(credentialRepository),
            slotLedgerProvider = provider(null),
        )
    }

    private fun <T> provider(value: T?): ObjectProvider<T> =
        mockk<ObjectProvider<T>>(relaxed = true).also { every { it.ifAvailable } returns value }

    private fun credential(userId: String) =
        ConsumerUserKisCredentialJpaEntity(
            id = ConsumerUserKisCredentialId(userId = userId, accountType = "REAL"),
            accountNumber = userId,
            appKeyEnc = byteArrayOf(1),
            secretKeyEnc = byteArrayOf(1),
        )

    private fun assertNoLateSubscribe(channel: MarketChannel) {
        val firstApprovalStarted = CountDownLatch(1)
        val releaseFirstApproval = CountDownLatch(1)
        val calls = AtomicInteger()
        val approvalAdapter = mockk<KisApprovalAdapter>()
        every { approvalAdapter.getApprovalKey(any(), any()) } answers {
            if (calls.incrementAndGet() == 1) {
                firstApprovalStarted.countDown()
                releaseFirstApproval.await(2, TimeUnit.SECONDS)
            }
            "approval"
        }
        val bridge =
            buildBridge(
                mode = "REAL",
                approvalAdapter = approvalAdapter,
                approvalConfiguredExternally = true,
            )
        val messages = mutableListOf<String>()
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        sink.asFlux().subscribe(messages::add)
        setPrivateField(bridge, "sendSink", sink)
        session(bridge).onConnected()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val subscribeFuture =
                executor.submit {
                    when (channel) {
                        MarketChannel.TICK -> bridge.subscribe(listOf("005930"))
                        MarketChannel.ORDERBOOK -> bridge.subscribeOrderbook(listOf("005930"))
                        MarketChannel.MARKET_STATUS -> bridge.subscribeMarketStatus(listOf("005930"))
                        MarketChannel.OVERTIME -> bridge.subscribeOvertimeSinglePrice(listOf("005930"))
                    }
                }
            assertThat(firstApprovalStarted.await(2, TimeUnit.SECONDS)).isTrue()
            when (channel) {
                MarketChannel.TICK -> bridge.unsubscribe(listOf("005930"))
                MarketChannel.ORDERBOOK -> bridge.unsubscribeOrderbook(listOf("005930"))
                MarketChannel.MARKET_STATUS -> bridge.unsubscribeMarketStatus(listOf("005930"))
                MarketChannel.OVERTIME -> bridge.unsubscribeOvertimeSinglePrice(listOf("005930"))
            }
            releaseFirstApproval.countDown()
            subscribeFuture.get(2, TimeUnit.SECONDS)

            assertThat(messages).noneMatch { it.contains("\"tr_type\":\"1\"") }
            assertThat(
                when (channel) {
                    MarketChannel.TICK -> bridge.subscribedCodes()
                    MarketChannel.ORDERBOOK -> bridge.subscribedOrderbookCodes()
                    MarketChannel.MARKET_STATUS -> bridge.subscribedMarketStatusCodes()
                    MarketChannel.OVERTIME -> bridge.subscribedOvertimeSinglePriceCodes()
                },
            ).isEmpty()
        } finally {
            releaseFirstApproval.countDown()
            executor.shutdownNow()
        }
    }

    private enum class MarketChannel {
        TICK,
        ORDERBOOK,
        MARKET_STATUS,
        OVERTIME,
    }
}
