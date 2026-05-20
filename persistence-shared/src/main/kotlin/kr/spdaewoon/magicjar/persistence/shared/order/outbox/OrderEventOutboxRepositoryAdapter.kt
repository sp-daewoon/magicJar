package kr.spdaewoon.magicjar.persistence.shared.order.outbox

import kr.spdaewoon.magicjar.domain.order.event.OrderEventOutbox
import kr.spdaewoon.magicjar.domain.order.event.OrderOutboxEventType
import kr.spdaewoon.magicjar.domain.order.event.OrderOutboxStatus
import kr.spdaewoon.magicjar.domain.order.event.port.OrderEventOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * domain [OrderEventOutboxRepository] port JPA 구현 어댑터 — Flyway V40 정합 (ADR 0084 P1-1).
 *
 * 헌법 §6 (ADR 0051): helper 메서드(toEntity·toDomain·safeStatus·safeEventType)는 본 클래스 private fun 위치.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0084 §1-2)
 */
@Component
class OrderEventOutboxRepositoryAdapter(
    private val jpaRepository: OrderEventOutboxJpaRepository,
) : OrderEventOutboxRepository {
    private val log = LoggerFactory.getLogger(OrderEventOutboxRepositoryAdapter::class.java)

    @Transactional
    override fun save(outbox: OrderEventOutbox): OrderEventOutbox {
        val entity = toEntity(outbox)
        val saved = jpaRepository.save(entity)
        log.debug(
            "order_event_outbox save: id={} eventId={} topic={} status={}",
            saved.id,
            saved.eventId,
            saved.topic,
            saved.status,
        )
        return toDomain(saved)
    }

    /**
     * PENDING outbox 조회 — `FOR UPDATE SKIP LOCKED` (다중 worker race condition 차단).
     *
     * 호출자(예: `OrderOutboxPublisherWorker.drainPending`)가 동일 트랜잭션 안에서
     * markPublished / recordAttemptFailure 까지 처리해야 락 보호 효과. 따라서 본 메서드는
     * write 트랜잭션(`@Transactional`)으로 격상 — 호출자 트랜잭션에 합류.
     *
     * (FIX C-2): 기존 readOnly 트랜잭션 + ORDER BY 만 사용한 path 는 다중 instance 시
     * 같은 row 중복 select → 중복 Kafka 발행 risk. native `FOR UPDATE SKIP LOCKED` 로 영구 차단.
     */
    @Transactional
    override fun findPending(limit: Int): List<OrderEventOutbox> =
        jpaRepository
            .findPendingForUpdate(limit)
            .map { toDomain(it) }

    @Transactional
    override fun markPublished(id: Long): OrderEventOutbox? {
        val entity = jpaRepository.findById(id).orElse(null) ?: return null
        entity.status = OrderOutboxStatus.PUBLISHED.name
        entity.publishedAt = Instant.now()
        entity.lastError = null
        val saved = jpaRepository.save(entity)
        return toDomain(saved)
    }

    @Transactional
    override fun recordAttemptFailure(
        id: Long,
        error: String,
    ): OrderEventOutbox? {
        val entity = jpaRepository.findById(id).orElse(null) ?: return null
        entity.attemptCount += 1
        entity.lastError = error.take(500) // TEXT column 과부하 방지
        val saved = jpaRepository.save(entity)
        return toDomain(saved)
    }

    @Transactional
    override fun markFailed(
        id: Long,
        error: String,
    ): OrderEventOutbox? {
        val entity = jpaRepository.findById(id).orElse(null) ?: return null
        entity.status = OrderOutboxStatus.FAILED.name
        entity.lastError = error.take(500)
        val saved = jpaRepository.save(entity)
        return toDomain(saved)
    }

    @Transactional(readOnly = true)
    override fun findByStatus(
        status: OrderOutboxStatus,
        limit: Int,
    ): List<OrderEventOutbox> {
        val pageable = PageRequest.of(0, limit)
        return jpaRepository
            .findByStatusOrderByCreatedAtAsc(status.name, pageable)
            .map { toDomain(it) }
    }

    // --- private helper ---

    private fun toEntity(outbox: OrderEventOutbox): OrderEventOutboxJpaEntity =
        OrderEventOutboxJpaEntity(
            id = outbox.id,
            eventId = outbox.eventId,
            eventType = outbox.eventType.name,
            topic = outbox.topic,
            partitionKey = outbox.partitionKey,
            payloadJson = outbox.payloadJson,
            status = outbox.status.name,
            createdAt = outbox.createdAt,
            publishedAt = outbox.publishedAt,
            attemptCount = outbox.attemptCount,
            lastError = outbox.lastError,
        )

    private fun toDomain(entity: OrderEventOutboxJpaEntity): OrderEventOutbox =
        OrderEventOutbox(
            id = entity.id,
            eventId = entity.eventId,
            eventType = safeEventType(entity.eventType),
            topic = entity.topic,
            partitionKey = entity.partitionKey,
            payloadJson = entity.payloadJson,
            status = safeStatus(entity.status),
            createdAt = entity.createdAt,
            publishedAt = entity.publishedAt,
            attemptCount = entity.attemptCount,
            lastError = entity.lastError,
        )

    private fun safeEventType(value: String): OrderOutboxEventType =
        runCatching { OrderOutboxEventType.valueOf(value) }.getOrDefault(OrderOutboxEventType.ORDER_INTENT)

    private fun safeStatus(value: String): OrderOutboxStatus =
        runCatching { OrderOutboxStatus.valueOf(value) }.getOrDefault(OrderOutboxStatus.PENDING)
}
