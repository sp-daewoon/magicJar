package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.controller.WatchlistAddRequest
import kr.spdaewoon.magicjar.api.controller.WatchlistEntryDto
import kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaEntity
import kr.spdaewoon.magicjar.api.persistence.AuditTrailJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaEntity
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.WatchlistJpaEntity
import kr.spdaewoon.magicjar.api.persistence.WatchlistJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 관심종목 비즈니스 로직 (ADR 0028·0040, spec §4 정합).
 * - 옵션 C 채택: clientside 6자리 숫자 검증 + raw code 저장 + instrument stub upsert
 * - Kafka watchlist.changed.v1 producer (consumer listener는 T-W2)
 * - user_id: user_account 첫 row 자동 resolve (1차 single-tenant 단순화)
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class WatchlistService(
    private val watchlistJpaRepository: WatchlistJpaRepository,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val userAccountJpaRepository: UserAccountJpaRepository,
    private val auditTrailJpaRepository: AuditTrailJpaRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    private val log = LoggerFactory.getLogger(WatchlistService::class.java)

    companion object {
        private const val AUDIT_ACTOR = "user"
        private const val WATCHLIST_CHANGED_TOPIC = "watchlist.changed.v1"
    }

    /**
     * 1차 single-tenant: user_account 첫 row의 user_id 자동 resolve.
     * multi-user 본격화 시 HTTP header X-User-Id 또는 OAuth2 세션으로 전환.
     */
    fun currentUserId(): String =
        userAccountJpaRepository
            .findAll(Sort.by(Sort.Direction.ASC, "createdAt"))
            .firstOrNull()
            ?.userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "셋업 완료 후 사용 가능합니다")

    /**
     * 관심종목 목록 조회 (priority 오름차순, created_at 내림차순).
     */
    @Transactional(readOnly = true)
    fun list(userId: String): List<WatchlistEntryDto> =
        watchlistJpaRepository
            .findByUserIdOrderByPriorityAscCreatedAtDesc(userId)
            .map { it.toDto() }

    /**
     * 관심종목 등록.
     * 1) instrument stub upsert (market='UNKNOWN', type='UNKNOWN' — T-IM1 진입 시 갱신)
     * 2) watchlist INSERT (UQ violation → 409 WATCHLIST_DUPLICATE)
     * 3) audit row INSERT
     * 4) Kafka watchlist.changed.v1 publish
     */
    @Transactional
    fun add(
        userId: String,
        request: WatchlistAddRequest,
    ): WatchlistEntryDto {
        val code = request.code

        // instrument stub upsert (FK 위반 방지 — §3-2-A)
        upsertInstrumentStub(code)

        val entity =
            WatchlistJpaEntity(
                userId = userId,
                code = code,
                priority = request.priority ?: 0,
            )
        val saved =
            try {
                watchlistJpaRepository.save(entity)
            } catch (e: DataIntegrityViolationException) {
                // UQ(user_id, code) 위반 → 409 (함정 4번)
                throw ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 종목입니다")
            }

        auditTrailJpaRepository.save(
            AuditTrailJpaEntity(
                actor = AUDIT_ACTOR,
                action = "WATCHLIST_ADDED",
                entityType = "watchlist",
                entityId = code,
                detailJson = """{"userId":"$userId","code":"$code"}""",
            ),
        )

        publishWatchlistChanged(userId, code, "ADDED")

        log.info("Watchlist 등록: user={} code={}", maskUserId(userId), code)
        return saved.toDto()
    }

    /**
     * 관심종목 삭제.
     * 삭제 후 audit + Kafka publish (consumer T-W2 unsubscribe 신호).
     */
    @Transactional
    fun remove(
        userId: String,
        code: String,
    ) {
        val deleted = watchlistJpaRepository.deleteByUserIdAndCode(userId, code)
        if (deleted == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "등록된 종목을 찾을 수 없습니다")
        }

        auditTrailJpaRepository.save(
            AuditTrailJpaEntity(
                actor = AUDIT_ACTOR,
                action = "WATCHLIST_REMOVED",
                entityType = "watchlist",
                entityId = code,
                detailJson = """{"userId":"$userId","code":"$code"}""",
            ),
        )

        publishWatchlistChanged(userId, code, "REMOVED")

        log.info("Watchlist 삭제: user={} code={}", maskUserId(userId), code)
    }

    // ── private helpers ─────────────────────────────────────────────────────

    /**
     * instrument stub row upsert (옵션 C — market/type='UNKNOWN', T-IM1에서 갱신).
     */
    private fun upsertInstrumentStub(code: String) {
        if (!instrumentJpaRepository.existsByCode(code)) {
            val stub =
                InstrumentJpaEntity(
                    code = code,
                    name = code,
                    market = "UNKNOWN",
                    type = "UNKNOWN",
                )
            runCatching { instrumentJpaRepository.save(stub) }
                .onFailure { e ->
                    // 동시 INSERT race condition 허용 — 이미 존재하면 무시
                    log.debug("instrument stub upsert race — code={}: {}", code, e.message)
                }
        }
    }

    /**
     * watchlist.changed.v1 Kafka 발행 (ADR 0028 §4-5 — T-W2 listener는 후속).
     */
    private fun publishWatchlistChanged(
        userId: String,
        code: String,
        action: String,
    ) {
        val payload =
            """{"userId":"$userId","code":"$code","action":"$action","occurredAt":"${Instant.now()}"}"""
        runCatching {
            kafkaTemplate.send(WATCHLIST_CHANGED_TOPIC, userId, payload)
        }.onFailure { e ->
            // Kafka 실패는 비치명적 — Watchlist 등록/삭제 자체는 성공 처리
            log.warn("Kafka publish 실패 (watchlist.changed.v1) — code={} action={}: {}", code, action, e.message)
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    private fun WatchlistJpaEntity.toDto(): WatchlistEntryDto =
        WatchlistEntryDto(
            id = id ?: 0L,
            userId = userId,
            code = code,
            priority = priority,
            createdAt = createdAt,
        )
}
