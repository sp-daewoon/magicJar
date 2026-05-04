package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS WebSocket 단일 연결 위에서 trId × trKey 다중 구독 dispatcher.
 *
 * - 1 KIS account = 1 WS connection.
 * - subscribe(trId, trKey, listener) — frame 도착 시 listener 호출.
 * - 연결 전 subscribe 호출 → pending queue 적재 → 연결 후 일괄 flush.
 * - 재연결 시 listeners 전부 재구독.
 * - iv/key 캐시는 체결통보(H0STCNI0·H0STCNI9) 전용 — 시세 trId는 무시.
 *
 * plan §2-B, §3 T2 (2026-05-04-track2-r2-quote-ws.md).
 */
@Component
@Profile("dev | prd")
class KisWsRouter(
    private val properties: KisProperties,
    private val approvalProvider: KisWsApprovalProvider,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 등록된 listener 맵 — trId+trKey 복합 키 */
    private data class SubKey(
        val trId: String,
        val trKey: String,
    )

    private val listeners = ConcurrentHashMap<SubKey, KisWsFrameListener>()

    /** 연결 성립 전 subscribe 요청을 버퍼링 */
    private val pendingSubscribes = ConcurrentLinkedQueue<SubKey>()

    /** 현재 WS 세션으로 메시지 전송하는 싱크 */
    private val outboundSink = AtomicReference<Sinks.Many<String>?>(null)

    /** AES iv/key — 체결통보 trId(H0STCNI0/9) 전용 */
    @Volatile
    private var ivKey: Pair<String, String>? = null

    private val running = AtomicBoolean(false)
    private val wsDisposable = AtomicReference<Disposable?>(null)
    private val wsClient = ReactorNettyWebSocketClient()

    fun subscribe(
        trId: String,
        trKey: String,
        listener: KisWsFrameListener,
    ) {
        val key = SubKey(trId, trKey)
        // 멱등 — 같은 (trId, trKey)에 대한 재구독은 listener만 갱신하고 KIS WS 프레임은 보내지 않는다.
        // 동적 구독 명령이 중복 들어와도 KIS WS에 SUBSCRIBE 메시지가 매번 가지 않도록 보호.
        val existing = listeners.putIfAbsent(key, listener)
        if (existing != null) {
            log.debug("[KIS_WS_ROUTER] already subscribed trId={} trKey={} — skip frame", trId, trKey)
            return
        }
        val sink = outboundSink.get()
        if (sink != null) {
            sendSubscribeFrame(sink, trId, trKey, register = true)
        } else {
            pendingSubscribes.offer(key)
            log.debug("[KIS_WS_ROUTER] queued subscribe trId={} trKey={}", trId, trKey)
        }
    }

    fun unsubscribe(
        trId: String,
        trKey: String,
    ) {
        val key = SubKey(trId, trKey)
        listeners.remove(key)
        pendingSubscribes.remove(key)
        val sink = outboundSink.get()
        if (sink != null) {
            sendSubscribeFrame(sink, trId, trKey, register = false)
        }
    }

    /**
     * 부팅 시 1회 호출 — WS 연결 + 재연결 루프 시작.
     * 이미 running이면 no-op.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            log.info("[KIS_WS_ROUTER] already started — skip")
            return
        }
        log.info("[KIS_WS_ROUTER] starting, wsUrl={}", properties.wsUrl)
        connectWithBackoff()
    }

    @PreDestroy
    fun shutdown() {
        running.set(false)
        wsDisposable.getAndSet(null)?.dispose()
        outboundSink.getAndSet(null)?.tryEmitComplete()
        log.info("[KIS_WS_ROUTER] shutdown")
    }

    // ───────────────────────── internal ─────────────────────────

    private fun connectWithBackoff(attempt: Int = 0) {
        if (!running.get()) return

        val backoff = backoffDelay(attempt)
        if (backoff > Duration.ZERO) {
            log.info("[KIS_WS_ROUTER] reconnect in {} (attempt={})", backoff, attempt)
        }

        val delayedConnect: Mono<Void> =
            Mono.delay(backoff).then(
                Mono
                    .fromCallable {
                        val approvalKey =
                            try {
                                approvalProvider.getApprovalKey()
                            } catch (e: Exception) {
                                log.error("[KIS_WS_ROUTER] approval_key fetch failed — abort connect", e)
                                return@fromCallable null
                            }
                        approvalKey
                    }.flatMap { approvalKey ->
                        if (approvalKey == null) return@flatMap Mono.empty()
                        val sink: Sinks.Many<String> = Sinks.many().unicast().onBackpressureBuffer()
                        outboundSink.set(sink)

                        // 기존 pending + 현재 listeners 모두 재구독
                        val toSubscribe: List<SubKey> =
                            buildList {
                                addAll(pendingSubscribes)
                                pendingSubscribes.clear()
                                addAll(listeners.keys)
                            }.distinctBy { it }

                        val wsUri = URI.create(properties.wsUrl)
                        wsClient
                            .execute(wsUri) { session ->
                                log.info("[KIS_WS_ROUTER] connected to {}", wsUri)

                                // outbound — Flux를 WebSocketMessage로 변환
                                val outFlux: Flux<WebSocketMessage> =
                                    sink
                                        .asFlux()
                                        .map { session.textMessage(it) }

                                // 연결 직후 대기 구독 일괄 등록
                                toSubscribe.forEach { sub ->
                                    sendSubscribeFrame(sink, sub.trId, sub.trKey, register = true)
                                }

                                val send = session.send(outFlux)
                                val receive =
                                    session
                                        .receive()
                                        .map { it.payloadAsText }
                                        .doOnNext { text ->
                                            try {
                                                handleFrame(sink, approvalKey, text)
                                            } catch (e: Exception) {
                                                log.error("[KIS_WS_ROUTER] frame handling error", e)
                                            }
                                        }.doOnError { e ->
                                            log.warn("[KIS_WS_ROUTER] receive error: {}", e.message)
                                        }.doOnComplete {
                                            log.info("[KIS_WS_ROUTER] receive stream completed")
                                        }

                                Mono.zip(send, receive.then()).then()
                            }.doOnError { e ->
                                log.warn("[KIS_WS_ROUTER] WS error: {}", e.message)
                                outboundSink.set(null)
                                ivKey = null
                            }.doOnSuccess {
                                log.info("[KIS_WS_ROUTER] WS session ended normally")
                                outboundSink.set(null)
                                ivKey = null
                            }
                    },
            )

        val disp =
            delayedConnect
                .doFinally {
                    if (running.get()) {
                        connectWithBackoff(attempt + 1)
                    }
                }.subscribe(
                    {},
                    { e -> log.error("[KIS_WS_ROUTER] fatal error", e) },
                )
        wsDisposable.set(disp)
    }

    /**
     * KIS WS 응답 프레임 파싱 및 dispatch.
     *
     * 1) JSON SUBSCRIBE SUCCESS → iv/key 캐시 (체결통보 trId만)
     * 2) "0|trId|count|payload" → plaintext dispatch
     * 3) "1|trId|count|encrypted" → AES 복호 후 dispatch (체결통보만 이 경로)
     */
    internal fun handleFrame(
        sink: Sinks.Many<String>,
        approvalKey: String,
        text: String,
    ) {
        val trimmed = text.trim()

        // JSON 응답 — SUBSCRIBE SUCCESS / PING
        if (trimmed.startsWith("{")) {
            handleJsonFrame(sink, approvalKey, trimmed)
            return
        }

        val parts = trimmed.split("|", limit = 4)
        if (parts.size < 4) return

        val isEncrypted = parts[0] == "1"
        val trId = parts[1]
        val rawPayload =
            if (isEncrypted) {
                val (k, iv) =
                    ivKey ?: run {
                        log.warn("[KIS_WS_ROUTER] encrypted frame but iv/key not cached — drop trId={}", trId)
                        return
                    }
                KisAesDecoder.decode(parts[3], k, iv)
            } else {
                parts[3]
            }

        val columns = rawPayload.split("^")
        if (columns.isEmpty()) return

        // trKey는 columns[0] — 종목코드 또는 HTS ID
        val trKey = columns[0]
        val key = SubKey(trId, trKey)
        val listener = listeners[key]
        if (listener != null) {
            listener.onFrame(trId, trKey, columns)
        } else {
            log.debug("[KIS_WS_ROUTER] no listener for trId={} trKey={}", trId, trKey)
        }
    }

    /**
     * JSON 응답 처리.
     * - PINGPONG → PONG 응답 (KIS WS 연결 유지)
     * - SUBSCRIBE SUCCESS → iv/key 체결통보 trId에만 캐시
     */
    private fun handleJsonFrame(
        sink: Sinks.Many<String>,
        approvalKey: String,
        json: String,
    ) {
        try {
            val node = mapper.readTree(json)
            val headerTrId = node.path("header").path("tr_id").asText(null)
            val bodyMsgCd = node.path("body").path("msg_cd").asText(null)

            // PINGPONG 처리
            if (headerTrId == "PINGPONG") {
                log.debug("[KIS_WS_ROUTER] PING received — sending PONG")
                val pong =
                    mapper.writeValueAsString(
                        mapOf(
                            "header" to
                                mapOf(
                                    "approval_key" to approvalKey,
                                    "custtype" to "P",
                                    "tr_type" to "1",
                                    "content-type" to "utf-8",
                                ),
                            "body" to mapOf("input" to mapOf("tr_id" to "PINGPONG")),
                        ),
                    )
                sink.tryEmitNext(pong)
                return
            }

            // SUBSCRIBE SUCCESS — iv/key는 체결통보 trId에만 적용
            val output = node.path("body").path("output")
            val iv = output.path("iv").asText(null)
            val key = output.path("key").asText(null)
            if (headerTrId != null && iv != null && key != null) {
                if (headerTrId.startsWith("H0STCNI")) {
                    // 체결통보 암호화 응답만 iv/key 저장
                    ivKey = key to iv
                    log.info("[KIS_WS_ROUTER] iv/key cached for trId={}", headerTrId)
                } else {
                    log.debug("[KIS_WS_ROUTER] SUBSCRIBE SUCCESS trId={} msgCd={}", headerTrId, bodyMsgCd)
                }
            }
        } catch (e: Exception) {
            log.warn("[KIS_WS_ROUTER] JSON frame parse error: {}", e.message)
        }
    }

    private fun sendSubscribeFrame(
        sink: Sinks.Many<String>,
        trId: String,
        trKey: String,
        register: Boolean,
    ) {
        val approvalKey =
            try {
                approvalProvider.getApprovalKey()
            } catch (e: Exception) {
                log.warn("[KIS_WS_ROUTER] cannot get approvalKey for subscribe: {}", e.message)
                return
            }
        val frame =
            mapper.writeValueAsString(
                mapOf(
                    "header" to
                        mapOf(
                            "approval_key" to approvalKey,
                            "custtype" to "P",
                            "tr_type" to if (register) "1" else "2",
                            "content-type" to "utf-8",
                        ),
                    "body" to
                        mapOf(
                            "input" to mapOf("tr_id" to trId, "tr_key" to trKey),
                        ),
                ),
            )
        sink.tryEmitNext(frame)
        log.info(
            "[KIS_WS_ROUTER] {} trId={} trKey={}",
            if (register) "subscribe" else "unsubscribe",
            trId,
            trKey,
        )
    }

    private fun backoffDelay(attempt: Int): Duration =
        when {
            attempt == 0 -> Duration.ZERO
            attempt == 1 -> Duration.ofSeconds(1)
            attempt == 2 -> Duration.ofSeconds(2)
            attempt == 3 -> Duration.ofSeconds(5)
            else -> Duration.ofSeconds(10)
        }
}
