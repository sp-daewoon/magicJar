package kr.spdaewoon.magicjar.api.persistence.strategy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant

/**
 * strategy_position **read-only** JPA 엔티티 (api 모듈 — ADR 0110 V64, 분할매수 R3-a).
 *
 * 쓰기(upsert)는 consumer 모듈 [StrategyPositionJpaEntity] 책임(체결 listener). api 모듈은 전략별 포지션
 * 표시(GET /api/strategies/{id}/position)를 위한 조회만 한다 — 별도 엔티티 클래스로 매핑(모듈 분리·테이블 공유).
 *
 * ADR 0051 주의: kotlin-jpa no-arg constructor 필요(api 모듈 적용). 본 PR add-only read path.
 */
@Entity
@Table(name = "strategy_position")
class StrategyPositionReadJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", length = 20, nullable = false)
    val userId: String = "",
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long = 0,
    @Column(name = "code", length = 8, nullable = false)
    val code: String = "",
    @Column(name = "quantity", nullable = false)
    val quantity: Int = 0,
    @Column(name = "avg_price", precision = 19, scale = 4, nullable = false)
    val avgPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "filled_lots", nullable = false)
    val filledLots: Int = 0,
    @Column(name = "realized_pnl", precision = 19, scale = 4, nullable = false)
    val realizedPnl: BigDecimal = BigDecimal.ZERO,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "executed_exit_stages", columnDefinition = "jsonb", nullable = false)
    val executedExitStages: String = "[]",
    /** 분할매수 base 고정값(원) — 1차 시점 buyable 박제(ADR 0128 ①, V90). NULL=미진입/분할미사용. 쓰기는 consumer 책임. */
    @Column(name = "scale_in_base_budget", precision = 19, scale = 4)
    val scaleInBaseBudget: BigDecimal? = null,
    /** 부분 매수 잔여 수량(주) — 자금 부족으로 회차 일부만 체결된 미체결 잔여(ADR 0128 ②, V90). 0=잔여 없음. */
    @Column(name = "scale_in_remainder_qty", nullable = false)
    val scaleInRemainderQty: Int = 0,
    /**
     * 거래 계획(Planned Entry) JSONB String — ADR 0130 L2 V95. owner=AUTOPILOT 진입 박제. NULL=미기록(현행).
     * api 는 read-only(R3 UI "예상 계획" 패널 표시) — 쓰기는 consumer 책임. JSON String 그대로 보관(R3 에서 파싱).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trade_plan", columnDefinition = "jsonb")
    val tradePlan: String? = null,
    @Column(name = "opened_at", nullable = false)
    val openedAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
