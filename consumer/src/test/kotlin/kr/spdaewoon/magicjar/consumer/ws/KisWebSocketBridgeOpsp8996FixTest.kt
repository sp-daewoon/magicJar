package kr.spdaewoon.magicjar.consumer.ws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.config.KisWsTransportProperties
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisApprovalAdapter
import kr.spdaewoon.magicjar.consumer.order.KisExecutionWsListener
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.port.QuoteCachePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * ADR 0077 G-1 + H-1 + I-1 + J-1 fix 단위 테스트 (v3.6.11 hotfix).
 *
 * 검증 매트릭스:
 * - test 1 (G-1): 자격증명 0건 → getApprovalKey() null → doConnect() skip + OPSP8996 발생 0건
 * - test 2 (H-1): MOCK 자격증명만 있어도 시세 WS approval_key는 REAL AccountType 사용 시도 검증 (REAL 고정)
 * - test 3 (ADR 0142): magicjarMode=MOCK여도 market-data WS endpoint는 prod(:21000) 고정.
 *   H0STCNI9 무발사는 TransportContractTest가 검증. + test 3b: REAL → prod(:21000).
 * - test 4 (I-1): OPSP8996 attempt > 3 → dispose + 알림 (cap 3 LOCK)
 * - test 5 (I-1): WS subscribe 성공 → opsp8996RetryCount.get() == 0 검증 (reset)
 *
 * KIS-first 출처 (ADR 0077 §13):
 * - auth_ws None 반환 패턴: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
 * - KISWebSocket max_retries=3: 동일 URL
 *
 * AI 호출 0 — 룰 5 정합.
 */
class KisWebSocketBridgeOpsp8996FixTest {
    private val approvalAdapter = mockk<KisApprovalAdapter>(relaxed = true)
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val quoteCachePort = mockk<QuoteCachePort>(relaxed = true)
    private val wsClient = mockk<ReactorNettyWebSocketClient>(relaxed = true)
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)

    private lateinit var bridge: KisWebSocketBridge

    @BeforeEach
    fun setUp() {
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        every { wsClient.execute(any<URI>(), any()) } returns Mono.never()
    }

    /**
     * test 1 (G-1): 자격증명 미등록 시 getApprovalKey() null → doConnect() skip.
     *
     * ADR 0077 G-1 fix 검증:
     * - approvalAdapter.getApprovalKey() 가 IllegalStateException 던짐 → null 반환
     * - doConnect() 내 null 체크 → connect skip → wsClient.execute() 미호출
     * - "ERROR_APPROVAL_KEY" 문자열로 KIS에 subscribe 발사 없음 → OPSP8996 발생 0건
     *
     * KIS-first 근거: kis_auth.py auth_ws() — 실패 시 None 반환, 호출자 None 체크 후 connect skip.
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     */
    @Test
    fun `G-1 REAL 자격증명 0건 시 getApprovalKey null 반환 doConnect skip`() {
        // given: REAL 자격증명 미등록 — IllegalStateException 발생
        every {
            approvalAdapter.getApprovalKey(any(), any())
        } throws IllegalStateException("KIS 자격증명 없음 — userId=user001 type=REAL")

        bridge = buildBridge(defaultUserId = "user001")

        // when: connect() 호출
        bridge.connect()

        // then: wsClient.execute() 미호출 — doConnect() null 체크로 connect skip
        // "ERROR_APPROVAL_KEY" 문자열로 KIS 발사 0건 → OPSP8996 트리거 불가
        verify(exactly = 0) { wsClient.execute(any<URI>(), any()) }
    }

    /**
     * test 2 (H-1): 시세 WS는 AccountType.REAL 고정 — magicjarMode 무관.
     *
     * ADR 0077 H-1 fix 검증:
     * - magicjarMode = "MOCK" (비REAL 환경) 에서도 approvalAdapter.getApprovalKey는 REAL로 호출
     * - 사용자 메모리 [feedback_kis_key_realonly_for_data]: "시세·캔들·호가 정보 수집=실전 키"
     * - 이전 결함: magicjarMode != "REAL" → MOCK 분기 → MOCK 자격증명으로 발급 시도 → OPSP8996
     *
     * KIS-first 근거: kis_auth.py 예제는 모두 REAL 환경 기준.
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     */
    @Test
    fun `H-1 magicjarMode MOCK 환경에서도 시세 WS는 REAL AccountType 고정`() {
        // given: REAL 자격증명 정상 등록 (MOCK 자격증명은 없음)
        every {
            approvalAdapter.getApprovalKey("user001", kr.spdaewoon.magicjar.domain.user.AccountType.REAL)
        } returns "real-approval-key"
        every {
            approvalAdapter.getApprovalKey("user001", kr.spdaewoon.magicjar.domain.user.AccountType.MOCK)
        } throws IllegalStateException("KIS 자격증명 없음 — type=MOCK")

        // given: magicjarMode = "MOCK" (비REAL 환경)
        bridge = buildBridge(defaultUserId = "user001", magicjarMode = "MOCK")

        // when: subscribe 호출 (getApprovalKey() 내부에서 accountType 결정)
        bridge.subscribe(listOf("005930"))

        // then: REAL AccountType으로 approvalAdapter 호출 — MOCK 자격증명 시도 0건
        // H-1 fix: AccountType.REAL 고정 → magicjarMode 무관
        verify(exactly = 1) {
            approvalAdapter.getApprovalKey("user001", kr.spdaewoon.magicjar.domain.user.AccountType.REAL)
        }
        verify(exactly = 0) {
            approvalAdapter.getApprovalKey("user001", kr.spdaewoon.magicjar.domain.user.AccountType.MOCK)
        }
    }

    /**
     * test 3: ADR 0142 — MOCK 주문 mode도 market-data 물리 session은 REAL/prod(:21000)로 고정한다.
     *
     * H0STCNI9은 이 prod session에 co-subscribe하지 않는다. outbound frame 무발사 검증은
     * KisWebSocketBridgeTransportContractTest가 담당하며, 본 테스트는 endpoint assignment만 고정한다.
     */
    @Test
    fun `MOCK 주문 mode도 market data WS endpoint는 prod 21000`() {
        every {
            approvalAdapter.getApprovalKey("user001", kr.spdaewoon.magicjar.domain.user.AccountType.REAL)
        } returns "real-approval-key"

        bridge = buildBridge(defaultUserId = "user001", magicjarMode = "MOCK")

        bridge.connect()

        verify(timeout = 3_000L, exactly = 1) {
            wsClient.execute(match<URI> { it.toString() == "ws://ops.koreainvestment.com:21000" }, any())
        }
        verify(exactly = 0) {
            wsClient.execute(match<URI> { it.toString() == "ws://ops.koreainvestment.com:31000" }, any())
        }
    }

    /**
     * test 3b: REAL 모드 WS endpoint는 prod(:21000) — 실전 체결통보 H0STCNI0 발송 서버 정합.
     */
    @Test
    fun `REAL 모드 WS endpoint는 prod(21000)`() {
        every {
            approvalAdapter.getApprovalKey("user001", kr.spdaewoon.magicjar.domain.user.AccountType.REAL)
        } returns "real-approval-key"

        bridge = buildBridge(defaultUserId = "user001", magicjarMode = "REAL")

        bridge.connect()

        verify(timeout = 3_000L, exactly = 1) {
            wsClient.execute(match<URI> { it.toString() == "ws://ops.koreainvestment.com:21000" }, any())
        }
        verify(exactly = 0) {
            wsClient.execute(match<URI> { it.toString() == "ws://ops.koreainvestment.com:31000" }, any())
        }
    }

    /**
     * test 4 (I-1): OPSP8996 attempt=4 → cap 3 초과 → connection dispose + retry counter reset.
     *
     * ADR 0077 I-1 cap 3 fix 검증:
     * - opsp8996RetryCount가 4 이상 → KIS 표준 max_retries=3 cap 초과
     * - handleOpsp8996() → wsDisposable.dispose() 호출 + counter reset(0)
     * - 이전 결함: attempt > 1 시 reset 없이 ERROR + skip → attempt 무한 누적 (attempt=69~85 evidence)
     *
     * KIS-first 근거: KISWebSocket max_retries=3 (생성자 기본값).
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     */
    @Test
    fun `I-1 OPSP8996 attempt 4 cap 3 초과 시 counter reset 0 검증`() {
        // given: REAL 자격증명 정상 (subscribe 가능 상태)
        every {
            approvalAdapter.getApprovalKey(any(), any())
        } returns "approval-key-test"

        bridge = buildBridge(defaultUserId = "user001")

        // given: opsp8996RetryCount를 3으로 설정 (cap 3에서 한 번 더 increment되면 4 > 3)
        val retryCountField = KisWebSocketBridge::class.java.getDeclaredField("opsp8996RetryCount")
        retryCountField.isAccessible = true
        val retryCount = retryCountField.get(bridge) as java.util.concurrent.atomic.AtomicInteger
        retryCount.set(3) // increment 후 4 → cap 3 초과 분기 진입

        // given: subscribe로 session에 종목 추가
        bridge.subscribe(listOf("005930"))

        // when: OPSP8996 응답 처리 (handleOpsp8996 직접 접근 — reflection)
        // handleOpsp8996은 private이므로 retryCount 상태 변화로 cap 3 로직 간접 검증
        // cap 3 초과 시 retryCount.set(0) 호출 → counter가 0으로 reset됨
        val handleOpsp8996 = KisWebSocketBridge::class.java.getDeclaredMethod("handleOpsp8996", String::class.java)
        handleOpsp8996.isAccessible = true
        val opsp8996Json = """{"header":{},"body":{"rt_cd":"1","msg_cd":"OPSP8996","msg1":"ALREADY IN USE appkey"}}"""
        handleOpsp8996.invoke(bridge, opsp8996Json)

        // then: attempt=4 (>3) → counter reset(0) 호출
        // I-1 fix: cap 3 초과 시 opsp8996RetryCount.set(0) + wsDisposable.dispose()
        assertThat(retryCount.get()).isEqualTo(0)
    }

    @Test
    fun `OPSP8996 retry는 unsubscribe와 동일 transition lock으로 직렬화된다`() {
        val approvalLookupReached = CountDownLatch(1)
        val approvalCalls = AtomicInteger(0)
        every { approvalAdapter.getApprovalKey(any(), any()) } answers {
            if (approvalCalls.incrementAndGet() == 2) {
                approvalLookupReached.countDown()
            }
            "approval-key-test"
        }
        bridge = buildBridge(defaultUserId = "user001")
        bridge.subscribe(listOf("005930"))

        val retryCountField = KisWebSocketBridge::class.java.getDeclaredField("opsp8996RetryCount")
        retryCountField.isAccessible = true
        val retryCount = retryCountField.get(bridge) as java.util.concurrent.atomic.AtomicInteger
        val stateLockField = KisWebSocketBridge::class.java.getDeclaredField("subscriptionStateLock")
        stateLockField.isAccessible = true
        val stateLock = stateLockField.get(bridge)
        val failure = AtomicReference<Throwable?>()
        val handleOpsp8996 = KisWebSocketBridge::class.java.getDeclaredMethod("handleOpsp8996", String::class.java)
        handleOpsp8996.isAccessible = true
        val opsp8996Json =
            """{"header":{},"body":{"rt_cd":"1","msg_cd":"OPSP8996","msg1":"ALREADY IN USE appkey"}}"""
        val retryThread =
            Thread {
                try {
                    handleOpsp8996.invoke(bridge, opsp8996Json)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

        synchronized(stateLock) {
            retryThread.start()
            assertThat(approvalLookupReached.await(3, TimeUnit.SECONDS)).isTrue()
            bridge.unsubscribe(listOf("005930"))
        }
        retryThread.join(3_000L)

        assertThat(retryThread.isAlive).isFalse()
        assertThat(failure.get()).isNull()
        assertThat(retryCount.get()).isZero()
    }

    @Test
    fun `send sink emit 실패는 connection을 폐기해 remote local divergence를 차단한다`() {
        every { approvalAdapter.getApprovalKey(any(), any()) } returns "approval-key-test"
        bridge = buildBridge(defaultUserId = "user001")
        val sink = Sinks.many().multicast().directBestEffort<String>()
        val disposable = mockk<Disposable>(relaxed = true)
        setPrivateField("sendSink", sink)
        setPrivateField("wsDisposable", disposable)
        val sendTickSubscribe =
            KisWebSocketBridge::class.java.getDeclaredMethod(
                "sendTickSubscribe",
                String::class.java,
                String::class.java,
                String::class.java,
            )
        sendTickSubscribe.isAccessible = true

        sendTickSubscribe.invoke(bridge, "approval-key-test", "005930", "1")

        verify(exactly = 1) { disposable.dispose() }
    }

    @Test
    fun `OPSP0008 state 보정은 subscribe unsubscribe transition lock과 직렬화된다`() {
        every { approvalAdapter.getApprovalKey(any(), any()) } returns "approval-key-test"
        bridge = buildBridge(defaultUserId = "user001")
        bridge.subscribe(listOf("005930"))
        val stateLockField = KisWebSocketBridge::class.java.getDeclaredField("subscriptionStateLock")
        stateLockField.isAccessible = true
        val stateLock = stateLockField.get(bridge)
        val fallbackCalled = CountDownLatch(1)
        bridge.onSubscribeRejected = { _, _ -> fallbackCalled.countDown() }
        val failure = AtomicReference<Throwable?>()
        val handleOpsp0008 = KisWebSocketBridge::class.java.getDeclaredMethod("handleOpsp0008", String::class.java)
        handleOpsp0008.isAccessible = true
        val response =
            """{"header":{"tr_id":"H0UNCNT0","tr_key":"005930"},"body":{"rt_cd":"1","msg_cd":"OPSP0008","msg1":"MAX SUBSCRIBE OVER"}}"""
        val rejectionThread =
            Thread {
                try {
                    handleOpsp0008.invoke(bridge, response)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

        synchronized(stateLock) {
            rejectionThread.start()
            assertThat(fallbackCalled.await(500, TimeUnit.MILLISECONDS)).isFalse()
            assertThat(bridge.subscribedCodes()).contains("005930")
        }
        assertThat(fallbackCalled.await(3, TimeUnit.SECONDS)).isTrue()
        rejectionThread.join(3_000L)

        assertThat(failure.get()).isNull()
        assertThat(bridge.subscribedCodes()).doesNotContain("005930")
    }

    private fun setPrivateField(
        name: String,
        value: Any,
    ) {
        val field = KisWebSocketBridge::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(bridge, value)
    }

    /**
     * test 4 (I-1): WS subscribe 성공 응답 → opsp8996RetryCount reset(0) 검증.
     *
     * ADR 0077 I-1 counter reset fix 검증:
     * - subscribe 성공 시 handleMessage()에서 opsp8996RetryCount.set(0) 호출
     * - 동일 connection 내 OPSP8996 사이클 분리 — 성공 후 다음 OPSP8996은 attempt=1부터 재시작
     * - 이전 결함: reset 없이 attempt 무한 누적 → evidence attempt=69~85
     *
     * KIS-first 근거: KISWebSocket retry_count는 connection 단위 — 성공 시 사이클 분리 의미.
     * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     */
    @Test
    fun `I-1 subscribe 성공 응답 수신 시 opsp8996RetryCount reset 0 검증`() {
        // given: REAL 자격증명 정상
        every {
            approvalAdapter.getApprovalKey(any(), any())
        } returns "approval-key-test"

        bridge = buildBridge(defaultUserId = "user001")

        // given: opsp8996RetryCount를 2로 설정 (OPSP8996이 발생했다가 회복 중인 상태)
        val retryCountField = KisWebSocketBridge::class.java.getDeclaredField("opsp8996RetryCount")
        retryCountField.isAccessible = true
        val retryCount = retryCountField.get(bridge) as java.util.concurrent.atomic.AtomicInteger
        retryCount.set(2)

        // when: subscribe 성공 응답 JSON으로 handleMessage 직접 호출
        val handleMessage = KisWebSocketBridge::class.java.getDeclaredMethod("handleMessage", String::class.java)
        handleMessage.isAccessible = true
        // rt_cd="0" + msg_cd != OPSP8996 → isSubscriptionSuccess=true → counter reset
        val successJson =
            """{"header":{"tr_id":"H0UNCNT0","tr_key":"005930"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","msg1":"SUBSCRIBE SUCCESS","output":{}}}"""
        handleMessage.invoke(bridge, successJson)

        // then: subscribe 성공 → opsp8996RetryCount.get() == 0 (reset 확인)
        // I-1 fix: subscribe 성공 시 reset(0) → 이전 OPSP8996 누적 attempt 소거
        assertThat(retryCount.get()).isEqualTo(0)
    }

    // ===== 헬퍼 =====

    private fun buildBridge(
        defaultUserId: String = "user001",
        magicjarMode: String = "REAL",
    ): KisWebSocketBridge {
        // T-O Phase 1 B-1: executionListenerProvider 추가 — 본 테스트는 체결통보 wiring 영역 외이므로 null 반환 stub.
        // ADR 0068 §14-6 정합 — 미합류 시 graceful skip(log.warn) 패턴.
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
            wsTransport =
                KisWsTransportProperties(
                    urlProd = "ws://ops.koreainvestment.com:21000",
                    credentialOwnerId = defaultUserId,
                    htsId = defaultUserId,
                ),
            magicjarMode = magicjarMode,
            executionListenerProvider = executionListenerProvider,
            systemEventLogger = systemEventLogger,
            priceMetricsPortProvider = priceMetricsPortProvider,
            credentialRepositoryProvider =
                mockk<ObjectProvider<ConsumerUserKisCredentialJpaRepository>>(
                    relaxed = true,
                ).also {
                    every {
                        it.ifAvailable
                    } returns null
                },
            slotLedgerProvider =
                mockk<ObjectProvider<kr.spdaewoon.magicjar.consumer.ingestion.WsSlotBudgetLedger>>(
                    relaxed = true,
                ).also {
                    every { it.ifAvailable } returns null
                },
        )
    }
}
