package com.mj.batch.infrastructure.persistence.corp_action

import com.mj.domain.market.corp_action.CorpAction
import com.mj.domain.market.corp_action.port.CorpActionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * CorpActionRepository 포트 구현체 (batch 모듈).
 *
 * PostgreSQL corp_action 테이블에 멱등 upsert.
 * instrument_code + type + ex_date UNIQUE → 기존 row update, 없으면 insert.
 */
@Component
@Transactional
class CorpActionRepositoryAdapter(
    private val corpActionJpaRepository: CorpActionJpaRepository,
) : CorpActionRepository {
    private val log = LoggerFactory.getLogger(CorpActionRepositoryAdapter::class.java)

    override fun upsert(corpAction: CorpAction) {
        val jpa = corpAction.toJpa()
        val existing =
            corpActionJpaRepository.findByInstrumentCodeAndTypeAndExDate(
                jpa.instrumentCode,
                jpa.type,
                jpa.exDate,
            )
        if (existing != null) {
            existing.payload = jpa.payload
            existing.source = jpa.source
            existing.updatedAt = java.time.Instant.now()
            corpActionJpaRepository.save(existing)
        } else {
            corpActionJpaRepository.save(jpa)
        }
    }

    override fun upsertAll(corpActions: List<CorpAction>) {
        log.debug("[corp-action-repo] upsertAll count={}", corpActions.size)
        corpActions.forEach { upsert(it) }
    }

    @Transactional(readOnly = true)
    override fun findByInstrumentCodeAndExDateBetween(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> =
        corpActionJpaRepository
            .findByInstrumentCodeAndExDateBetweenOrderByExDateAsc(instrumentCode, from, to)
            .map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun findByInstrumentCodeAndExDateLessThanEqual(
        instrumentCode: String,
        targetDate: LocalDate,
    ): List<CorpAction> =
        corpActionJpaRepository
            .findByInstrumentCodeAndExDateLessThanEqualOrderByExDateAsc(instrumentCode, targetDate)
            .map { it.toDomain() }
}
