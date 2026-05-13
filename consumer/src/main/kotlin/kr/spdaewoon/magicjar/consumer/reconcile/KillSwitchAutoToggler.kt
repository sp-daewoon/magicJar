package kr.spdaewoon.magicjar.consumer.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.domain.reconcile.port.KillSwitchTogglePort
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileEventPublisher
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * `reconcile.drift.v1` Kafka listener — drift 검출 시 KillSwitch auto-ON 토글 (ADR 0043·0070 §2-11).
 *
 * ### 흐름 (ADR 0070 §2-11 자본 보호 step 5·6)
 * ```
 * 1. reconcile.drift.v1 listen (payload: userId, symbol, qtyDiff, ...)
 * 2. user별 idempotency 체크 — 같은 reconcile run에서 종목별 N건 publish되어도 KillSwitch는 1회만 engage
 * 3. KillSwitchTogglePort.engage(userId, reason="POSITION_DRIFT")
 *    — 어댑터 부재 시 KillSwitchJpaRepository.enableKillSwitch baseline reuse (graceful fallback)
 * 4. killswitch.engaged.v1 publish (triggeredBy=AUTO)
 * ```
 *
 * ### 다중 종목 drift 시 idempotency
 * 한 user의 reconcile 1회 실행에서 N개 종목 drift 검출 시 reconcile.drift.v1 N건 publish됨.
 * KillSwitch는 user당 1회만 engage — 5분 sliding window in-memory idempotency 캐시.
 * (Redis 분산락은 cron 자체에 적용 — 본 listener는 process-local 정합으로 충분)
 *
 * ### Phase 2 graceful fallback
 * - `KillSwitchTogglePort` 어댑터 부재 시 — `KillSwitchJpaRepository` baseline reuse (이미 존재).
 *   KillSwitchJpaRepository.enableKillSwitch는 ADR 0042 Bootstrap·ADR 0043 baseline에서 검증됨.
 *
 * AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class KillSwitchAutoToggler(
    private val eventPublisher: ReconcileEventPublisher,
    private val killSwitchTogglePortProvider: ObjectProvider<KillSwitchTogglePort>,
    private val killSwitchJpaRepository: KillSwitchJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(KillSwitchAutoToggler::class.java)

    companion object {
        const val TOPIC = "reconcile.drift.v1"
        const val GROUP = "magicjar-consumer-reconcile-killswitch"
        private const val REASON = "POSITION_DRIFT"
        private const val TRIGGERED_BY = "AUTO"
        private const val UPDATED_BY = "consumer"
        private const val IDEMPOTENCY_WINDOW_MS = 5 * 60 * 1000L // 5분
    }

    // user별 마지막 engage 시각 — process-local idempotency
    private val lastEngagedAt = ConcurrentHashMap<String, Long>()

    @KafkaListener(
        topics = [TOPIC],
        groupId = GROUP,
    )
    fun onDriftDetected(record: ConsumerRecord<String, String>) {
        try {
            val value = record.value() ?: return

            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
            val userId = payload["userId"]?.toString() ?: return
            val symbol = payload["symbol"]?.toString() ?: "?"

            // idempotency — 같은 user의 다중 종목 drift는 KillSwitch 1회만 engage
            val now = System.currentTimeMillis()
            val last = lastEngagedAt[userId]
            if (last != null && (now - last) < IDEMPOTENCY_WINDOW_MS) {
                log.debug(
                    "KillSwitch engage idempotency skip — 5분 내 이미 engaged: userId={} symbol={}",
                    userId,
                    symbol,
                )
                return
            }

            engage(userId)
            lastEngagedAt[userId] = now
            eventPublisher.publishKillSwitchEngaged(userId, REASON, TRIGGERED_BY)
            log.warn(
                "KillSwitch auto-ON: userId={} reason={} triggeredBy={}",
                userId,
                REASON,
                TRIGGERED_BY,
            )
        } catch (e: Exception) {
            log.error(
                "KillSwitchAutoToggler 처리 실패: offset={} err={}",
                record.offset(),
                e.message,
                e,
            )
        }
    }

    /**
     * KillSwitch enable — Phase 2 port 우선, 부재 시 baseline JPA repo reuse.
     */
    internal fun engage(userId: String) {
        val port = killSwitchTogglePortProvider.getIfAvailable()
        if (port != null) {
            try {
                port.engage(userId, REASON)
                return
            } catch (e: Exception) {
                log.error(
                    "KillSwitchTogglePort.engage 실패 — baseline JPA로 fallback: userId={} err={}",
                    userId,
                    e.message,
                )
            }
        }
        // baseline JPA fallback (ADR 0042·0043 baseline)
        try {
            killSwitchJpaRepository.enableKillSwitch(
                userId = userId,
                reason = REASON,
                updatedBy = UPDATED_BY,
            )
        } catch (e: Exception) {
            log.error(
                "KillSwitchJpaRepository.enableKillSwitch 실패 — 자본 사고 노출 risk: userId={} err={}",
                userId,
                e.message,
                e,
            )
        }
    }
}
