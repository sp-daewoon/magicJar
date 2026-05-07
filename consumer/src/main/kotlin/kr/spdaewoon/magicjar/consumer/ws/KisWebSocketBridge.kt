package kr.spdaewoon.magicjar.consumer.ws

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisApprovalAdapter
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.port.QuoteCachePort
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Sinks
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KIS WebSocket 브릿지 — domain port KisWsPort 구현 (ADR 0048 §3-1).
 *
 * KIS-first 출처:
 * - WebSocket URL: open-trading-api examples_user/kis_auth.py my_url_ws 설정
 *   실전: wss://ops.koreainvestment.com:21000
 *   모의: wss://ops.koreainvestment.com:31000 (VTS)
 * - approval_key: KisApprovalAdapter.getApprovalKey() (/oauth2/Approval endpoint)
 * - subscribe 메시지: {"header": {"approval_key": ..., "tr_type": "1", "custtype": "P"}, "body": {"input": {"tr_id": "H0STCNT0", "tr_key": "005930"}}}
 * - unsubscribe: tr_type "2"
 * - PINGPONG heartbeat: "PINGPONG" 수신 시 동일 텍스트 송신
 *
 * 41 slot 예산 (ADR 0048 §2-1):
 * - H0STCNI0 체결통보: 1 slot (reserve — 실제 subscribe는 T-O)
 * - H0STCNT0 체결가: 최대 30 slot (본 브릿지 책임)
 * - H0STASP0 호가: 최대 10 slot (LRU — SubscriptionRouter 책임)
 *
 * 재연결 지수 백오프 (ADR 0048 §2-4-B):
 * 1차: 즉시, 2차: 2s, 3차: 5s, 4차: 10s, 5차+: 30s
 *
 * 10초 heartbeat 무신호 감지 → reconnect 트리거 (ADR 0048 §2-4-A).
 *
 * @PreDestroy graceful shutdown ≤ 5s (ADR 0045).
 */
@Component
class KisWebSocketBridge(
    private val approvalAdapter: KisApprovalAdapter,
    private val redisTemplate: StringRedisTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val quoteCachePort: QuoteCachePort,
    @Value("\${magicjar.kis.ws.url-prod:wss://ops.koreainvestment.com:21000}")
    private val wsUrlProd: String,
    @Value("\${magicjar.kis.ws.url-vps:wss://ops.koreainvestment.com:31000}")
    private val wsUrlVps: String,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
    @Value("\${magicjar.kis.ws.user-id:}")
    private val defaultUserId: String,
) : KisWsPort {
    private val log = LoggerFactory.getLogger(KisWebSocketBridge::class.java)

    // 재연결 지수 백오프 딜레이 (ms) — ADR 0048 §2-4-B
    private val reconnectDelaysMs = longArrayOf(0L, 2000L, 5000L, 10000L, 30000L)
    private val heartbeatTimeoutSeconds = 10L
    private val pollingFallbackTimeoutSeconds = 30L

    private val session = KisWebSocketSession()
    private val scheduler = Executors.newScheduledThreadPool(1)
    private val wsClient = ReactorNettyWebSocketClient()

    // Sink: 메시지 전송용 (subscribe/unsubscribe 메시지를 WS에 보냄)
    private var sendSink: Sinks.Many<String>? = null
    private var wsDisposable: Disposable? = null
    private var heartbeatWatchdog: ScheduledFuture<*>? = null

    @Volatile
    private var pollingFallbackActive = AtomicBoolean(false)

    // polling fallback 콜백 (backend-core WatchlistPollingWorker와 연동)
    @Volatile
    var onPollingFallbackStart: (() -> Unit)? = null

    @Volatile
    var onPollingFallbackStop: (() -> Unit)? = null

    /**
     * KIS WS에 종목 구독 (H0STCNT0).
     * slot 여유 확인은 SubscriptionRouter 책임 — 여기서는 단순 subscribe 메시지 송신.
     */
    override fun subscribe(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey = getApprovalKey()
        codes.forEach { code ->
            if (!session.subscribedCodes.contains(code)) {
                val msg = buildSubscribeMessage(approvalKey, code, trType = "1")
                sendSink?.tryEmitNext(msg)
                session.subscribedCodes.add(code)
                log.info("KIS WS subscribe: code={}", code)
            }
        }
    }

    /**
     * KIS WS 종목 구독 해제 (H0STCNT0).
     */
    override fun unsubscribe(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey = getApprovalKey()
        codes.forEach { code ->
            if (session.subscribedCodes.contains(code)) {
                val msg = buildSubscribeMessage(approvalKey, code, trType = "2")
                sendSink?.tryEmitNext(msg)
                session.subscribedCodes.remove(code)
                log.info("KIS WS unsubscribe: code={}", code)
            }
        }
    }

    override fun subscribedCodes(): Set<String> = session.subscribedCodes.toSet()

    /**
     * WebSocket 연결 시작.
     * 부팅 시 PositionBootstrapRunner 또는 WatchlistIngestionWorker에서 호출.
     */
    fun connect() {
        if (session.connected || session.shuttingDown) return
        log.info("KIS WS 연결 시작: url={}", getWsUrl())
        scheduleConnect(delayMs = 0L)
    }

    private fun scheduleConnect(delayMs: Long) {
        if (session.shuttingDown) return
        scheduler.schedule({
            if (!session.shuttingDown) {
                doConnect()
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun doConnect() {
        val sink = Sinks.many().unicast().onBackpressureBuffer<String>()
        sendSink = sink

        val approvalKey = getApprovalKey()
        val wsUrl = getWsUrl()

        wsDisposable =
            wsClient
                .execute(URI(wsUrl)) { wsSession ->
                    val sendFlux =
                        sink
                            .asFlux()
                            .map { text -> wsSession.textMessage(text) }

                    val receiveFlux =
                        wsSession
                            .receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .doOnNext { raw -> handleMessage(raw) }
                            .doOnError { err -> log.error("KIS WS 수신 오류: {}", err.message) }
                            .then()

                    wsSession.send(sendFlux).mergeWith(receiveFlux).then()
                }.doOnSubscribe {
                    session.onConnected()
                    pollingFallbackActive.set(false)
                    onPollingFallbackStop?.invoke()
                    log.info("KIS WS 연결 성공 — approval_key 발급 완료")
                    startHeartbeatWatchdog()
                    // 기존 구독 종목 재구독
                    val existingCodes = session.subscribedCodes.toList()
                    if (existingCodes.isNotEmpty()) {
                        existingCodes.forEach { code ->
                            val msg = buildSubscribeMessage(approvalKey, code, trType = "1")
                            sink.tryEmitNext(msg)
                        }
                        log.info("KIS WS 재구독 완료: 종목 {}건", existingCodes.size)
                    }
                }.doOnTerminate {
                    session.onDisconnected()
                    stopHeartbeatWatchdog()
                    log.warn("KIS WS 연결 종료")
                    if (!session.shuttingDown) {
                        scheduleReconnect()
                    }
                }.subscribe()
    }

    private fun handleMessage(raw: String) {
        session.onMessageReceived(Clock.System.now())

        when (val parsed = KisWsMessageParser.parse(raw)) {
            is KisWsMessageParser.ParsedMessage.Pingpong -> {
                sendSink?.tryEmitNext("PINGPONG")
                log.debug("KIS WS PINGPONG 응답")
            }

            is KisWsMessageParser.ParsedMessage.RealtimeData -> {
                when (parsed.trId) {
                    "H0STCNT0" -> handleTickData(parsed.rawData)
                    "H0STASP0" -> log.debug("KIS WS 호가 수신: trId=H0STASP0 (호가 처리 미구현 — T-S1)")
                    "H0STCNI0" -> log.info("KIS WS 체결통보 수신: trId=H0STCNI0 (handler 미구현 — T-O)")
                    else -> log.debug("KIS WS 알 수 없는 TR_ID: trId={}", parsed.trId)
                }
            }

            is KisWsMessageParser.ParsedMessage.SubscriptionResponse -> {
                val success = KisWsMessageParser.isSubscriptionSuccess(parsed.rawJson)
                log.info("KIS WS 구독 응답: success={} json={}", success, parsed.rawJson.take(200))
            }

            is KisWsMessageParser.ParsedMessage.Unknown -> {
                log.debug("KIS WS 알 수 없는 메시지: raw={}", raw.take(200))
            }
        }
    }

    private fun handleTickData(rawData: String) {
        val tick = KisWsMessageParser.parseH0Stcnt0(rawData) ?: return
        log.debug("KIS WS tick: code={} price={} vol={}", tick.code, tick.price, tick.volume)

        // 1. Redis quote:last:{code} 갱신 (ADR 0014 T1 cache)
        writeToRedis(tick)

        // 2. Redis candle:current:{code}:1m HMSET 갱신 (ADR 0029)
        updateCandleCurrent(tick)

        // 3. Redis candle:today:{code}:1d HMSET 갱신 (ADR 0029)
        updateCandleToday(tick)

        // 4. Kafka market.tick.v1 publish
        publishTickEvent(tick)
    }

    private fun writeToRedis(tick: TickEvent) {
        try {
            val json =
                objectMapper.writeValueAsString(
                    mapOf(
                        "price" to tick.price.toPlainString(),
                        "volume" to tick.volume,
                        "ts" to tick.ts.toString(),
                        "source" to tick.source,
                    ),
                )
            redisTemplate
                .opsForValue()
                .set(
                    "quote:last:${tick.code}",
                    json,
                    java.time.Duration.ofDays(7),
                )
        } catch (e: Exception) {
            log.error("KIS WS Redis 쓰기 실패: code={} error={}", tick.code, e.message)
        }
    }

    private fun updateCandleCurrent(tick: TickEvent) {
        try {
            val key = "candle:current:${tick.code}:1m"
            val existing = redisTemplate.opsForHash<String, String>().entries(key)
            val priceStr = tick.price.toPlainString()
            val currentOpen = existing["open"] ?: priceStr
            val currentHigh =
                existing["high"]?.let { h -> maxOf(h.toBigDecimalOrNull() ?: BigDecimal.ZERO, tick.price).toPlainString() } ?: priceStr
            val currentLow =
                existing["low"]?.let { l -> minOf(l.toBigDecimalOrNull() ?: tick.price, tick.price).toPlainString() } ?: priceStr
            val startTs = existing["startTs"] ?: tick.ts.toString()
            val volume = (existing["volume"]?.toLongOrNull() ?: 0L) + tick.volume

            redisTemplate.opsForHash<String, String>().putAll(
                key,
                mapOf(
                    "open" to currentOpen,
                    "high" to currentHigh,
                    "low" to currentLow,
                    "close" to priceStr,
                    "volume" to volume.toString(),
                    "startTs" to startTs,
                ),
            )
            // TTL: 2분 (1분봉 closing 후 DEL로 정리 — TickToMinuteAggregator 책임)
            redisTemplate.expire(key, java.time.Duration.ofMinutes(2))
        } catch (e: Exception) {
            log.error("KIS WS candle:current 갱신 실패: code={} error={}", tick.code, e.message)
        }
    }

    private fun updateCandleToday(tick: TickEvent) {
        try {
            val key = "candle:today:${tick.code}:1d"
            val existing = redisTemplate.opsForHash<String, String>().entries(key)
            val priceStr = tick.price.toPlainString()
            val currentOpen = existing["open"] ?: priceStr
            val currentHigh =
                existing["high"]?.let { h -> maxOf(h.toBigDecimalOrNull() ?: BigDecimal.ZERO, tick.price).toPlainString() } ?: priceStr
            val currentLow =
                existing["low"]?.let { l -> minOf(l.toBigDecimalOrNull() ?: tick.price, tick.price).toPlainString() } ?: priceStr
            val volume = (existing["volume"]?.toLongOrNull() ?: 0L) + tick.volume

            redisTemplate.opsForHash<String, String>().putAll(
                key,
                mapOf(
                    "open" to currentOpen,
                    "high" to currentHigh,
                    "low" to currentLow,
                    "close" to priceStr,
                    "volume" to volume.toString(),
                ),
            )
            // TTL: 장 마감 + 30분 (16:00 + 30m = 16:30 KST) — 단순화: 24h TTL
            redisTemplate.expire(key, java.time.Duration.ofHours(24))
        } catch (e: Exception) {
            log.error("KIS WS candle:today 갱신 실패: code={} error={}", tick.code, e.message)
        }
    }

    private fun publishTickEvent(tick: TickEvent) {
        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "code" to tick.code,
                        "price" to tick.price.toPlainString(),
                        "volume" to tick.volume,
                        "ts" to tick.ts.toString(),
                        "source" to tick.source,
                        "stale" to tick.stale,
                    ),
                )
            kafkaTemplate.send("market.tick.v1", tick.code, payload)
        } catch (e: Exception) {
            log.error("KIS WS Kafka publish 실패: code={} error={}", tick.code, e.message)
        }
    }

    /**
     * 재연결 스케줄링 — 지수 백오프 (ADR 0048 §2-4-B).
     * 1차: 즉시, 2차: 2s, 3차: 5s, 4차: 10s, 5차+: 30s
     */
    private fun scheduleReconnect() {
        val attempt = session.incrementReconnect()
        val delayIdx = (attempt - 1).coerceIn(0, reconnectDelaysMs.size - 1)
        val delayMs = reconnectDelaysMs[delayIdx]
        log.warn("KIS WS 재연결 스케줄: attempt={} delay={}ms", attempt, delayMs)

        // 30초 이상 연속 실패 시 polling fallback 활성화
        if (delayMs >= pollingFallbackTimeoutSeconds * 1000 && !pollingFallbackActive.get()) {
            log.warn("KIS WS 재연결 30s 초과 — polling fallback 활성화")
            pollingFallbackActive.set(true)
            onPollingFallbackStart?.invoke()
        }

        scheduleConnect(delayMs)
    }

    /**
     * Heartbeat watchdog — 10초 무신호 시 reconnect 트리거 (ADR 0048 §2-4-A).
     */
    private fun startHeartbeatWatchdog() {
        stopHeartbeatWatchdog()
        heartbeatWatchdog =
            scheduler.scheduleAtFixedRate({
                if (!session.connected || session.shuttingDown) return@scheduleAtFixedRate
                val lastMsg = session.lastMessageAt.get() ?: return@scheduleAtFixedRate
                val elapsed = Clock.System.now().epochSeconds - lastMsg.epochSeconds
                if (elapsed > heartbeatTimeoutSeconds && session.subscribedCodes.isNotEmpty()) {
                    log.warn("KIS WS heartbeat 무신호 {}s — 재연결 트리거", elapsed)
                    wsDisposable?.dispose()
                }
            }, heartbeatTimeoutSeconds, heartbeatTimeoutSeconds, TimeUnit.SECONDS)
    }

    private fun stopHeartbeatWatchdog() {
        heartbeatWatchdog?.cancel(false)
        heartbeatWatchdog = null
    }

    /**
     * Graceful shutdown (ADR 0045 — SIGTERM 시 ≤ 5s).
     */
    @PreDestroy
    fun destroy() {
        log.info("KIS WS graceful shutdown 시작")
        session.shuttingDown = true
        stopHeartbeatWatchdog()

        // 모든 종목 unsubscribe
        val codes = session.subscribedCodes.toList()
        if (codes.isNotEmpty()) {
            try {
                val approvalKey = getApprovalKey()
                codes.forEach { code ->
                    val msg = buildSubscribeMessage(approvalKey, code, trType = "2")
                    sendSink?.tryEmitNext(msg)
                }
                Thread.sleep(1000) // unsubscribe 송신 대기
            } catch (e: Exception) {
                log.warn("KIS WS shutdown 시 unsubscribe 실패: {}", e.message)
            }
        }

        wsDisposable?.dispose()
        sendSink?.tryEmitComplete()
        scheduler.shutdown()
        scheduler.awaitTermination(4, TimeUnit.SECONDS)
        log.info("KIS WS graceful shutdown 완료")
    }

    private fun getApprovalKey(): String {
        if (defaultUserId.isBlank()) {
            log.warn("KIS WS user-id 미설정 — approval_key 발급 불가. defaultUserId 확인 필요")
            return "NO_USER_ID"
        }
        val accountType = if (magicjarMode == "REAL") AccountType.REAL else AccountType.MOCK
        return try {
            approvalAdapter.getApprovalKey(defaultUserId, accountType)
        } catch (e: Exception) {
            log.error("KIS WS approval_key 발급 실패: userId={} error={}", defaultUserId.take(4), e.message)
            "ERROR_APPROVAL_KEY"
        }
    }

    private fun getWsUrl(): String = if (magicjarMode == "REAL") wsUrlProd else wsUrlVps

    /**
     * KIS WS subscribe/unsubscribe 메시지 빌드.
     *
     * KIS-first 출처: open-trading-api kis_auth.py data_fetch() + dispatch-spec §4-4:
     * {
     *   "header": {"approval_key": "...", "tr_type": "1", "custtype": "P"},
     *   "body": {"input": {"tr_id": "H0STCNT0", "tr_key": "005930"}}
     * }
     */
    private fun buildSubscribeMessage(
        approvalKey: String,
        code: String,
        trType: String, // "1" = subscribe, "2" = unsubscribe
    ): String =
        objectMapper.writeValueAsString(
            mapOf(
                "header" to
                    mapOf(
                        "approval_key" to approvalKey,
                        "tr_type" to trType,
                        "custtype" to "P",
                        "content-type" to "utf-8",
                    ),
                "body" to
                    mapOf(
                        "input" to
                            mapOf(
                                "tr_id" to "H0STCNT0",
                                "tr_key" to code,
                            ),
                    ),
            ),
        )
}
