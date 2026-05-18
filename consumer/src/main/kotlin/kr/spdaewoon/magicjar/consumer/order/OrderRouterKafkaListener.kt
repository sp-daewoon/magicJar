package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderIntent
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderIdempotencyGuard
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.TradingModeGate
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Instant

/**
 * `order.intent.v1` Kafka listener — T-O 진입점 (ADR 0068 §14-2 LOCK).
 *
 * ### 12 step 흐름 (§14-2 본문 인용)
 * ```
 * 1. payload deserialize → OrderIntent
 * 2. OrderIdempotencyGuard.acquire (Redis SETNX 60s, key="idem:order:{signalId}:{userId}")
 * 3. TradingModeGate.resolveMode(userId) — 3중 게이트
 * 4. KillSwitch 재검증
 * 5. order_request INSERT(status=REQUESTED, mode)
 * 6. STOMP notifyRequested + Kafka order.requested.v1 publish
 * 7. KisOrderHttpAdapter.submit(intent, mode) — 5xx 1회 retry
 * 8. order_request UPDATE(status=ACK, kis_order_no, kis_fwdg_orgno, acknowledged_at)
 * 9. audit KIS_ACK
 * 10. (FILLED 통보는 KisExecutionWsListener)
 * 11. Kafka commit (MANUAL_IMMEDIATE)
 * 12. Micrometer counter magicjar_order_processed_total{result=ACK|REJECTED|SKIPPED}
 * ```
 *
 * ### Kafka 설정 LOCK (§14-2 자본 보호)
 * - enable.auto.commit=false (containerFactory에서 ack-mode=MANUAL_IMMEDIATE)
 * - max.poll.records=1 (분당 burst 회피)
 * - concurrency=1 per partition (멀티스레드 race 회피)
 * - isolation.level=read_committed
 *
 * 본 PR은 listener 흐름 LOCK 구현. ack-mode·concurrency 등 컨테이너 factory 별도 설정은
 * application.yml `spring.kafka.listener.ack-mode=manual_immediate` 의존 (기존 설정 정합).
 *
 * ### Phase 2 graceful fallback (헌법 §6 + ADR 0066 ObjectProvider 패턴)
 * OrderRequestRepository / OrderAuditEventRepository / KillSwitchQueryPort 미합류 시 ObjectProvider로
 * graceful skip + log.warn — 자본 변동 path이므로 미합류 시점 명확히 알림.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class OrderRouterKafkaListener(
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val orderAuditEventRepositoryProvider: ObjectProvider<OrderAuditEventRepository>,
    private val killSwitchQueryPortProvider: ObjectProvider<KillSwitchQueryPort>,
    private val tradingModeGate: TradingModeGate,
    private val orderIdempotencyGuard: OrderIdempotencyGuard,
    private val kisOrderPort: KisOrderPort,
    private val orderRequestedKafkaProducer: OrderRequestedKafkaProducer,
    private val orderRejectedKafkaProducer: OrderRejectedKafkaProducer,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OrderRouterKafkaListener::class.java)

    companion object {
        const val TOPIC = "order.intent.v1"
        const val GROUP = "magicjar-consumer-order"
        private const val RETRY_BACKOFF_MS = 500L
    }

    /**
     * Kafka listen — partition key = userId (ADR 0067 §7-1 OrderIntentKafkaProducer 정합).
     * groupId = `magicjar-consumer-order` 독립 thread + offset.
     */
    @KafkaListener(
        topics = [TOPIC],
        groupId = GROUP,
    )
    fun onOrderIntent(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        try {
            // Step 1: payload deserialize
            val intent = deserialize(record.value())
            if (intent == null) {
                log.error(
                    "OrderRouterKafkaListener deserialize 실패 (poison pill skip): offset={} key={} value={}",
                    record.offset(),
                    record.key(),
                    record.value()?.take(300),
                )
                ack.acknowledge() // poison pill 회피 — DLQ는 backlog
                return
            }

            // Step 2: 멱등성 가드
            val idemKey = "idem:order:${intent.idempotencyKey}"
            if (!orderIdempotencyGuard.acquire(idemKey, 60)) {
                writeAudit(
                    orderId = null,
                    userId = intent.userId,
                    eventType = OrderAuditEventType.IDEMPOTENCY_SKIPPED,
                    payload = mapOf("idempotencyKey" to intent.idempotencyKey, "signalId" to intent.signalId),
                )
                log.warn(
                    "OrderRouterKafkaListener IDEMPOTENCY_SKIPPED: userId={} signalId={} key={}",
                    intent.userId,
                    intent.signalId,
                    intent.idempotencyKey,
                )
                ack.acknowledge()
                return
            }

            // Step 3: TradingModeGate (3중 게이트)
            val modeResult = tradingModeGate.resolveMode(intent.userId)
            if (modeResult is OrderResult.Left) {
                val reason = "REAL_GATE_DENIED:${modeResult.value.condition}"
                handleRejected(intent, TradingMode.REAL, reason, OrderAuditEventType.REAL_GATE_DENIED)
                ack.acknowledge()
                return
            }
            val mode = (modeResult as OrderResult.Right).value

            // Step 4: KillSwitch 재검증 (ADR 0024·0043)
            val killSwitchPort = killSwitchQueryPortProvider.getIfAvailable()
            val killSwitchOn =
                killSwitchPort?.let {
                    try {
                        it.isOn(intent.userId)
                    } catch (e: Exception) {
                        log.warn("KillSwitchQueryPort.isOn 실패 (fallback false): userId={} err={}", intent.userId, e.message)
                        false
                    }
                } ?: false

            if (killSwitchOn) {
                handleRejected(intent, mode, "KILLSWITCH_ON", OrderAuditEventType.KILLSWITCH_DENIED)
                ack.acknowledge()
                return
            }

            // Step 5: order_request INSERT(status=REQUESTED)
            val saved =
                when (val result = persistRequested(intent, mode)) {
                    is PersistResult.NotWired -> {
                        // Repository 미합류 — Phase 2 미완. 자본 path이므로 listener 진행 중단 + log.error.
                        log.error(
                            "OrderRouterKafkaListener — OrderRequestRepository 미합류 (Phase 2 미완): userId={} signalId={}",
                            intent.userId,
                            intent.signalId,
                        )
                        ack.acknowledge()
                        return
                    }
                    is PersistResult.SaveFailed -> {
                        // save 자체는 시도됐으나 DB 단에서 실패 (FK violation·연결 끊김 등). root cause 명시.
                        log.error(
                            "OrderRouterKafkaListener — OrderRequestRepository.save 실패 (listener 진행 중단): userId={} signalId={} reason={}",
                            intent.userId,
                            intent.signalId,
                            result.reason,
                        )
                        ack.acknowledge()
                        return
                    }
                    is PersistResult.Ok -> result.saved
                }

            // Step 6: Kafka order.requested.v1 publish (STOMP notify는 Phase 2 backend-core가 listen)
            orderRequestedKafkaProducer.publish(saved)

            // Step 7: KIS REST submit (5xx 1회 retry)
            val kisResult =
                submitWithRetry(intent, mode)
            if (kisResult is OrderResult.Left) {
                val deny = kisResult.value
                val rejectReason =
                    when (deny) {
                        is DenyReason.KisRejected -> "${deny.rtCd}:${deny.msg}"
                        is DenyReason.KisException -> "KIS_EXCEPTION:${deny.cause}"
                        is DenyReason.RealGateDenied -> "REAL_GATE_DENIED:${deny.condition}"
                        DenyReason.IdempotencyDuplicate -> "IDEMPOTENCY_DUPLICATE"
                        DenyReason.KillSwitchOn -> "KILLSWITCH_ON"
                    }
                val now = Instant.now()
                val rejected =
                    try {
                        orderRequestRepositoryProvider
                            .getIfAvailable()
                            ?.updateStatus(saved.id!!, OrderStatus.REJECTED, rejectReason)
                            ?: saved.copy(status = OrderStatus.REJECTED, rejectReason = rejectReason, completedAt = now)
                    } catch (e: Exception) {
                        log.error("updateStatus REJECTED 실패: orderId={} err={}", saved.id, e.message)
                        saved.copy(status = OrderStatus.REJECTED, rejectReason = rejectReason, completedAt = now)
                    }

                val auditType =
                    when (deny) {
                        is DenyReason.KisRejected -> OrderAuditEventType.KIS_REJECT
                        is DenyReason.KisException -> OrderAuditEventType.KIS_EXCEPTION
                        else -> OrderAuditEventType.KIS_REJECT
                    }
                writeAudit(
                    orderId = saved.id,
                    userId = saved.userId,
                    eventType = auditType,
                    payload = mapOf("reason" to rejectReason),
                )
                orderRejectedKafkaProducer.publish(rejected, rejectReason)
                ack.acknowledge()
                return
            }

            // Step 8·9: ACK 처리
            val ackData = (kisResult as OrderResult.Right).value
            val ackTime = Instant.now()
            try {
                orderRequestRepositoryProvider
                    .getIfAvailable()
                    ?.updateKisAck(saved.id!!, ackData.kisOrderNo, ackData.kisFwdgOrgno, ackTime)
            } catch (e: Exception) {
                log.error("updateKisAck 실패: orderId={} err={}", saved.id, e.message)
            }

            writeAudit(
                orderId = saved.id,
                userId = saved.userId,
                eventType = OrderAuditEventType.KIS_ACK,
                payload = mapOf("ODNO" to ackData.kisOrderNo, "KRX_FWDG_ORD_ORGNO" to ackData.kisFwdgOrgno),
            )

            log.info(
                "OrderRouterKafkaListener ACK: userId={} signalId={} code={} side={} ODNO={}",
                intent.userId,
                intent.signalId,
                intent.code,
                intent.side,
                ackData.kisOrderNo,
            )

            // Step 11: Kafka commit
            ack.acknowledge()
        } catch (e: Exception) {
            log.error(
                "OrderRouterKafkaListener 처리 실패 (자본 path — ack 보류, 재처리): offset={} key={} err={}",
                record.offset(),
                record.key(),
                e.message,
                e,
            )
            // 자본 path이므로 unexpected 예외는 ack 보류 — Kafka가 재처리.
            // 1회 retry 후 동일 실패 시 운영 알림(backlog DLQ) 필요.
        }
    }

    /**
     * Step 5 — REQUESTED 상태 영속.
     *
     * **반환 분기 (§62 hotfix — caller 로그 misleading 정정)**:
     * - [PersistResult.NotWired] — Repository bean 미합류 (Phase 2 미완)
     * - [PersistResult.SaveFailed] — Repository.save 자체가 Exception 발생 (FK violation·연결 끊김 등)
     * - [PersistResult.Ok] — 정상 영속 + saved row 반환
     */
    private fun persistRequested(
        intent: OrderIntent,
        mode: TradingMode,
    ): PersistResult {
        val repo = orderRequestRepositoryProvider.getIfAvailable() ?: return PersistResult.NotWired
        val request =
            OrderRequest(
                userId = intent.userId,
                signalId = intent.signalId,
                strategyId = intent.strategyId,
                code = intent.code,
                side = intent.side,
                priceType = intent.priceType,
                quantity = intent.quantity,
                limitPrice = intent.limitPrice,
                status = OrderStatus.REQUESTED,
                mode = mode,
                idempotencyKey = intent.idempotencyKey,
                requestedAt = Instant.now(),
            )
        return try {
            PersistResult.Ok(repo.save(request))
        } catch (e: Exception) {
            log.error("OrderRequestRepository.save 실패: userId={} signalId={} err={}", intent.userId, intent.signalId, e.message)
            PersistResult.SaveFailed(e.message ?: "unknown")
        }
    }

    /**
     * [persistRequested] 결과 분기 — caller 로그 메시지 분리용 (§62 hotfix).
     */
    internal sealed class PersistResult {
        /** Repository ObjectProvider가 bean을 찾지 못함 — Phase 2 미완 상태. */
        object NotWired : PersistResult()

        /** Repository.save() 호출 자체가 Exception 발생 — DB 단 결함 (FK violation·연결 끊김 등). */
        data class SaveFailed(
            val reason: String,
        ) : PersistResult()

        /** 정상 영속 — saved row 반환. */
        data class Ok(
            val saved: OrderRequest,
        ) : PersistResult()
    }

    /**
     * Step 3·4 REJECTED 공통 처리 (게이트 단계 — order_request INSERT(REJECTED) + audit + Kafka publish).
     */
    private fun handleRejected(
        intent: OrderIntent,
        mode: TradingMode,
        reason: String,
        auditType: OrderAuditEventType,
    ) {
        val now = Instant.now()
        val rejected =
            OrderRequest(
                userId = intent.userId,
                signalId = intent.signalId,
                strategyId = intent.strategyId,
                code = intent.code,
                side = intent.side,
                priceType = intent.priceType,
                quantity = intent.quantity,
                limitPrice = intent.limitPrice,
                status = OrderStatus.REJECTED,
                mode = mode,
                idempotencyKey = intent.idempotencyKey,
                rejectReason = reason,
                requestedAt = now,
                completedAt = now,
            )

        val saved =
            try {
                orderRequestRepositoryProvider.getIfAvailable()?.save(rejected) ?: rejected
            } catch (e: Exception) {
                log.error("REJECTED save 실패: userId={} err={}", intent.userId, e.message)
                rejected
            }

        writeAudit(saved.id, saved.userId, auditType, mapOf("reason" to reason))
        orderRejectedKafkaProducer.publish(saved, reason)
        log.warn(
            "OrderRouterKafkaListener REJECTED: userId={} signalId={} reason={}",
            intent.userId,
            intent.signalId,
            reason,
        )
    }

    /**
     * Step 7 — KIS REST submit + 5xx 1회 retry (§14-2 step 7 + §14-4 risk 매트릭스).
     */
    private fun submitWithRetry(
        intent: OrderIntent,
        mode: TradingMode,
    ): OrderResult<DenyReason, kr.spdaewoon.magicjar.domain.order.execution.KisOrderAck> {
        var attempt = 0
        while (true) {
            try {
                return kisOrderPort.submit(intent, mode)
            } catch (e: WebClientResponseException) {
                if (e.statusCode.is5xxServerError && attempt == 0) {
                    attempt++
                    log.warn(
                        "KIS REST 5xx 1회 retry: userId={} signalId={} status={}",
                        intent.userId,
                        intent.signalId,
                        e.statusCode,
                    )
                    Thread.sleep(RETRY_BACKOFF_MS)
                    continue
                }
                log.error(
                    "KIS REST 5xx 재시도 후 실패: userId={} signalId={} status={}",
                    intent.userId,
                    intent.signalId,
                    e.statusCode,
                )
                return OrderResult.Left(DenyReason.KisException("KIS_5XX:${e.statusCode.value()}"))
            } catch (e: Exception) {
                log.error("KIS REST submit 예외: userId={} signalId={} err={}", intent.userId, intent.signalId, e.message)
                return OrderResult.Left(DenyReason.KisException(e.message ?: "UNKNOWN"))
            }
        }
    }

    /**
     * audit row 영속 — Repository 미합류 시 graceful skip + log.warn.
     */
    private fun writeAudit(
        orderId: Long?,
        userId: String,
        eventType: OrderAuditEventType,
        payload: Map<String, Any?>,
    ) {
        val repo = orderAuditEventRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.warn(
                "writeAudit skip — OrderAuditEventRepository 미합류 (Phase 2 미완): orderId={} userId={} eventType={}",
                orderId,
                userId,
                eventType,
            )
            return
        }
        try {
            repo.save(
                OrderAuditEvent(
                    orderId = orderId,
                    userId = userId,
                    eventType = eventType,
                    payloadJson = objectMapper.writeValueAsString(payload),
                    createdAt = Instant.now(),
                ),
            )
        } catch (e: Exception) {
            log.error("writeAudit 실패: orderId={} userId={} eventType={} err={}", orderId, userId, eventType, e.message)
        }
    }

    /**
     * `order.intent.v1` payload deserialize (ADR 0067 OrderIntentKafkaProducer schema 정합).
     */
    internal fun deserialize(value: String?): OrderIntent? {
        if (value.isNullOrBlank()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
            val signalId = (payload["signalId"] as? Number)?.toLong() ?: return null
            val strategyId = (payload["strategyId"] as? Number)?.toLong() ?: return null
            val userId = payload["userId"]?.toString() ?: return null
            val code = payload["code"]?.toString() ?: return null
            val sideName = payload["side"]?.toString() ?: return null
            val side = OrderSide.values().firstOrNull { it.name == sideName } ?: return null
            // ADR 0067 publisher가 priceType·quantity·limitPrice 미발행 — T-O 진입 시 default 채움.
            val priceTypeName = payload["priceType"]?.toString()
            val priceType =
                priceTypeName?.let { name -> OrderPriceType.values().firstOrNull { it.name == name } }
                    ?: OrderPriceType.MARKET
            val quantity = (payload["quantity"] as? Number)?.toInt() ?: 1
            val limitPrice =
                when (val raw = payload["limitPrice"]) {
                    is Number -> BigDecimal.valueOf(raw.toDouble())
                    is String -> raw.toBigDecimalOrNull()
                    else -> null
                }
            val idempotencyKey =
                payload["idempotencyKey"]?.toString()
                    ?: "$signalId:$userId"
            val emittedAtSec =
                when (val raw = payload["emittedAt"]) {
                    is Number -> raw.toLong()
                    is String -> raw.toLongOrNull() ?: Instant.now().epochSecond
                    else -> Instant.now().epochSecond
                }
            OrderIntent(
                id = signalId,
                userId = userId,
                signalId = signalId,
                strategyId = strategyId,
                code = code,
                side = side,
                priceType = priceType,
                quantity = quantity,
                limitPrice = limitPrice,
                idempotencyKey = idempotencyKey,
                requestedAt = Instant.ofEpochSecond(emittedAtSec),
            )
        } catch (e: Exception) {
            log.error("OrderRouterKafkaListener.deserialize 실패: err={}", e.message)
            null
        }
    }
}
