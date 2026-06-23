package kr.spdaewoon.magicjar.consumer.persistence

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
 * strategy_position JPA 엔티티 (consumer 모듈 — ADR 0110 V64, 분할매수 R2-2).
 *
 * 기존 [PositionJpaEntity](user+code 합산, KIS 권위) 아래의 전략별 lot ledger.
 * UQ(user_id, strategy_id, code) — 체결 listener가 find-then-save로 upsert(같은 transaction boundary).
 *
 * `executed_exit_stages` 는 JSONB 정수 배열 — [ConsumerStrategyJpaEntity] codes/intervals 패턴 정합
 * (`@JdbcTypeCode(SqlTypes.JSON)` + String). 어댑터가 Set<Int> ↔ JSON String 매핑.
 */
@Entity
@Table(name = "strategy_position")
class StrategyPositionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", length = 20, nullable = false)
    val userId: String,
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "quantity", nullable = false)
    val quantity: Int = 0,
    @Column(name = "avg_price", precision = 19, scale = 4, nullable = false)
    val avgPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "filled_lots", nullable = false)
    val filledLots: Int = 0,
    @Column(name = "realized_pnl", precision = 19, scale = 4, nullable = false)
    val realizedPnl: BigDecimal = BigDecimal.ZERO,
    /** 발화·체결된 PartialExit 단계 인덱스 — JSONB 정수 배열 String(`[]` default). 어댑터가 Set<Int> 매핑. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "executed_exit_stages", columnDefinition = "jsonb", nullable = false)
    val executedExitStages: String = "[]",
    /**
     * 분할매수 base 고정값(원) — 1차 진입 시점 buyable 박제(ADR 0128 ①, V90). NULL=미진입/분할미사용.
     * 체결 listener([ConsumerExecutionStrategyPositionAdapter])가 1차 체결(filledLots 0→1) 시 set, 전량 청산 시 reset.
     */
    @Column(name = "scale_in_base_budget", precision = 19, scale = 4)
    val scaleInBaseBudget: BigDecimal? = null,
    /**
     * 부분 매수 잔여 수량(주) — 자금 부족으로 회차 일부만 체결됐을 때 못 산 잔여(ADR 0128 ②, V90). 0=잔여 없음.
     * 다음 회차에서 [RiskGatewayEvaluator.resolveBuyQuantity]가 마저 채우고, 전량 청산 시 reset.
     */
    @Column(name = "scale_in_remainder_qty", nullable = false)
    val scaleInRemainderQty: Int = 0,
    /**
     * 거래 계획(Planned Entry) JSONB — ADR 0130 L2 V95. owner=AUTOPILOT 진입 시점 박제(thesis·TP·SL·trailing·
     * hold·exitConditions·contingency·status·exitReason). NULL=미기록(USER/SYSTEM·게이트 off=현행). 어댑터가
     * [TradePlanSerde] + ObjectMapper 로 [TradePlan] ↔ JSON String 매핑(executed_exit_stages 와 동일 JSONB String 패턴).
     * 전량 청산(quantity=0) 시 reset(R2 listener).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trade_plan", columnDefinition = "jsonb")
    val tradePlan: String? = null,
    @Column(name = "opened_at", nullable = false)
    val openedAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(userId = "", strategyId = 0, code = "")
}
