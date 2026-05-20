package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
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
 * KIS WS H0STCNI0/H0STCNI9 체결 통보 처리 (ADR 0068 §14-6 LOCK + ADR 0084 P0-3 race fix).
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
 * ### ADR 0084 P0-3 race fix — pre-insert 패턴 (architect 권장)
 * 외부 코드 리뷰 결함 #4 — KIS WS 체결 통보가 REST submit ACK 응답보다 먼저 도달 가능.
 * 기존 path: `findByKisOrderNo == null` → graceful skip → 체결 row 영속 0 → sum 계산 누락 → FILLED 전환 안 됨 → audit 깨짐.
 *
 * 신규 path (pre-insert + retry):
 *  1. `findByKisOrderNo == null` 시 ─ 즉시 skip 대신 짧은 retry (50ms × 3회) — ACK 처리 race window 흡수
 *  2. retry 후에도 null이면 ─ pre-insert 패턴: OrderRequest stub row INSERT (kisOrderNo set + status=ACK + signalId=0 sentinel + idempotencyKey="WS_PREINSERT:{kisOrderNo}")
 *     - 후속 REST ACK 응답이 도착하면 [OrderRouterKafkaListener] step 8이 동일 kisOrderNo로 update 갱신 (idempotency 정합)
 *     - 또는 [KisOrderInquireCcldHttpAdapter] REST backfill이 동일 kisOrderNo row를 cross-ref
 *  3. event_id 멱등 가드 ─ `{kisOrderNo}:{stckCntgHour}:{cntgQty}` 합성 후 [OrderExecutionRepository.existsByEventId] true 시 skip
 *
 * ### eventId 합성 규칙 (ADR 0084 §2-3 V35 정합)
 * `{odno}:{stckCntgHour}:{cntgQty}` — WS + REST inquire-daily-ccld backfill 양 경로 동일 합성.
 * Flyway V35 `uq_execution_event_id` partial UNIQUE 인덱스가 1차 DB level dedup, 본 listener의 existsByEventId가 2차 application level dedup.
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
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0084 §2-8)
 */
@Component
class KisExecutionWsListener(
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val orderExecutionRepositoryProvider: ObjectProvider<OrderExecutionRepository>,
    private val orderAuditEventRepositoryProvider: ObjectProvider<OrderAuditEventRepository>,
    private val orderFilledKafkaProducer: OrderFilledKafkaProducer,
    private val orderRejectedKafkaProducer: OrderRejectedKafkaProducer,
    private val orderCancelledKafkaProducer: OrderCancelledKafkaProducer,
    private val accountBalanceChangedKafkaProducer: AccountBalanceChangedKafkaProducer,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) {
    private val log = LoggerFactory.getLogger(KisExecutionWsListener::class.java)

    companion object {
        /**
         * ADR 0084 P0-3 — race fix retry 횟수 (50ms × 3회 = 150ms max).
         * REST submit ACK 응답 latency 측정 후 backlog §62에서 운영 tune.
         */
        const val RACE_RETRY_COUNT: Int = 3
        const val RACE_RETRY_INTERVAL_MS: Long = 50L

        /**
         * ADR 0084 P0-3 — event_id 합성 규칙.
         * `{kisOrderNo}:{stckCntgHour}:{cntgQty}` — WS 통보 + REST inquire-daily-ccld backfill 양 경로 동일.
         * Flyway V35 `uq_execution_event_id` UNIQUE partial index와 정합.
         */
        fun buildEventId(
            kisOrderNo: String,
            stckCntgHour: String,
            cntgQty: Int,
        ): String = "$kisOrderNo:$stckCntgHour:$cntgQty"
    }

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
     * §14-6 흐름 분기 LOCK 적용 + ADR 0084 P0-3 race fix.
     *
     * 본 함수는 단위 테스트 진입점 — [KisExecutionMessage] 직접 주입 가능 (parser 우회).
     *
     * ### ADR 0084 P0-3 — race fix 흐름
     * 1. findByKisOrderNo == null 시 즉시 skip 대신 짧은 retry (50ms × 3회) — REST ACK 처리 race window 흡수
     * 2. retry 후에도 null이면 pre-insert 패턴 진입 ([preInsertOrderRequest]) — 후속 REST ACK이 동일 kisOrderNo update로 정합
     * 3. pre-insert 실패 시에도 graceful skip (자본 보호 — listener thread 안 죽이기 + log.error + audit)
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

        // ADR 0084 P0-3 — race fix 1단계: short retry (50ms × 3회)
        // REST submit ACK 응답이 WS 통보보다 늦게 도달하는 race window 흡수.
        val request = findOrderRequestWithRetry(orderRepo, msg.odno)

        if (request == null) {
            // ADR 0084 P0-3 — race fix 2단계: pre-insert 패턴
            // retry 후에도 못 찾으면 WS 통보가 진실원 → OrderRequest stub row INSERT.
            // 후속 REST ACK 응답 시 OrderRouterKafkaListener step 8이 동일 kisOrderNo로 update 정합.
            val preInserted = preInsertOrderRequest(orderRepo, msg)
            if (preInserted == null) {
                log.warn(
                    "KisExecutionWsListener — pre-insert 실패 — graceful skip: odno={} cntgYn={}",
                    msg.odno,
                    msg.cntgYn,
                )
                return
            }
            dispatchByFlag(msg, preInserted)
            return
        }

        dispatchByFlag(msg, request)
    }

    /**
     * ADR 0084 P0-3 — findByKisOrderNo race fix retry helper.
     * REST ACK 응답이 늦게 도달하는 race window를 흡수 (50ms × 3회 = 최대 150ms).
     *
     * 본 retry는 listener thread block을 유발하나 — KIS WS 메시지 처리는 ReactorNetty event-loop가 아닌
     * KisWebSocketBridge.handleMessage scheduler thread에서 실행되므로 wsSession latency에 영향 없음.
     * (handleMessage가 receiveFlux.doOnNext에서 호출 → Reactor 기본 publishOn이 default scheduler).
     *
     * retry 횟수 + 간격은 backlog §62로 운영 측정 후 tune.
     */
    private fun findOrderRequestWithRetry(
        orderRepo: OrderRequestRepository,
        kisOrderNo: String,
    ): OrderRequest? {
        repeat(RACE_RETRY_COUNT) { attempt ->
            val found =
                try {
                    orderRepo.findByKisOrderNo(kisOrderNo)
                } catch (e: Exception) {
                    log.error(
                        "KisExecutionWsListener findByKisOrderNo 실패: odno={} attempt={} err={}",
                        kisOrderNo,
                        attempt,
                        e.message,
                    )
                    null
                }
            if (found != null) {
                if (attempt > 0) {
                    log.info(
                        "KisExecutionWsListener race retry hit: odno={} attempt={}",
                        kisOrderNo,
                        attempt,
                    )
                }
                return found
            }
            if (attempt < RACE_RETRY_COUNT - 1) {
                try {
                    Thread.sleep(RACE_RETRY_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    /**
     * ADR 0084 P0-3 — pre-insert 패턴 (architect 권장 §2-1).
     *
     * race fix retry 후에도 OrderRequest 부재 시 — WS 통보를 진실원으로 stub row INSERT.
     * 후속 REST ACK 응답 시 [OrderRouterKafkaListener.updateKisAck] 가 동일 kisOrderNo row를 update 갱신 (idempotency 정합).
     *
     * pre-insert sentinel 매핑:
     *  - signalId = 0L (sentinel — 후속 ACK이 진짜 signalId로 update 또는 audit에서 trace)
     *  - strategyId = 0L (sentinel)
     *  - userId = msg.custId (KIS HTS 사용자 ID — credential identity)
     *  - code = msg.code (KIS PDNO)
     *  - side = msg.sellBuyCls "02"=BUY · "01"=SELL (raw L389~L446 정합)
     *  - priceType = MARKET (msg.odKind raw 변환 불완전 — sentinel)
     *  - quantity = msg.orderQty (cross-ref)
     *  - limitPrice = null (priceType MARKET sentinel)
     *  - status = OrderStatus.ACK (WS 통보 = KIS 접수 후 단계)
     *  - mode = REAL or MOCK — 본 sentinel은 REAL (MOCK 환경은 후속 REST ACK이 정정)
     *  - kisOrderNo = msg.odno
     *  - idempotencyKey = "WS_PREINSERT:{odno}" (DB UNIQUE 정합 — REST ACK 시 별도 row와 충돌 없음)
     *  - requestedAt = Instant.now (sentinel — 후속 ACK이 진짜 시각으로 update 못함, audit trace로만 의미)
     *
     * **사용자 신뢰 의무**: pre-insert row는 audit에서 KIS_ACK + extra `{"source":"WS_PREINSERT"}` 마커로 trace 가능.
     */
    private fun preInsertOrderRequest(
        orderRepo: OrderRequestRepository,
        msg: KisExecutionMessage,
    ): OrderRequest? {
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)
        val sentinelIdempotencyKey = "WS_PREINSERT:${msg.odno}"

        // pre-insert 직전 — 동일 idempotencyKey 이미 합류했나? (재진입 멱등)
        val existing =
            try {
                orderRepo.findByIdempotencyKey(sentinelIdempotencyKey)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener preInsert findByIdempotencyKey 실패: odno={} err={}", msg.odno, e.message)
                null
            }
        if (existing != null) {
            return existing
        }

        val side = if (msg.sellBuyCls == "01") OrderSide.SELL else OrderSide.BUY
        val stub =
            OrderRequest(
                userId = msg.custId.ifBlank { "WS_PREINSERT" },
                signalId = 0L,
                strategyId = 0L,
                code = msg.code,
                side = side,
                priceType = OrderPriceType.MARKET,
                quantity = msg.orderQty.coerceAtLeast(1),
                limitPrice = null,
                status = OrderStatus.ACK,
                mode = TradingMode.REAL,
                kisOrderNo = msg.odno,
                idempotencyKey = sentinelIdempotencyKey,
                requestedAt = now,
                acknowledgedAt = now,
            )

        val saved =
            try {
                orderRepo.save(stub)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener pre-insert save 실패: odno={} err={}", msg.odno, e.message)
                return null
            }

        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = saved.id,
                    userId = saved.userId,
                    eventType = OrderAuditEventType.KIS_ACK,
                    payloadJson = jsonOf(msg, extra = mapOf("source" to "WS_PREINSERT")),
                    createdAt = now,
                ),
            )
        }

        log.warn(
            "KisExecutionWsListener — WS_PREINSERT row 합류 (ADR 0084 P0-3 race fix): orderId={} odno={} sentinelUser={}",
            saved.id,
            msg.odno,
            maskUserId(saved.userId),
        )
        return saved
    }

    private fun dispatchByFlag(
        msg: KisExecutionMessage,
        request: OrderRequest,
    ) {
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

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    /**
     * P2-4-3 진보 페르소나 가드 (ADR 0087, 2026-05-19).
     *
     * 영속된 [OrderRequest.id]는 invariant상 non-null. null이면:
     *  - JPA save 후 generated key 미설정 (영속성 결함)
     *  - pre-insert path stub row 생성 실패 후 in-memory copy 누락
     *  - 또는 caller가 잘못된 (영속 안 된) request 전달
     *
     * 어느 경로든 자본 path 실행 불가 — 명시 [requireNotNull] throw + handler 이름 메시지로
     * 운영자 audit 시 즉시 root cause 매칭 가능 (vs raw `!!` KotlinNullPointerException).
     */
    private fun requireOrderId(
        request: OrderRequest,
        handlerName: String,
    ): Long =
        requireNotNull(request.id) {
            "KisExecutionWsListener.$handlerName — request.id null (자본 path invariant 위반): " +
                "kisOrderNo=${request.kisOrderNo} userId=${maskUserId(request.userId)}"
        }

    private fun handleRejected(
        msg: KisExecutionMessage,
        request: OrderRequest,
    ) {
        val orderRepo = orderRequestRepositoryProvider.getIfAvailable() ?: return
        val auditRepo = orderAuditEventRepositoryProvider.getIfAvailable()
        val now = Instant.now(clock)
        val reason = "WS_REJECTED:${msg.acptYn}"

        // P2-4-3 진보 페르소나 (ADR 0087) — `!!` 가드: 영속된 request.id는 invariant상 non-null.
        // null이면 OrderRequest persistence 또는 pre-insert 로직 결함 — 명시 throw로 운영자 즉시 매칭.
        val requestId = requireOrderId(request, "handleRejected")

        val updated =
            try {
                orderRepo.markCompleted(requestId, now, OrderStatus.REJECTED)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener markCompleted REJECTED 실패: orderId={} err={}", requestId, e.message)
                request.copy(status = OrderStatus.REJECTED, rejectReason = reason, completedAt = now)
            } ?: request.copy(status = OrderStatus.REJECTED, rejectReason = reason, completedAt = now)

        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = requestId,
                    userId = request.userId,
                    eventType = OrderAuditEventType.KIS_REJECT,
                    payloadJson = jsonOf(msg),
                    createdAt = now,
                ),
            )
        }

        orderRejectedKafkaProducer.publish(updated, reason)
        log.warn("KisExecutionWsListener REJECTED: orderId={} odno={}", requestId, msg.odno)
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

        // P2-4-3 진보 페르소나 (ADR 0087) — `!!` 가드 (handleRejected 정합)
        val requestId = requireOrderId(request, "handleCancelled")

        val updated =
            try {
                orderRepo.markCompleted(requestId, now, OrderStatus.CANCELLED)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener markCompleted CANCELLED 실패: orderId={} err={}", requestId, e.message)
                request.copy(status = OrderStatus.CANCELLED, completedAt = now)
            } ?: request.copy(status = OrderStatus.CANCELLED, completedAt = now)

        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = requestId,
                    userId = request.userId,
                    eventType = OrderAuditEventType.KIS_CANCEL_ACK,
                    payloadJson = jsonOf(msg),
                    createdAt = now,
                ),
            )
        }
        orderCancelledKafkaProducer.publish(updated)
        // Polling → STOMP push (2026-05-20) — 잔고 변경 trigger (취소도 예약 cash 회복).
        accountBalanceChangedKafkaProducer.publish(
            userId = updated.userId,
            mode = updated.mode,
            reason = AccountBalanceChangedKafkaProducer.REASON_CANCELLED,
            triggerKisOrderNo = msg.odno,
        )
        log.info("KisExecutionWsListener CANCELLED: orderId={} odno={}", requestId, msg.odno)
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

        // P2-4-3 진보 페르소나 (ADR 0087) — `!!` 가드 (handleRejected·handleCancelled 정합)
        // handleExecution 4 site (line 458·494·504·533 기존) 일괄 흡수.
        val requestId = requireOrderId(request, "handleExecution")

        if (execRepo == null) {
            log.warn(
                "KisExecutionWsListener — OrderExecutionRepository 미합류: orderId={} odno={}",
                requestId,
                msg.odno,
            )
            return
        }

        // ADR 0084 P0-3 — event_id 멱등 가드 (WS + REST backfill 양 경로 dedup LOCK)
        val eventId = buildEventId(msg.odno, msg.stckCntgHour, msg.cntgQty)
        val alreadyPersisted =
            try {
                execRepo.existsByEventId(eventId)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener existsByEventId 실패 — fail-open INSERT 진입: eventId={} err={}", eventId, e.message)
                false
            }
        if (alreadyPersisted) {
            log.info(
                "KisExecutionWsListener — 중복 체결 skip (event_id dedup): orderId={} eventId={}",
                requestId,
                eventId,
            )
            return
        }

        // 1. order_execution INSERT (eventId 포함)
        val execution =
            try {
                execRepo.save(
                    OrderExecution(
                        orderId = requestId,
                        userId = request.userId,
                        kisOrderNo = msg.odno,
                        executedQuantity = msg.cntgQty,
                        executedPrice = msg.cntgUnpr,
                        executedAt = executedAt,
                        eventId = eventId,
                        createdAt = now,
                    ),
                )
            } catch (e: Exception) {
                log.error(
                    "KisExecutionWsListener orderExecution save 실패: orderId={} eventId={} err={}",
                    requestId,
                    eventId,
                    e.message,
                )
                return
            }

        // 2. audit FILL_RECEIVED
        auditRepo?.runCatching {
            save(
                OrderAuditEvent(
                    orderId = requestId,
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
                execRepo.sumExecutedQuantityByOrder(requestId)
            } catch (e: Exception) {
                log.error("KisExecutionWsListener sumExecutedQuantityByOrder 실패: orderId={} err={}", requestId, e.message)
                msg.cntgQty
            }

        when {
            sum < request.quantity -> {
                val partial =
                    try {
                        orderRepo.updateStatus(requestId, OrderStatus.PARTIALLY_FILLED, null)
                    } catch (e: Exception) {
                        log.error("updateStatus PARTIALLY_FILLED 실패: orderId={} err={}", requestId, e.message)
                        request.copy(status = OrderStatus.PARTIALLY_FILLED)
                    } ?: request.copy(status = OrderStatus.PARTIALLY_FILLED)

                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = requestId,
                            userId = request.userId,
                            eventType = OrderAuditEventType.PARTIAL_FILL,
                            payloadJson = jsonOf(msg, extra = mapOf("sum" to sum, "qty" to request.quantity)),
                            createdAt = now,
                        ),
                    )
                }
                orderFilledKafkaProducer.publish(partial, execution, partial = true)
                // Polling → STOMP push (2026-05-20) — 부분 체결도 잔고 변경 (현금·평가금액 즉시 변동).
                accountBalanceChangedKafkaProducer.publish(
                    userId = partial.userId,
                    mode = partial.mode,
                    reason = AccountBalanceChangedKafkaProducer.REASON_FILLED,
                    triggerKisOrderNo = msg.odno,
                )
                log.info(
                    "KisExecutionWsListener PARTIALLY_FILLED: orderId={} sum={}/{}",
                    requestId,
                    sum,
                    request.quantity,
                )
            }

            sum == request.quantity -> {
                val filled =
                    try {
                        orderRepo.markCompleted(requestId, now, OrderStatus.FILLED)
                    } catch (e: Exception) {
                        log.error("markCompleted FILLED 실패: orderId={} err={}", requestId, e.message)
                        request.copy(status = OrderStatus.FILLED, completedAt = now)
                    } ?: request.copy(status = OrderStatus.FILLED, completedAt = now)

                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = requestId,
                            userId = request.userId,
                            eventType = OrderAuditEventType.FULL_FILL,
                            payloadJson = jsonOf(msg, extra = mapOf("sum" to sum, "qty" to request.quantity)),
                            createdAt = now,
                        ),
                    )
                }
                orderFilledKafkaProducer.publish(filled, execution, partial = false)
                // Polling → STOMP push (2026-05-20) — 전체 체결 → 잔고 즉시 invalidate trigger.
                accountBalanceChangedKafkaProducer.publish(
                    userId = filled.userId,
                    mode = filled.mode,
                    reason = AccountBalanceChangedKafkaProducer.REASON_FILLED,
                    triggerKisOrderNo = msg.odno,
                )
                log.info("KisExecutionWsListener FILLED: orderId={} qty={}", requestId, sum)
            }

            else -> {
                // §14-4 risk 매트릭스: executions 누적 합 > order.quantity
                auditRepo?.runCatching {
                    save(
                        OrderAuditEvent(
                            orderId = requestId,
                            userId = request.userId,
                            eventType = OrderAuditEventType.EXECUTION_OVERFLOW,
                            payloadJson = jsonOf(msg, extra = mapOf("sum" to sum, "qty" to request.quantity)),
                            createdAt = now,
                        ),
                    )
                }
                log.error(
                    "KisExecutionWsListener EXECUTION_OVERFLOW: orderId={} sum={} > qty={}",
                    requestId,
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
