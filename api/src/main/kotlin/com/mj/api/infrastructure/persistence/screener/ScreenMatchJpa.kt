package com.mj.api.infrastructure.persistence.screener

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "screen_match")
class ScreenMatchJpa(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "screen_run_id", nullable = false)
    var screenRun: ScreenRunJpa,
    @Column(name = "code", length = 12, nullable = false)
    var code: String,
    @Column(name = "name", length = 64, nullable = false)
    var name: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entry_reason", columnDefinition = "jsonb", nullable = false)
    var entryReason: String,
    @Column(name = "matched_at", nullable = false)
    var matchedAt: Instant,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
