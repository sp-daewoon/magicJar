package kr.spdaewoon.magicjar.api.persistence.signal

import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.signal.port.SignalRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant as JInstant

/**
 * 시그널 JPA 어댑터 — SignalRepository port 구현 (ADR 0066 §5-3 + §2-5 E-1).
 *
 * 헌법 §6 (ADR 0051) 준수: 헬퍼 메서드는 interface 0건, 이 클래스 private fun 위치.
 *
 * ### 멱등성 처리 (E-1 DUPLICATE_KEY skip)
 * DB UQ `(strategy_id, code, interval, ts)` 위반 시
 * [DataIntegrityViolationException] catch → log.debug + 기존 엔티티 재조회 반환.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Component
class SignalJpaAdapter(
    private val signalJpaRepository: SignalJpaRepository,
) : SignalRepository {
    private val log = LoggerFactory.getLogger(SignalJpaAdapter::class.java)

    @Transactional
    override fun save(signal: Signal): Signal =
        try {
            val entity = toEntity(signal)
            val saved = signalJpaRepository.save(entity)
            toDomain(saved)
        } catch (e: DataIntegrityViolationException) {
            log.debug(
                "dup signal skipped: strategyId={} code={} interval={} ts={}",
                signal.strategyId,
                signal.code,
                signal.interval.code,
                signal.ts,
            )
            val existing = findExistingEntity(signal)
            existing?.let { toDomain(it) } ?: signal
        }

    @Transactional(readOnly = true)
    override fun findByDedupeKey(
        strategyId: Long,
        code: String,
        interval: CandleInterval,
        ts: kotlinx.datetime.Instant,
    ): Signal? {
        val jInstant = ts.toJavaInstant()
        return signalJpaRepository
            .findByStrategyIdAndCodeAndIntervalAndTs(strategyId, code, interval.code, jInstant)
            ?.let { toDomain(it) }
    }

    /**
     * ID로 단건 조회.
     * SignalQueryService에서 직접 호출.
     */
    @Transactional(readOnly = true)
    fun findById(id: Long): Signal? = signalJpaRepository.findById(id).map { toDomain(it) }.orElse(null)

    /**
     * 사용자 시계열 시그널 페이징 조회.
     * SignalQueryService에서 직접 호출 — port 외 추가 메서드.
     */
    @Transactional(readOnly = true)
    fun findByUserAndRange(
        userId: String,
        from: JInstant,
        to: JInstant,
        page: Int,
        size: Int,
    ): org.springframework.data.domain.Page<Signal> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "ts"))
        return signalJpaRepository
            .findByUserIdAndTsBetween(userId, from, to, pageable)
            .map { toDomain(it) }
    }

    // --- private helper ---

    private fun findExistingEntity(signal: Signal): SignalJpaEntity? =
        signalJpaRepository.findByStrategyIdAndCodeAndIntervalAndTs(
            signal.strategyId,
            signal.code,
            signal.interval.code,
            signal.ts.toJavaInstant(),
        )

    private fun toEntity(signal: Signal): SignalJpaEntity =
        SignalJpaEntity(
            id = signal.id,
            strategyId = signal.strategyId,
            userId = signal.userId,
            code = signal.code,
            interval = signal.interval.code,
            ts = signal.ts.toJavaInstant(),
            side = signal.side.name,
            strength = signal.strength,
            reason = signal.reason.takeIf { it.isNotBlank() },
            price = signal.priceAtSignal,
            contextJson = signal.contextJson,
        )

    private fun toDomain(entity: SignalJpaEntity): Signal =
        Signal(
            id = entity.id,
            strategyId = entity.strategyId,
            userId = entity.userId,
            code = entity.code,
            interval = safeInterval(entity.interval),
            ts = entity.ts.toKotlinInstant(),
            side = safeSide(entity.side),
            strength = entity.strength,
            reason = entity.reason ?: "",
            priceAtSignal = entity.price ?: java.math.BigDecimal.ZERO,
            contextJson = entity.contextJson,
            createdAt = entity.createdAt.toKotlinInstant(),
        )

    private fun safeInterval(code: String): CandleInterval =
        runCatching { CandleInterval.fromCode(code) }.getOrDefault(CandleInterval.ONE_DAY)

    private fun safeSide(value: String): SignalSide = runCatching { SignalSide.valueOf(value) }.getOrDefault(SignalSide.HOLD)
}
