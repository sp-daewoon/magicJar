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
import org.springframework.beans.factory.annotation.Qualifier
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
import java.util.concurrent.ConcurrentHashMap
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
 * H0NXCNT0 NXT 대체거래소 체결가 구독 (ADR 0063 정합 — 08:00~20:00 KST cover):
 * - subscribe(codes) 시 H0STCNT0 + H0NXCNT0 동시 구독 (장외 sync 결함 hotfix).
 * - KIS-first 출처: open-trading-api ccnl_nxt() tr_id=H0NXCNT0
 *   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
 *
 * 41 slot 예산 (ADR 0048 §2-1 + NXT 추가 후):
 * - H0STCNI0 체결통보: 1 slot (reserve — 실제 subscribe는 T-O)
 * - H0STCNT0 체결가: 최대 20 slot (NXT 병행 구독 시 slot 2배 소모)
 * - H0NXCNT0 NXT 체결가: 최대 20 slot (H0STCNT0와 동일 종목 세트)
 * - H0STASP0 호가: 최대 0 slot (NXT 추가로 slot 포화 — LRU 호가는 SubscriptionPolicy.ORDERBOOK_MAX=0 적용 검토, backlog §53)
 * - 현행: H0STCNT0(20) + H0NXCNT0(20) + H0STCNI0(1) = 41 정합.
 *   Watchlist ≤ 20 MVP 환경에서 안전. 풀 > 20 시 SubscriptionRouter priority 재조정 필요 (backlog §53).
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
    /** KIS WS 전용 WebSocketClient — SslProvider.JDK + TLSv1.2/1.3 명시 (WebClientConfig 빈).
     *  SSL handshake timeout fix (PR #278 DART 패턴 동일 적용).
     *  출처: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/websocket/ */
    @Qualifier("kisWebSocketClient")
    private val wsClient: ReactorNettyWebSocketClient,
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

    // 호가 구독 종목코드 Set (H0STASP0 — ADR 0048 §2-1 LRU 10 slot)
    private val orderbookSubscribedCodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // NXT 체결가 구독 종목코드 Set (H0NXCNT0 — ADR 0063 NXT 08:00~20:00 KST cover)
    // KIS-first 출처: open-trading-api ccnl_nxt() tr_id=H0NXCNT0
    // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
    private val nxtSubscribedCodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
     * KIS WS에 종목 구독 (H0STCNT0 + H0NXCNT0 동시 구독).
     * slot 여유 확인은 SubscriptionRouter 책임 — 여기서는 단순 subscribe 메시지 송신.
     *
     * NXT(H0NXCNT0) 추가 사유 (ADR 0063 정합 — 08:00~20:00 KST cover):
     *   정규장(09:00~15:30) 종료 후 H0STCNT0 tick 0건 → candle·quote stale.
     *   H0NXCNT0 동시 구독으로 NXT 시간외(15:30~20:00) + pre-market(08:00~09:00) tick 수신.
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     *   ccnl_nxt() tr_id="H0NXCNT0" — NXT 대체거래소 실시간 체결가
     */
    override fun subscribe(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey = getApprovalKey()
        codes.forEach { code ->
            if (!session.subscribedCodes.contains(code)) {
                val msg = buildSubscribeMessage(approvalKey, code, trType = "1")
                sendSink?.tryEmitNext(msg)
                session.subscribedCodes.add(code)
                log.info("KIS WS subscribe: code={} trId=H0STCNT0", code)
            }
            // H0NXCNT0 NXT 대체거래소 동시 구독 — ADR 0063 NXT 08:00~20:00 KST cover
            // KIS-first: open-trading-api ccnl_nxt() tr_id=H0NXCNT0
            // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
            if (!nxtSubscribedCodes.contains(code)) {
                val nxtMsg = buildSubscribeMessage(approvalKey, code, trType = "1", trId = "H0NXCNT0")
                sendSink?.tryEmitNext(nxtMsg)
                nxtSubscribedCodes.add(code)
                log.info("KIS WS subscribe: code={} trId=H0NXCNT0", code)
            }
        }
    }

    /**
     * KIS WS 종목 구독 해제 (H0STCNT0 + H0NXCNT0 동시 해제).
     */
    override fun unsubscribe(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey = getApprovalKey()
        codes.forEach { code ->
            if (session.subscribedCodes.contains(code)) {
                val msg = buildSubscribeMessage(approvalKey, code, trType = "2")
                sendSink?.tryEmitNext(msg)
                session.subscribedCodes.remove(code)
                log.info("KIS WS unsubscribe: code={} trId=H0STCNT0", code)
            }
            // H0NXCNT0 구독 해제
            if (nxtSubscribedCodes.contains(code)) {
                val nxtMsg = buildSubscribeMessage(approvalKey, code, trType = "2", trId = "H0NXCNT0")
                sendSink?.tryEmitNext(nxtMsg)
                nxtSubscribedCodes.remove(code)
                log.info("KIS WS unsubscribe: code={} trId=H0NXCNT0", code)
            }
        }
    }

    override fun subscribedCodes(): Set<String> = session.subscribedCodes.toSet()

    /**
     * KIS WS 호가 구독 (H0STASP0) — ADR 0048 §2-1 LRU 10 slot.
     * KIS-first 출처: open-trading-api asking_price_krx.py subscribe tr_type="1"
     */
    override fun subscribeOrderbook(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey = getApprovalKey()
        codes.forEach { code ->
            if (!orderbookSubscribedCodes.contains(code)) {
                val msg = buildSubscribeMessage(approvalKey, code, trType = "1", trId = "H0STASP0")
                sendSink?.tryEmitNext(msg)
                orderbookSubscribedCodes.add(code)
                log.info("KIS WS orderbook subscribe: code={}", code)
            }
        }
    }

    /**
     * KIS WS 호가 구독 해제 (H0STASP0).
     */
    override fun unsubscribeOrderbook(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey = getApprovalKey()
        codes.forEach { code ->
            if (orderbookSubscribedCodes.contains(code)) {
                val msg = buildSubscribeMessage(approvalKey, code, trType = "2", trId = "H0STASP0")
                sendSink?.tryEmitNext(msg)
                orderbookSubscribedCodes.remove(code)
                log.info("KIS WS orderbook unsubscribe: code={}", code)
            }
        }
    }

    override fun subscribedOrderbookCodes(): Set<String> = orderbookSubscribedCodes.toSet()

    /**
     * WebSocket 연결 시작 + approval_key 발급 + 기존 구독 종목 재구독.
     * 이미 연결된 상태이면 no-op (idempotent).
     *
     * 부팅 시 WatchlistIngestionWorker.onStartup()에서 watchlist 발견 후 호출 (ADR 0028 §2-1).
     *
     * KIS-first 출처:
     * - approval_key endpoint: /oauth2/Approval (KisApprovalAdapter)
     * - WebSocket URL raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/websocket/
     */
    override fun connect() {
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
                    // 기존 구독 종목 재구독 (H0STCNT0 + H0NXCNT0)
                    val existingCodes = session.subscribedCodes.toList()
                    if (existingCodes.isNotEmpty()) {
                        existingCodes.forEach { code ->
                            val msg = buildSubscribeMessage(approvalKey, code, trType = "1")
                            sink.tryEmitNext(msg)
                            // H0NXCNT0 NXT 재구독 — ADR 0063 NXT cover 정합
                            // KIS-first raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
                            val nxtMsg = buildSubscribeMessage(approvalKey, code, trType = "1", trId = "H0NXCNT0")
                            sink.tryEmitNext(nxtMsg)
                            nxtSubscribedCodes.add(code)
                        }
                        log.info("KIS WS 재구독 완료: 종목 {}건 (H0STCNT0 + H0NXCNT0 각각)", existingCodes.size)
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
                // KIS-first: kis_auth.py "await ws.pong(raw)" — 수신 raw 그대로 echo 의무
                // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
                sendSink?.tryEmitNext(parsed.rawMessage)
                log.trace("KIS WS PINGPONG keep-alive echo 전송")
            }

            is KisWsMessageParser.ParsedMessage.RealtimeData -> {
                when (parsed.trId) {
                    "H0STCNT0" -> handleTickData(parsed.rawData)
                    // H0NXCNT0 NXT 대체거래소 체결가 — ADR 0063 NXT 08:00~20:00 KST cover
                    // KIS-first: open-trading-api ccnl_nxt() tr_id=H0NXCNT0
                    // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
                    "H0NXCNT0" -> handleNxtTickData(parsed.rawData)
                    "H0STASP0" -> handleOrderbookData(parsed.rawData)
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

    private fun handleOrderbookData(rawData: String) {
        val snapshot = KisWsOrderbookParser.parseH0Stasp0(rawData) ?: return
        log.debug("KIS WS orderbook: code={} bids={} asks={}", snapshot.code, snapshot.bids.size, snapshot.asks.size)
        // Kafka market.orderbook.v1 publish
        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "code" to snapshot.code,
                        "ts" to snapshot.ts.toString(),
                        "bids" to snapshot.bids.map { mapOf("price" to it.price.toPlainString(), "quantity" to it.quantity) },
                        "asks" to snapshot.asks.map { mapOf("price" to it.price.toPlainString(), "quantity" to it.quantity) },
                    ),
                )
            kafkaTemplate.send("market.orderbook.v1", snapshot.code, payload)
        } catch (e: Exception) {
            log.error("KIS WS orderbook Kafka publish 실패: code={} error={}", snapshot.code, e.message)
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

    /**
     * H0NXCNT0 NXT 대체거래소 체결가 tick 처리.
     *
     * NXT cover 시간: ADR 0063 — 08:00~20:00 KST.
     * 정규장 종료(15:30) 후 H0STCNT0 tick 0건 → NXT tick으로 candle·quote 갱신.
     * TickEvent.source = "live-nxt" 로 H0STCNT0와 구분.
     *
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     *   ccnl_nxt() tr_id="H0NXCNT0" — 필드 구조 H0STCNT0(ccnl_krx)와 동일 인덱스.
     */
    private fun handleNxtTickData(rawData: String) {
        val tick = KisWsMessageParser.parseH0Nxcnt0(rawData) ?: return
        log.debug("KIS WS NXT tick: code={} price={} vol={} source={}", tick.code, tick.price, tick.volume, tick.source)

        // 동일 처리 흐름 — Redis update + candle 갱신 + Kafka publish
        writeToRedis(tick)
        updateCandleCurrent(tick)
        updateCandleToday(tick)
        publishTickEvent(tick)
    }

    /**
     * Redis quote:last:{code} 갱신.
     * dogfooding 결함 1 fix: priceChange·changeRate·changeSign 필드 포함 (등락률 싱크).
     * KIS-first: PRDY_VRSS[4]·PRDY_CTRT[5]·PRDY_VRSS_SIGN[3] 출처
     *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     */
    private fun writeToRedis(tick: TickEvent) {
        try {
            val data =
                mutableMapOf<String, Any?>(
                    "price" to tick.price.toPlainString(),
                    "volume" to tick.volume,
                    "ts" to tick.ts.toString(),
                    "source" to tick.source,
                )
            // 등락 필드 — null 이면 키 자체를 포함하지 않음 (polling source 호환)
            tick.priceChange?.let { data["priceChange"] = it.toPlainString() }
            tick.changeRate?.let { data["changeRate"] = it.toPlainString() }
            tick.changeSign?.let { data["changeSign"] = it }

            val json = objectMapper.writeValueAsString(data)
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

            // hotfix 2026-05-15: ts 키 필수 추가 (CandleQueryService line 109 합류 조건 정합)
            // 오늘 00:00 KST 기준 ts — 1d 캔들 ts 표준 (KST midnight)
            val todayKstMidnightUtc =
                java.time.LocalDate
                    .now(java.time.ZoneId.of("Asia/Seoul"))
                    .atStartOfDay(java.time.ZoneId.of("Asia/Seoul"))
                    .toInstant()
            val tsKey = existing["ts"] ?: todayKstMidnightUtc.toString()

            redisTemplate.opsForHash<String, String>().putAll(
                key,
                mapOf(
                    "open" to currentOpen,
                    "high" to currentHigh,
                    "low" to currentLow,
                    "close" to priceStr,
                    "volume" to volume.toString(),
                    "ts" to tsKey,
                ),
            )
            // TTL: 장 마감 + 30분 (16:00 + 30m = 16:30 KST) — 단순화: 24h TTL
            redisTemplate.expire(key, java.time.Duration.ofHours(24))
        } catch (e: Exception) {
            log.error("KIS WS candle:today 갱신 실패: code={} error={}", tick.code, e.message)
        }
    }

    /**
     * Kafka market.tick.v1 publish.
     * dogfooding 결함 1 fix: priceChange·changeRate·changeSign 필드 포함.
     * STOMP forwarder(MarketTickStompForwarder)가 payload를 그대로 forward하므로
     * 이 payload에 등락 필드를 넣으면 /topic/market.tick.{code} 에도 자동 전파됨.
     */
    private fun publishTickEvent(tick: TickEvent) {
        try {
            val data =
                mutableMapOf<String, Any?>(
                    "code" to tick.code,
                    "price" to tick.price.toPlainString(),
                    "volume" to tick.volume,
                    "ts" to tick.ts.toString(),
                    "source" to tick.source,
                    "stale" to tick.stale,
                )
            tick.priceChange?.let { data["priceChange"] = it.toPlainString() }
            tick.changeRate?.let { data["changeRate"] = it.toPlainString() }
            tick.changeSign?.let { data["changeSign"] = it }

            val payload = objectMapper.writeValueAsString(data)
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
     * KIS 키 변경 시 graceful reconnect (ADR 0074 §2-2-3 + ADR 0048 §3 정합).
     *
     * 흐름:
     * 1. 기존 WS 연결 종료 (dispose + shuttingDown 플래그는 false 유지 — shutdown 아님)
     * 2. 500ms 대기 (connection close propagation 보장)
     * 3. 신규 연결 시작 (doConnect — approval_key 재발급 + 활성 구독 종목 재구독)
     *
     * userId 파라미터: 로깅 식별용. 현재 단일 사용자 환경(헌법 §1)에서 실제 분기 없음.
     * approval_key 재발급: KisApprovalAdapter 내부 Redis cache가 Phase 1에서 DEL 완료 상태이므로
     *   doConnect → getApprovalKey 호출 시 자동으로 재발급됨 (idempotent 안전).
     *
     * AI 호출 0 — T-O 트랙 룰 5 정합.
     */
    fun reconnectGracefully(userId: String) {
        if (session.shuttingDown) {
            log.warn("[KisKeyChanged] WS shutdown 중 — reconnect 취소: userId={}", maskUserId(userId))
            return
        }
        log.info("[KisKeyChanged] WS graceful reconnect 시작 — userId={}", maskUserId(userId))

        // 1. 기존 연결 종료 (shuttingDown은 false 유지)
        stopHeartbeatWatchdog()
        wsDisposable?.dispose()
        sendSink?.tryEmitComplete()
        session.onDisconnected()

        // 2. 500ms 대기 — connection close propagation
        try {
            Thread.sleep(500L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // 3. 신규 연결 (기존 subscribedCodes 유지 → doConnect에서 재구독)
        session.reconnectCount.set(0)
        doConnect()

        log.info(
            "[KisKeyChanged] WS reconnect 완료 — userId={} subscribedCodes={}",
            maskUserId(userId),
            session.subscribedCodes.size,
        )
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    /**
     * Graceful shutdown (ADR 0045 — SIGTERM 시 ≤ 5s).
     */
    @PreDestroy
    fun destroy() {
        log.info("KIS WS graceful shutdown 시작")
        session.shuttingDown = true
        stopHeartbeatWatchdog()

        // 모든 종목 unsubscribe (H0STCNT0 + H0NXCNT0)
        val codes = session.subscribedCodes.toList()
        val nxtCodes = nxtSubscribedCodes.toList()
        if (codes.isNotEmpty() || nxtCodes.isNotEmpty()) {
            try {
                val approvalKey = getApprovalKey()
                codes.forEach { code ->
                    val msg = buildSubscribeMessage(approvalKey, code, trType = "2")
                    sendSink?.tryEmitNext(msg)
                }
                // H0NXCNT0 unsubscribe
                nxtCodes.forEach { code ->
                    val nxtMsg = buildSubscribeMessage(approvalKey, code, trType = "2", trId = "H0NXCNT0")
                    sendSink?.tryEmitNext(nxtMsg)
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
        } catch (e: IllegalStateException) {
            // 자격증명 미등록 — 사용자 환경 결함 (REAL/MOCK 한쪽만 등록 등). 매 호출마다 ERROR
            // 트레이스는 노이즈. WARN으로 강등 + 안내 메시지.
            log.warn(
                "KIS WS approval_key 발급 불가 — type={} 자격증명 미등록 (SetupWizard에서 등록 후 재부팅 필요): userId={}",
                accountType,
                defaultUserId.take(4),
            )
            "ERROR_APPROVAL_KEY"
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
        trId: String = "H0STCNT0", // 기본: H0STCNT0 체결가 / H0STASP0 호가
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
                                "tr_id" to trId,
                                "tr_key" to code,
                            ),
                    ),
            ),
        )
}
