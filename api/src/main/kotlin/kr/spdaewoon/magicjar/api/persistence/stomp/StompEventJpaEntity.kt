package kr.spdaewoon.magicjar.api.persistence.stomp

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * stomp_event JPA 엔티티 — Flyway V41 정합 (ADR 0084 P1-2).
 *
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인(api 모듈 적용) 의존.
 * 헌법 §6: body 가진 fun 0건.
 *
 * 컬럼 매핑:
 * - id BIGSERIAL PK
 * - user_id BIGINT NOT NULL
 * - channel VARCHAR(64) NOT NULL
 * - event_type VARCHAR(64) NOT NULL
 * - payload_json JSONB NOT NULL
 * - created_at TIMESTAMPTZ NOT NULL DEFAULT now()
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Entity
@Table(name = "stomp_event")
class StompEventJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "channel", nullable = false, length = 64)
    val channel: String,
    @Column(name = "event_type", nullable = false, length = 64)
    val eventType: String,
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    val payloadJson: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        userId = 0L,
        channel = "",
        eventType = "",
        payloadJson = "{}",
    )
}
