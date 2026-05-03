package com.mj.api.infrastructure.persistence.screener

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "screen_run")
class ScreenRunJpa(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "run_id", length = 36, nullable = false, unique = true)
    var runId: String,
    @Column(name = "strategy_yaml", nullable = false, columnDefinition = "TEXT")
    var strategyYaml: String,
    @Column(name = "universe_type", length = 32, nullable = false)
    var universeType: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "universe_detail", columnDefinition = "jsonb")
    var universeDetail: String?,
    @Column(name = "as_of_date", nullable = false)
    var asOfDate: LocalDate,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    var resultJson: String?,
    @Column(name = "total_scanned", nullable = false)
    var totalScanned: Int,
    @Column(name = "match_count", nullable = false)
    var matchCount: Int,
    @Column(name = "skip_count", nullable = false)
    var skipCount: Int,
    @Column(name = "status", length = 16, nullable = false)
    var status: String,
    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String?,
    @Column(name = "user_id")
    var userId: Long?,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
    @Column(name = "finished_at")
    var finishedAt: Instant?,
)
