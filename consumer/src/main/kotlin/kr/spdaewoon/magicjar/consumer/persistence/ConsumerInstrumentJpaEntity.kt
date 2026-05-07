package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * instrument JPA 엔티티 (consumer 모듈 — V3 stub 허용).
 * Bootstrap 시 KIS 잔고 종목에 대해 stub INSERT.
 */
@Entity
@Table(name = "instrument")
class ConsumerInstrumentJpaEntity(
    @Id
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "name", length = 80, nullable = false)
    val name: String,
    @Column(name = "market", length = 10, nullable = false)
    val market: String = "UNKNOWN",
    @Column(name = "type", length = 20, nullable = false)
    val type: String = "UNKNOWN",
    @Column(name = "active", nullable = false)
    val active: Boolean = true,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(code = "", name = "")
}
