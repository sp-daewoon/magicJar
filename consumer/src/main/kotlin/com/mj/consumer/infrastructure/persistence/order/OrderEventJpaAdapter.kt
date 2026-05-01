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
    @Column(name = "kis_order_no", nullable = false, length = 20)
    val kisOrderNo: String,
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
            eventId,
            orderId,
            userId,
            accountNo,
            kisMode,
            kisOrderNo,
            eventType,
            cumulativeQty,
            partialQty,
            execPrice,
            source,
            payload,
            occurredAt,
            ingestedAt,
        )

    companion object {
        fun from(e: OrderEventRecord): OrderEventEntity =
            OrderEventEntity(
                e.eventId,
                e.orderId,
                e.userId,
                e.accountNo,
                e.kisMode,
                e.kisOrderNo,
                e.eventType,
                e.cumulativeQty,
                e.partialQty,
                e.execPrice,
                e.source,
                e.payload,
                e.occurredAt,
                e.ingestedAt,
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
     * INSERT 시도. UNIQUE(kis_mode, kis_order_no, event_type, cumulative_qty) 충돌 시 false (멱등).
     *
     * REQUIRES_NEW로 nested transaction을 만들어 충돌 발생 시 호출자 트랜잭션에 영향 없게 한다.
     * (DataIntegrityViolation은 트랜잭션 자체를 rollback-only로 만들기 때문)
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
