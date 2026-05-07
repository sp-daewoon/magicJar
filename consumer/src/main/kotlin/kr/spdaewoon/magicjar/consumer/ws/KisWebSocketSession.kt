package kr.spdaewoon.magicjar.consumer.ws

import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS WebSocket 단일 연결 상태 관리 (ADR 0048 §2-4 정합).
 * thread-safe — AtomicReference·ConcurrentHashMap 사용.
 */
class KisWebSocketSession {
    /** 현재 구독 중인 종목코드 set (H0STCNT0) */
    val subscribedCodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 마지막 메시지 수신 시각 (heartbeat 무신호 감지용 — ADR 0048 §2-4-A) */
    val lastMessageAt: AtomicReference<Instant?> = AtomicReference(null)

    /** 연속 재연결 시도 횟수 (지수 백오프 계산용 — ADR 0048 §2-4-B) */
    val reconnectCount: AtomicInteger = AtomicInteger(0)

    /** WebSocket 연결 상태 */
    @Volatile
    var connected: Boolean = false

    /** graceful shutdown 요청 중 */
    @Volatile
    var shuttingDown: Boolean = false

    fun onMessageReceived(instant: Instant) {
        lastMessageAt.set(instant)
    }

    fun onConnected() {
        connected = true
        reconnectCount.set(0)
        lastMessageAt.set(
            kotlinx.datetime.Clock.System
                .now(),
        )
    }

    fun onDisconnected() {
        connected = false
    }

    fun incrementReconnect(): Int = reconnectCount.incrementAndGet()

    fun reset() {
        subscribedCodes.clear()
        connected = false
        reconnectCount.set(0)
        lastMessageAt.set(null)
    }
}
