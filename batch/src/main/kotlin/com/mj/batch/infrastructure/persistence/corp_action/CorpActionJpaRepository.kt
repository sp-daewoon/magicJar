package com.mj.batch.infrastructure.persistence.corp_action

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

interface CorpActionJpaRepository : JpaRepository<CorpActionJpa, Long> {
    fun findByInstrumentCodeAndExDateBetweenOrderByExDateAsc(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpActionJpa>

    fun findByInstrumentCodeAndExDateLessThanEqualOrderByExDateAsc(
        instrumentCode: String,
        exDate: LocalDate,
    ): List<CorpActionJpa>

    fun findByInstrumentCodeAndTypeAndExDate(
        instrumentCode: String,
        type: String,
        exDate: LocalDate,
    ): CorpActionJpa?

    /** 미보정 Corp Action 목록 (applied_at IS NULL) */
    fun findByAppliedAtIsNull(): List<CorpActionJpa>

    @Modifying
    @Query(
        """
        UPDATE CorpActionJpa c
        SET c.appliedAt = :appliedAt, c.updatedAt = :now
        WHERE c.instrumentCode = :instrumentCode
    """,
    )
    fun markApplied(
        @Param("instrumentCode") instrumentCode: String,
        @Param("appliedAt") appliedAt: Instant,
        @Param("now") now: Instant = Instant.now(),
    ): Int
}
