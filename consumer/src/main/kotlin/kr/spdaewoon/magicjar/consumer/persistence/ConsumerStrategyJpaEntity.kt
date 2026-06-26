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
 * strategy JPA entity copy for the consumer JVM.
 *
 * consumer는 api 모듈에 의존하지 않으므로 실시간 시그널 hot path에서 strategy 테이블을 직접 읽는다.
 */
@Entity
@Table(name = "strategy")
class ConsumerStrategyJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    // V88 — user_id nullable 전환(SYSTEM 시드 프리셋은 null). consumer는 owner='USER' 행만 조회하므로 실 조회 row는 항상 non-null.
    @Column(name = "user_id")
    val userId: Long? = null,
    @Column(name = "name", nullable = false, length = 200)
    val name: String,
    @Column(name = "name_ko", length = 200)
    val nameKo: String?,
    @Column(name = "dsl_entry", nullable = false, columnDefinition = "TEXT")
    val dslEntry: String,
    @Column(name = "dsl_exit", columnDefinition = "TEXT")
    val dslExit: String?,
    @Column(name = "universe_market", length = 20, nullable = false)
    val universeMarket: String = "KOSPI",
    @Column(name = "tf", length = 10, nullable = false)
    val tf: String = "D1",
    @Column(name = "fixed_stop_loss_pct", precision = 9, scale = 4)
    val fixedStopLossPct: BigDecimal? = null,
    @Column(name = "take_profit_pct", precision = 9, scale = 4)
    val takeProfitPct: BigDecimal? = null,
    // V96 추가 컬럼 — ADR 0135 trailing stop 영속. consumer hot path 시그널 평가가 trailing 을 적재해야
    //   TradePlanFactory 가 TradePlanTrailing 을 박제하고 TRAIL 청산이 작동한다. ★ 이 매핑이 빠지면 신호평가 시
    //   trailing 이 null 로 덮여 1분봉 scalping break-even 레버가 영구 dead(scale_in_config drift 와 동형 사고). null=비활성.
    @Column(name = "trailing_stop_pct", precision = 9, scale = 4)
    val trailingStopPct: BigDecimal? = null,
    // V73 추가 컬럼 — 잔익절 방어 B안. 진입 후 N캔들 동안 DSL 매도 무시(손절·익절 무관). null=비활성(현행).
    @Column(name = "min_hold_bars")
    val minHoldBars: Int? = null,
    // V94 추가 컬럼 — ADR 0129 레버 1 오버나잇 토글. default true = 현행 거동 보존(zero-regression).
    //   false + owner=AUTOPILOT 이면 EOD 청산 워커(15:15 KST)·late-entry 게이트(14:30 KST) 대상.
    //   ★ ADR 0128 scale_in_config 매핑 누락 drift 교훈 — 컬럼 매핑 LOCK(도메인까지 전달돼야 게이트가 작동).
    @Column(name = "allow_overnight", nullable = false)
    val allowOvernight: Boolean = true,
    // V82 추가 컬럼 — VI 발동 행동 정책. 기본 ALERT_ONLY(기존 전략 주문 0).
    @Column(name = "vi_action_policy", nullable = false, length = 24)
    val viActionPolicy: String = "ALERT_ONLY",
    // V72 추가 컬럼 — ADR 0114 진입(BUY) 집행 정책 (raw JSON. BUY 가격 산정 입력 — EntryPolicySerde round-trip).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entry_policy", columnDefinition = "jsonb")
    val entryPolicy: String? = null,
    // V65 추가 컬럼 — ADR 0111 분할매수(scale_in_config)·분할익절(partial_exits) JSONB(raw String 보관).
    //   2026-06-18 P0 재수정 — consumer hot path 시그널 평가가 scaleIn을 적재하도록 매핑 누락 해소.
    //   api StrategyJpaEntity는 이미 보유했으나 consumer 복제 엔티티에서만 빠져 있어 resolveBuyQuantity 분할 분기가
    //   항상 scaleIn=null로 전량 매수했다(라이브 실증: 전략 47 첫회 40% 미적용·전량 20주).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scale_in_config", columnDefinition = "jsonb")
    val scaleInConfig: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "partial_exits", columnDefinition = "jsonb")
    val partialExits: String? = null,
    @Column(name = "ast_depth", nullable = false)
    val astDepth: Int = 0,
    @Column(name = "node_count", nullable = false)
    val nodeCount: Int = 0,
    @Column(name = "indicator_call_count", nullable = false)
    val indicatorCallCount: Int = 0,
    @Column(name = "estimated_eval_time_ms", nullable = false)
    val estimatedEvalTimeMs: Int = 0,
    @Column(name = "complexity_class", length = 20, nullable = false)
    val complexityClass: String = "LOW",
    @Column(name = "disabled_reason", length = 32)
    val disabledReason: String? = null,
    @Column(name = "last_disabled_at")
    val lastDisabledAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eval_metric_summary", columnDefinition = "jsonb")
    val evalMetricSummary: String? = null,
    @Column(name = "status", nullable = false, length = 16)
    val status: String = "ACTIVE",
    // V88 owner(USER/SYSTEM/AUTOPILOT) — **autopilot Guard 식별 단일 출처**. 2026-06-22 P0 root fix:
    //   이 컬럼 매핑이 통째로 누락돼 toDomain owner 가 항상 default USER 로 떨어져, owner=AUTOPILOT 전략의
    //   신호가 USER 로 운반되고 AutopilotBuyGuard 가 영구 우회됐다(라이브 풀 한도 14배 실매수의 진짜 근본).
    //   native query 는 owner 로 필터하나 엔티티가 컬럼을 안 읽어 도메인까지 전달 안 됐던 것. null/legacy=USER.
    @Column(name = "owner", nullable = false, length = 16)
    val owner: String = "USER",
    // V81 추가 컬럼 — ADR 0122 §2-A 전략 출처(USER_DSL/PAPER_DISCOVERY). consumer capital 분기 판정 키.
    // null=legacy=USER_DSL 취급 → 실 KIS 잔고 경로(현행 zero-regression).
    @Column(name = "origin", length = 24)
    val origin: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "codes", columnDefinition = "jsonb")
    val codes: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "intervals", columnDefinition = "jsonb")
    val intervals: String? = null,
    @Column(name = "paused_at")
    val pausedAt: Instant? = null,
    @Column(name = "disabled_at")
    val disabledAt: Instant? = null,
    @Column(name = "deleted_at")
    val deletedAt: Instant? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        userId = 0L,
        name = "",
        nameKo = null,
        dslEntry = "",
        dslExit = null,
    )
}
