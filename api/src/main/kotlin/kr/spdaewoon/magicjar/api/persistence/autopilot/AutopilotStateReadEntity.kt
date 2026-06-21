package kr.spdaewoon.magicjar.api.persistence.autopilot

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

/**
 * `autopilot_state` **read-only projection** (api 모듈 · spec 2026-06-22 §2-0-A·§2-0-B · Phase 0 Task 4).
 *
 * ## 왜 api 자체 read-only 엔티티인가 (drift 회피 결정)
 * 쓰기 권위 엔티티는 consumer [kr.spdaewoon.magicjar.consumer.autopilot.AutopilotStateJpaEntity] 이다(autopilot_state
 * 의 모든 mutation = kill·손실누적·deactivate 는 consumer 가 단독 책임). api 는 `/api/autopilot/status` 표시를 위해
 * **읽기만** 필요하다. consumer 엔티티를 persistence-shared 로 추출하는 방안은 모듈 의존·entity scan·매핑 책임
 * 이동 비용이 있어(저위험 아님), 본 Task 는 **api 모듈 read-only 엔티티**로 V91/V92 스키마를 그대로 미러링한다.
 *
 * ## drift 회피 (★ 의무 인지)
 * 본 엔티티 컬럼은 V91/V92 와 **반드시 동일**해야 한다(`autopilot_state` 단일 테이블 1:1). consumer 쓰기 엔티티에
 * 컬럼이 추가되면 V93+ 마이그레이션과 함께 본 엔티티도 동기화한다. api 는 어떤 쓰기도 하지 않으므로(read-only
 * 컨트롤러는 select 만) 두 엔티티가 같은 행을 가리켜도 충돌 0 — 단 **표시 값 drift 만 주의**.
 *
 * 헌법 §6 — 본 엔티티는 데이터 holder. 쿼리는 [AutopilotStateReadRepository] abstract fun 만(body 0).
 */
@Entity
@Table(name = "autopilot_state")
class AutopilotStateReadEntity(
    @Id
    @Column(name = "id")
    val id: Long = 1L,
    @Column(name = "kill_active", nullable = false)
    val killActive: Boolean = false,
    @Column(name = "pool_pct", precision = 6, scale = 3, nullable = false)
    val poolPct: BigDecimal = BigDecimal.ZERO,
    @Column(name = "per_trade_pct", precision = 6, scale = 3, nullable = false)
    val perTradePct: BigDecimal = BigDecimal.ZERO,
    @Column(name = "max_positions", nullable = false)
    val maxPositions: Int = 0,
    @Column(name = "daily_loss_pct", precision = 6, scale = 3, nullable = false)
    val dailyLossPct: BigDecimal = BigDecimal.ZERO,
    @Column(name = "cum_loss_pct", precision = 6, scale = 3, nullable = false)
    val cumLossPct: BigDecimal = BigDecimal.ZERO,
    @Column(name = "cum_realized_loss", precision = 20, scale = 4, nullable = false)
    val cumRealizedLoss: BigDecimal = BigDecimal.ZERO,
    @Column(name = "daily_realized_loss", precision = 20, scale = 4, nullable = false)
    val dailyRealizedLoss: BigDecimal = BigDecimal.ZERO,
    @Column(name = "lifetime_realized_loss", precision = 20, scale = 4, nullable = false)
    val lifetimeRealizedLoss: BigDecimal = BigDecimal.ZERO,
) {
    protected constructor() : this(id = 1L)
}
