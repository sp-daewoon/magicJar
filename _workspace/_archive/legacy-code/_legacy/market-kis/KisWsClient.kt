package com.mj.domain.market.kis

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import com.mj.domain.market.event.RealtimeQuoteEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.AbstractWebSocketHandler
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS WebSocket 실시간 체결 구독 클라이언트.
 *
 * - subscribe(code) 누적 → 연결 시 일괄 PINGREQ 형식으로 구독 등록.
 * - 연결 종료 시 1s → 2s → 5s → 5s … 백오프 재연결.
 * - credentials 미설정 시 부팅은 정상 진행, start() 호출 시 IllegalStateException.
 *
 * 동시 구독 한도 약 40 — 30개로 버퍼.
 */
@Component
class KisWsClient(
    private val properties: KisProperties,
    private val objectMapper: ObjectMapper,
    private val publisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(KisWsClient::class.java)

    private val subscriptions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val session = AtomicReference<WebSocketSession?>()
    private val attempt = AtomicInteger(0)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicReference(false)

    @Volatile private var approvalKey: String? = null

    fun subscribe(code: String) {
        require(code.isNotBlank()) { "code blank" }
        if (subscriptions.size >= MAX_SUBSCRIPTIONS && !subscriptions.contains(code)) {
            log.warn("KIS WS subscription cap reached ({}). Skipping {}", MAX_SUBSCRIPTIONS, code)
            return
        }
        if (subscriptions.add(code)) {
            session.get()?.let { sendSubscribe(it, code) }
        }
    }

    fun unsubscribe(code: String) {
        if (subscriptions.remove(code)) {
            session.get()?.let { sendUnsubscribe(it, code) }
        }
    }

    fun setApprovalKey(key: String) {
        approvalKey = key
    }

    fun start() {
        check(properties.hasCredentials()) { "KIS credentials missing — WS start forbidden" }
        if (!running.compareAndSet(false, true)) return
        connect()
    }

    @PreDestroy
    fun shutdown() {
        running.set(false)
        session.getAndSet(null)?.close(CloseStatus.NORMAL)
        executor.shutdownNow()
    }

    private fun connect() {
        if (!running.get()) return
        val uri = URI.create(properties.wsUrl)
        val client = StandardWebSocketClient()
        log.info("KIS WS connecting to {} (attempt={})", uri, attempt.get())
        try {
            val sess = client.execute(handler(), null, uri).get(10, TimeUnit.SECONDS)
            session.set(sess)
            attempt.set(0)
            subscriptions.forEach { sendSubscribe(sess, it) }
        } catch (ex: Exception) {
            log.warn("KIS WS connect failed: {}", ex.message)
            scheduleReconnect()
        }
    }

    private fun handler() =
        object : AbstractWebSocketHandler() {
            override fun handleTextMessage(
                session: WebSocketSession,
                message: TextMessage,
            ) {
                val raw = message.payload
                if (raw.startsWith("{")) return
                val quote = KisWsEvents.parseConclusion(raw) ?: return
                publisher.publishEvent(RealtimeQuoteEvent(quote, source = "KIS_WS"))
            }

            override fun afterConnectionClosed(
                session: WebSocketSession,
                status: CloseStatus,
            ) {
                log.info("KIS WS closed: {}", status)
                this@KisWsClient.session.set(null)
                scheduleReconnect()
            }
        }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val n = attempt.incrementAndGet()
        val delay =
            when {
                n == 1 -> Duration.ofSeconds(1)
                n == 2 -> Duration.ofSeconds(2)
                else -> Duration.ofSeconds(5)
            }
        log.info("KIS WS reconnect scheduled in {}", delay)
        executor.schedule({ connect() }, delay.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun sendSubscribe(
        session: WebSocketSession,
        code: String,
    ) {
        val message = buildSubscribeFrame(code, register = true)
        session.sendMessage(TextMessage(message))
    }

    private fun sendUnsubscribe(
        session: WebSocketSession,
        code: String,
    ) {
        val message = buildSubscribeFrame(code, register = false)
        session.sendMessage(TextMessage(message))
    }

    private fun buildSubscribeFrame(
        code: String,
        register: Boolean,
    ): String {
        val frame =
            mapOf(
                "header" to
                    mapOf(
                        "approval_key" to (approvalKey ?: ""),
                        "custtype" to "P",
                        "tr_type" to if (register) "1" else "2",
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
            )
        return objectMapper.writeValueAsString(frame)
    }

    companion object {
        const val MAX_SUBSCRIPTIONS = 30
    }
}
