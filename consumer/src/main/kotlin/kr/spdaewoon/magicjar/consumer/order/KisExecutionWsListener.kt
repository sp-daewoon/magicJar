package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * KIS WS H0STCNI0/H0STCNI9 체결 통보 처리 (ADR 0068 §14-6 LOCK).
 *
 * ### raw 출처
 * examples_user/domestic_stock/domestic_stock_functions_ws.py L389~L446 (def ccnl_notice)
 *
 * ### tr_id
 * - H0STCNI0 = 실전 체결 통보
 * - H0STCNI9 = 모의 체결 통보
 *
 * ### 흐름 분기 LOCK (§14-6)
 * ```
 * if (CNTG_YN=="1" && RFUS_YN=="Y") → 거부 통보 (§14-2 step 7 백업)
 * if (CNTG_YN=="1" && ACPT_YN=="1") → 주문 접수 통보 (KIS_ACK 백업, 중복 시 멱등)
 * if (CNTG_YN=="1" && ACPT_YN=="3") → 취소 접수 통보 (status=CANCELLED + KIS_CANCEL_ACK)
 * if (CNTG_YN=="2") → 체결 통보:
 *   - findByKisOrderNo(ODER_NO)
 *   - order_execution INSERT(CNTG_QTY, CNTG_UNPR, STCK_CNTG_HOUR)
 *   - audit FILL_RECEIVED
 *   - sum = sumExecutedQuantityByOrder(orderId)
 *   - sum < qty → PARTIALLY_FILLED + PARTIAL_FILL + STOMP partial
 *   - sum == qty → FILLED + FULL_FILL + STOMP filled + Kafka order.filled
 *   - sum > qty → EXECUTION_OVERFLOW + skip
 * ```
 *
 * ### Phase 1 wiring 한계 (자본 보호)
 * 본 클래스는 [processMessage] 핵심 흐름을 구현. 실제 KIS WS subscribe wiring(KisWebSocketBridge 통합)은
 * Phase 2 backend-core·KisWebSocketBridge 보강 시점에 합류. 본 PR은 핵심 분기 로직 + 단위 테스트로
 * 자본 보호 risk cover (§14-7 LOCK).
 *
 * ### Phase 2 graceful fallback (헌법 §6 + ADR 0066 ObjectProvider 패턴)
 * OrderRequestRepository·OrderExecutionRepository·OrderAuditEventRepository 미합류 시 — 본 listener는
 * ObjectProvider로 graceful skip + log.warn (자본 변동 path이므로 사용자 알림 의무).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class KisExecutionWsListener(
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val orderExecutionRepositoryProvider: ObjectProvider<OrderExecutionRepository>,
    private val orderAuditEventRepositoryProvider: ObjectProvider<OrderAuditEventRepository>,
    private val orderFilledKafkaProducer: OrderFilledKafkaProducer,
    private val orderRejectedKafkaProducer: OrderRejectedKafkaProducer,
    private val orderCancelledKafkaProducer: OrderCancelledKafkaProducer,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(KisExecutionWsListener::class.java)

    /**
     * KIS WS body 진입점 — [KisExecutionMessage.parseFromCaretDelimited] 파싱 후 [processMessage] 위임.
     *
     * 본 함수는 KisWebSocketBridge에서 H0STCNI0/H0STCNI9 수신 시 호출 (Phase 2 또는 후속 합류).
     */
    fun onWebSocketMessage(rawBody: String) {
        try {
            val msg = KisExecutionMessage.parseFromCaretDelimited(rawBody)
            processMessage(msg)
        } catch (e: Exception) {
            log.error("KisExecutionWsListener parse 실패: err={} body={}", e.message, rawBody.take(200))
        }
    }

    /**
     * §14-6 흐름 분기 LOCK 적용.
     *
     * 본 함수는 단위 테스트 진입점 — [KisExecutionMessage] 직접 주입 가능 (parser 우회).
     */
    fun processMessage(msg: KisExecutionMessage) {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable()
        if (orderRepo == null) {
            log.warn(
                "KisExecutionWsListener — OrderRequestRepository 미합류 (Phase 2 미완): odno={} cntgYn={}",
                msg.odno,
                msg.cntgYn,
            )
            return
        }

        val request =
            try {
                orderRepo.findByKisOrderNo(msg.odno)
            } catch (e: Exception) {
                log.error(
                    "KisExecutionWsListener findByKisOrderNo 실패: odno={} err={}",
                    msg.odno,
                    e.message,
                )
                null
            }

        if (request == null) {
            log.warn(
                "KisExecutionWsListener — OrderRequest 없음 (KIS WS leak 또는 race): odno={} cntgYn={}",
                msg.odno,
                msg.cntgYn,
            )
            return
        }

        when {
            msg.cntgYn == "1" && msg.rfusYn == "Y" -> handleRejected(msg, request)
            msg.cntgYn == "1" && msg.acptYn == "1" -> handleAckBackup(msg, request)
            msg.cntgYn == "1" && msg.acptYn == "3" -> handleCancelled(msg, request)
            msg.cntgYn == "2" -> handleExecution(msg, request)
            else -> {
                log.debug(
                    "KisExecutionWsListener — 미분류 (skip): odno={} cntgYn={} acptYn={} rfusYn={}",
                    msg.odno,
                    msg.cntgYn,
                    msg.acptYn,
                    msg.rfusYn,
                )
            }
        }
    }

    private fun handleRejected(
        msg: KisExecutionMessage,
        request: OrderRequest,
    ) {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable() ?: return
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)
        val reason = "WS_REJECTED:${msg.acptYn}"

        val updated =
            try {
                orderRepo.markCompleted(request.id!!, now, OrderStatus.REJECTED)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener markCompleted REJECTED 실패: orderId={} err={}", request.id, e.message)
                request.copy(status = OrderStatus.REJECTED, rejectReason = reason, completedAt = now)
            } ?: request.copy(status = OrderStatus.REJECTED, rejectReason = reason, completedAt = now)

        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = request.id,
                    userId = request.userId,
                    eventType = OrderAuditEventType.KIS_REJECT,
                    payloadJson = jsonOf(msg),
                    createdAt = now,
                ),
            )
        }

        orderRejectedKafkaProducer.publish(updated, reason)
        log.warn("KisExecutionWsListener REJECTED: orderId={} odno={}", request.id, msg.odno)
    }

    private fun handleAckBackup(
        msg: KisExecutionMessage,
        request: OrderRequest,
    ) {
        // §14-6: 주문 접수 통보 (§14-2 step 8 KIS_ACK의 백업 — 중복 시 멱등)
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)
        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = request.id,
                    userId = request.userId,
                    eventType = OrderAuditEventType.KIS_ACK,
                    payloadJson = jsonOf(msg, extra = mapOf("source" to "WS_BACKUP")),
                    createdAt = now,
                ),
            )
        }
        log.debug("KisExecutionWsListener ACK 백업: orderId={} odno={}", request.id, msg.odno)
    }

    private fun handleCancelled(
        msg: KisExecutionMessage,
        request: OrderRequest,
    ) {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable() ?: return
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)

        val updated =
            try {
                orderRepo.markCompleted(request.id!!, now, OrderStatus.CANCELLED)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener markCompleted CANCELLED 실패: orderId={} err={}", request.id, e.message)
                request.copy(status = OrderStatus.CANCELLED, completedAt = now)
            } ?: request.copy(status = OrderStatus.CANCELLED, completedAt = now)

        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = request.id,
                    userId = request.userId,
                    eventType = OrderAuditEventType.KIS_CANCEL_ACK,
                    payloadJson = jsonOf(msg),
                    createdAt = now,
                ),
            )
        }
        orderCancelledKafkaProducer.publish(updated)
        log.info("KisExecutionWsListener CANCELLED: orderId={} odno={}", request.id, msg.odno)
    }

    private fun handleExecution(
        msg: KisExecutionMessage,
        request: OrderRequest,
    ) {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable() ?: return
        val execRepo = orderExecutionRepositoryProvider.getIfAvailable()
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)
        val executedAt = parseExecutedAt(msg.stckCntgHour)

        if (execRepo == null) {
            log.warn(
                "KisExecutionWsListener — OrderExecutionRepository 미합류: orderId={} odno={}",
                request.id,
                msg.odno,
            )
            return
        }

        // 1. order_execution INSERT
        val execution =
            try {
                execRepo.save(
                    OrderExecution(
                        orderId = request.id!!,
                        userId = request.userId,
                        kisOrderNo = msg.odno,
                        executedQuantity = msg.cntgQty,
                        executedPrice = msg.cntgUnpr,
                        executedAt = executedAt,
                        createdAt = now,
                    ),
                )
            } catch (e: Exception) {
                log.error(
                    "KisExecutionWsListener orderExecution save 실패: orderId={} err={}",
                    request.id,
                    e.message,
                )
                return
            }

        // 2. audit FILL_RECEIVED
        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = request.id,
                    userId = request.userId,
                    eventType = OrderAuditEventType.FILL_RECEIVED,
                    payloadJson = jsonOf(msg),
                    createdAt = now,
                ),
            )
        }

        // 3. 누적 합 평가 → PARTIALLY_FILLED / FILLED / OVERFLOW
        val sum =
            try {
                execRepo.sumExecutedQuantityByOrder(request.id!!)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener sumExecutedQuantityByOrder 실패: orderId={} err={}", request.id, e.message)
                msg.cntgQty
            }

        when {
            sum < request.quantity -> {
                val partial =
                    try {
                        orderRepo.updateStatus(request.id!!, OrderStatus.PARTIALLY_FILLED, null)
                    } catch (e: Exception) {
                        log.error("updateStatus PARTIALLY_FILLED 실패: orderId={} err={}", request.id, e.message)
                        request.copy(status = OrderStatus.PARTIALLY_FILLED)
                    } ?: request.copy(status = OrderStatus.PARTIALLY_FILLED)

                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = request.id,
                            userId = request.userId,
                            eventType = OrderAuditEventType.PARTIAL_FILL,
                            payloadJson = jsonOf(msg, extra = mapOf("sum" to sum, "qty" to request.quantity)),
                            createdAt = now,
                        ),
                    )
                }
                orderFilledKafkaProducer.publish(partial, execution, partial = true)
                log.info(
                    "KisExecutionWsListener PARTIALLY_FILLED: orderId={} sum={}/{}",
                    request.id,
                    sum,
                    request.quantity,
                )
            }

            sum == request.quantity -> {
                val filled =
                    try {
                        orderRepo.markCompleted(request.id!!, now, OrderStatus.FILLED)
                    } catch (e: Exception) {
                        log.error("markCompleted FILLED 실패: orderId={} err={}", request.id, e.message)
                        request.copy(status = OrderStatus.FILLED, completedAt = now)
                    } ?: request.copy(status = OrderStatus.FILLED, completedAt = now)

                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = request.id,
                            userId = request.userId,
                            eventType = OrderAuditEventType.FULL_FILL,
                            payloadJson = jsonOf(msg, extra = mapOf("sum" to sum, "qty" to request.quantity)),
                            createdAt = now,
                        ),
                    )
                }
                orderFilledKafkaProducer.publish(filled, execution, partial = false)
                log.info("KisExecutionWsListener FILLED: orderId={} qty={}", request.id, sum)
            }

            else -> {
                // §14-4 risk 매트릭스: executions 누적 합 > order.quantity
                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = request.id,
                            userId = request.userId,
                            eventType = OrderAuditEventType.EXECUTION_OVERFLOW,
                            payloadJson = jsonOf(msg, extra = mapOf("sum" to sum, "qty" to request.quantity)),
                            createdAt = now,
                        ),
                    )
                }
                log.error(
                    "KisExecutionWsListener EXECUTION_OVERFLOW: orderId={} sum={} > qty={}",
                    request.id,
                    sum,
                    request.quantity,
                )
            }
        }
    }

    /**
     * STCK_CNTG_HOUR (HHMMSS) + today (KST) → Instant 합성.
     */
    internal fun parseExecutedAt(hhmmss: String): Instant {
        val time =
            try {
                LocalTime.parse(hhmmss.padStart(6, '0'), DateTimeFormatter.ofPattern("HHmmss"))
            } catch (e: Exception) {
                log.warn("parseExecutedAt HHMMSS 파싱 실패 — 현재시각 fallback: hhmmss={} err={}", hhmmss, e.message)
                return Instant.now(clock)
            }
        val today = LocalDate.now(clock)
        return today.atTime(time).atZone(ZoneId.of("Asia/Seoul")).toInstant()
    }

    private fun jsonOf(
        msg: KisExecutionMessage,
        extra: Map<String, Any?> = emptyMap(),
    ): String =
        try {
            objectMapper.writeValueAsString(
                mapOf(
                    "odno" to msg.odno,
                    "code" to msg.code,
                    "cntgYn" to msg.cntgYn,
                    "acptYn" to msg.acptYn,
                    "rfusYn" to msg.rfusYn,
                    "cntgQty" to msg.cntgQty,
                    "cntgUnpr" to msg.cntgUnpr.toPlainString(),
                    "stckCntgHour" to msg.stckCntgHour,
                ) + extra,
            )
        } catch (e: Exception) {
            "{\"error\":\"audit json failed: ${e.message}\"}"
        }
}
