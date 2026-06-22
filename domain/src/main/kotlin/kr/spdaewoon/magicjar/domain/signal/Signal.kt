package kr.spdaewoon.magicjar.domain.signal

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
import java.math.BigDecimal

/**
 * 시그널 도메인 (ADR 0066 §5-2 채택 + ADR 0023 §2-1 baseline 확장).
 *
 * BoundaryClosingKafkaListener가 수신한 `market.candle.{interval}.closed.v1` payload를
 * RealtimeSignalGenerator가 평가하여 발행하는 BUY/SELL/HOLD 시그널.
 *
 * ### 멱등성 (ADR 0066 §2-5 E-1)
 * `(strategyId, code, interval, ts)` UQ — Phase 2 backend-core가 DB constraint 정의.
 * Phase 1 SignalDedupeGuard가 Redis SETNX 30s 보조.
 *
 * ### Pure Kotlin
 * Spring/JPA 의존 0. kotlinx.datetime.Instant 사용.
 *
 * @param id DB auto-generated (Phase 2 영속)
 * @param strategyId 시그널 발행 전략 ID
 * @param userId 정합 cache (signal table denormalized — 페이징 조회 효율)
 * @param code 종목 코드 (KIS 6자리)
 * @param interval 평가 인터벌 (1m·3m·5m·15m·30m·90m·1h·4h·12h 등)
 * @param ts boundary closing ts (deterministic — ADR 0064 §4-2 정합)
 * @param side BUY · SELL · HOLD ([SignalSide])
 * @param strength 0~100 — 룰 매칭 강도 (ADR 0023 §2-1 baseline)
 * @param reason 룰 매칭 사유 (예: "MA(5) > MA(20) at 09:05")
 * @param priceAtSignal 평가 시점 종가 (boundary close raw)
 * @param contextJson 평가 context JSON (indicator values · pattern matched · …)
 * @param createdAt 영속 시각
 * @param strategyOwner 발행 전략의 [StrategyOwner] — **신호가 owner 를 직접 운반**한다(2026-06-22 P0 견고 fix).
 *   signal.generated.v1 consumer([kr.spdaewoon.magicjar.consumer.risk.SignalGeneratedKafkaListener])의 autopilot
 *   Guard 가 owner=[StrategyOwner.AUTOPILOT] BUY 를 식별할 때, 과거엔 listener 가 strategy 를 **재조회(lookup)** 해
 *   owner 를 알아냈다. 그 lookup 은 신규 BUY(보유 0·position-driven 폴백 미반환)에서 null → owner 미상 → 회로차단기
 *   우회의 root 였다. 신호 생성기가 발행 시점에 보유 중인 strategy.owner 를 동봉하면 Guard 가 lookup 없이 즉시 식별한다
 *   (추가 조회 0). **nullable = 하위호환** — owner 필드 없는 legacy payload 역직렬화는 null(=USER 취급, zero-regression).
 *   DB signal 테이블에는 별도 컬럼 없음(transient — 영속 round-trip 시 null 이 되므로 생성기가 publish 직전 재부착).
 *
 * spec 인용: ADR 0066 §5-2.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
data class Signal(
    val id: Long? = null,
    val strategyId: Long,
    val userId: String,
    val code: String,
    val interval: CandleInterval,
    val ts: Instant,
    val side: SignalSide,
    val strength: Int,
    val reason: String,
    val priceAtSignal: BigDecimal,
    val contextJson: String? = null,
    val createdAt: Instant? = null,
    val strategyOwner: StrategyOwner? = null,
)
