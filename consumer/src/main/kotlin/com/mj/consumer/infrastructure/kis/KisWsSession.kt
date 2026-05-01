package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS 체결통보 WebSocket 세션.
 * spec §5.5, §7.2.
 *
 * - subscribe 프레임 송신 → SUBSCRIBE SUCCESS 응답에서 iv/key 추출 → 캐시
 * - 데이터 프레임 receive → AES 복호 → ^ 구분 raw → callback
 * - 연결 끊김 시 자동 재접속 (exponential backoff, retry indefinite)
 * - 실 WS 통신 검증은 T15 통합테스트에서 (fake/embedded WS 또는 dev profile dogfooding)
 */
class KisWsSession(
    private val wsUrl: String,
    private val approvalKey: String,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = ReactorNettyWebSocketClient()
    private val subscription = AtomicReference<Disposable?>()

    @Volatile
    private var ivKey: Pair<String, String>? = null

    /**
     * @param trId KIS H0STCNI0(real) / H0STCNI9(demo)
     * @param trKey HTS ID — 계정 식별자
     * @param onFrame 체결통보 raw가 들어왔을 때 callback
     */
    fun subscribe(
        trId: String,
        trKey: String,
        onFrame: (KisExecutionNoticeRaw) -> Unit,
    ) {
        val handler =
            WebSocketHandler { sess ->
                val subscribeMsg =
                    mapper.writeValueAsString(
                        mapOf(
                            "header" to
                                mapOf(
                                    "approval_key" to approvalKey,
                                    "tr_type" to "1",
                                    "custtype" to "P",
                                    "content-type" to "utf-8",
                                ),
                            "body" to
                                mapOf(
                                    "input" to mapOf("tr_id" to trId, "tr_key" to trKey),
                                ),
                        ),
                    )
                val out = sess.send(Mono.just(sess.textMessage(subscribeMsg)))
                val inFlux =
                    sess
                        .receive()
                        .map { it.payloadAsText }
                        .doOnNext { text ->
                            try {
                                handleFrame(text, onFrame)
                            } catch (e: Exception) {
                                log.error("[KIS_WS] frame handling failed: $text", e)
                            }
                        }
                Mono.zip(out, inFlux.then()).then()
            }
        val disp =
            client
                .execute(URI.create(wsUrl), handler)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2)))
                .subscribe(
                    { log.info("[KIS_WS] session ended normally") },
                    { e -> log.error("[KIS_WS] session terminal error", e) },
                )
        subscription.set(disp)
        log.info("[KIS_WS] subscribed tr_id={} tr_key=***", trId)
    }

    fun unsubscribe() {
        subscription.get()?.dispose()
        ivKey = null
        log.info("[KIS_WS] unsubscribed")
    }

    /**
     * 응답 분기:
     * 1) JSON SUBSCRIBE SUCCESS — body.output.{iv,key} 캐시
     * 2) 실 데이터 프레임 — `0|tr_id|count|payload` 또는 `1|tr_id|count|encrypted_payload`
     */
    internal fun handleFrame(
        text: String,
        onFrame: (KisExecutionNoticeRaw) -> Unit,
    ) {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val node = mapper.readTree(trimmed)
            val output = node.path("body").path("output")
            val iv = output.path("iv").asText(null)
            val key = output.path("key").asText(null)
            if (iv != null && key != null) {
                ivKey = key to iv
                log.info("[KIS_WS] subscribe success — iv/key cached")
            }
            return
        }
        val parts = trimmed.split("|", limit = 4)
        if (parts.size < 4) return
        val isEncrypted = parts[0] == "1"
        val rawPayload =
            if (isEncrypted) {
                val (k, iv) =
                    ivKey ?: run {
                        log.warn("[KIS_WS] iv/key not yet — drop encrypted frame")
                        return
                    }
                KisAesDecoder.decode(parts[3], k, iv)
            } else {
                parts[3]
            }
        val cols = rawPayload.split("^")
        if (cols.size < 15) return
        onFrame(KisExecutionNoticeRaw.fromColumns(cols))
    }
}
