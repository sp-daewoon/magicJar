package com.mj.consumer.infrastructure.persistence.guard

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Embeddable
data class DailyOrderCounterId(
    @Column(name = "counter_date")
    val counterDate: LocalDate,
    @Column(name = "user_id")
    val userId: UUID,
    @Column(name = "account_no", length = 20)
    val accountNo: String,
) : Serializable

@Entity
@Table(name = "daily_order_counter")
class DailyOrderCounterEntity(
    @EmbeddedId
    val id: DailyOrderCounterId,
    @Column(name = "count", nullable = false)
    val count: Int = 0,
    @Column(name = "total_value_krw", nullable = false)
    val totalValueKrw: BigDecimal = BigDecimal.ZERO,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface DailyOrderCounterJpaRepository : JpaRepository<DailyOrderCounterEntity, DailyOrderCounterId> {
    @Modifying
    @Query(
        value = """
            INSERT INTO daily_order_counter (counter_date, user_id, account_no, count, total_value_krw, updated_at)
            VALUES (:date, :userId, :accountNo, 1, :value, NOW())
            ON CONFLICT (counter_date, user_id, account_no)
            DO UPDATE SET count = daily_order_counter.count + 1,
                          total_value_krw = daily_order_counter.total_value_krw + EXCLUDED.total_value_krw,
                          updated_at = NOW()
        """,
        nativeQuery = true,
    )
    fun incrementCounter(
        @Param("date") date: LocalDate,
        @Param("userId") userId: UUID,
        @Param("accountNo") accountNo: String,
        @Param("value") value: BigDecimal,
    ): Int
}

/**
 * DailyOrderLimitGuard에서 사용. 카운터 증가는 atomic UPSERT.
 * Guard 통과(증가) 후 KIS 호출 실패 시 rollback은 호출자 트랜잭션에 위임.
 */
@Component
@Transactional
class DailyOrderCounterJpaAdapter(
    private val repo: DailyOrderCounterJpaRepository,
) {
    fun current(
        date: LocalDate,
        userId: UUID,
        accountNo: String,
    ): DailyOrderCounterEntity? = repo.findById(DailyOrderCounterId(date, userId, accountNo)).orElse(null)

    fun increment(
        date: LocalDate,
        userId: UUID,
        accountNo: String,
        value: BigDecimal,
    ) {
        repo.incrementCounter(date, userId, accountNo, value)
    }
}
