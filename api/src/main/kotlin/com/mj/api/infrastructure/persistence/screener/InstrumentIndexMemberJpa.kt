package com.mj.api.infrastructure.persistence.screener

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * 지수 구성 종목 (KOSPI200, KOSDAQ150 등).
 *
 * removed_at null = 현재 구성 종목.
 */
@Entity
@Table(name = "instrument_index_member")
class InstrumentIndexMemberJpa(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "code", length = 12, nullable = false)
    var code: String,
    @Column(name = "index_group", length = 32, nullable = false)
    var indexGroup: String,
    @Column(name = "added_at", nullable = false)
    var addedAt: LocalDate = LocalDate.now(),
    @Column(name = "removed_at")
    var removedAt: LocalDate? = null,
)
