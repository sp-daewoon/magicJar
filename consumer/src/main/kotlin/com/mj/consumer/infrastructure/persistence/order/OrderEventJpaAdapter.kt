package com.mj.consumer.infrastructure.persistence.order

import com.mj.domain.order.OrderEventRecord
import com.mj.domain.order.OrderEventSource
import com.mj.domain.order.OrderEventType
import com.mj.domain.order.port.OrderEventRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * order_events 테이블 JPA 엔티티. V9·V11 Flyway 대응.
 *
 * V11 변경:
 * - kisOrderNo NULLABLE (REJECTED 이벤트는 KIS 미도달이므로 null)
 * - 기존 UNIQUE(kis_mode, kis_order_no, event_type, cumulative_qty) 제약 삭제 →
 *   V11 PARTIAL INDEX(kis_order_no IS NOT NULL)로 교체. NOT NULL인 PLACED 이벤트는
 *   여전히 중복 방지, REJECTED 이벤트는 여러 건 허용.
 */
@Entity
@Table(name = "order_events")
class OrderEventEntity(
    @Id
    @Column(name = "event_id")
    val eventId: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "account_no", nullable = false, length = 20)
    val accountNo: String,
    @Column(name = "kis_mode", nullable = false, length = 8)
    val kisMode: String,
    /** V11: NULLABLE — REJECTED 이벤트는 KIS 미도달. */
    @Column(name = "kis_order_no", length = 20)
    val kisOrderNo: String?,
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    val eventType: OrderEventType,
    @Column(name = "cumulative_qty")
    val cumulativeQty: Int? = null,
    @Column(name = "partial_qty")
    val partialQty: Int? = null,
    @Column(name = "exec_price")
    val execPrice: BigDecimal? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 8)
    val source: OrderEventSource? = null,
    @Column(name = "payload", columnDefinition = "JSONB")
    val payload: String? = null,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: OffsetDateTime,
    @Column(name = "ingested_at", nullable = false)
    val ingestedAt: OffsetDateTime,
) {
    fun toDomain(): OrderEventRecord =
        OrderEventRecord(
            eventId = eventId,
            orderId = orderId,
            userId = userId,
            accountNo = accountNo,
            kisMode = kisMode,
            kisOrderNo = kisOrderNo,
            eventType = eventType,
            cumulativeQty = cumulativeQty,
            partialQty = partialQty,
            execPrice = execPrice,
            source = source,
            payload = payload,
            occurredAt = occurredAt,
            ingestedAt = ingestedAt,
        )

    companion object {
        fun from(e: OrderEventRecord): OrderEventEntity =
            OrderEventEntity(
                eventId = e.eventId,
                orderId = e.orderId,
                userId = e.userId,
                accountNo = e.accountNo,
                kisMode = e.kisMode,
                kisOrderNo = e.kisOrderNo,
                eventType = e.eventType,
                cumulativeQty = e.cumulativeQty,
                partialQty = e.partialQty,
                execPrice = e.execPrice,
                source = e.source,
                payload = e.payload,
                occurredAt = e.occurredAt,
                ingestedAt = e.ingestedAt,
            )
    }
}

interface OrderEventJpaRepository : JpaRepository<OrderEventEntity, UUID> {
    fun findByOrderIdOrderByOccurredAtAsc(orderId: UUID): List<OrderEventEntity>
}

@Component
class OrderEventJpaAdapter(
    private val repo: OrderEventJpaRepository,
) : OrderEventRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * INSERT 시도. UNIQUE 충돌 시 false (멱등).
     *
     * V11 이후: PARTIAL INDEX(kis_order_no IS NOT NULL)로 PLACED 이벤트만 dedup.
     * REJECTED 이벤트(kisOrderNo=null)는 중복 허용 — REQUIRES_NEW에서 충돌 시 false.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun insertIdempotent(event: OrderEventRecord): Boolean =
        try {
            repo.saveAndFlush(OrderEventEntity.from(event))
            true
        } catch (e: DataIntegrityViolationException) {
            log.debug(
                "[ORDER_EVENT] dedup hit kis_mode={} kis_order_no={} event_type={} cum_qty={}",
                event.kisMode,
                event.kisOrderNo,
                event.eventType,
                event.cumulativeQty,
            )
            false
        }

    override fun findByOrder(orderId: UUID): List<OrderEventRecord> = repo.findByOrderIdOrderByOccurredAtAsc(orderId).map { it.toDomain() }
}
