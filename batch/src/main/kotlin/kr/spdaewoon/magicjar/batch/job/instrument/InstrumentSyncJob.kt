package kr.spdaewoon.magicjar.batch.job.instrument

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentAliasJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentJpaRepository
import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.port.KisInstrumentMasterPort
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 종목 마스터 sync 잡 (T-IM1 PR-11a — ADR 0017·0039 정합).
 *
 * 처리 흐름 (pr-11-im1-design.md §5-3):
 * 1. Redis 동시 실행 락 (instrument:sync:running SET NX EX 600)
 * 2. KisInstrumentMasterPort.fetchAll() (KIS 종목 마스터 zip 다운로드·파싱)
 * 3. diff (기존 active vs KIS 응답)
 * 4. DB upsert (INSERT NEW · UPDATE CHANGED · DELIST 폐지)
 * 5. instrument_alias upsert (KRX source 1건/종목)
 * 6. Kafka instrument.updated.v1 publish
 * 7. Redis invalidate (instrument:list:* · instrument:search:* · by-code:*)
 * 8. Redis instrument:sync:lastAt SET
 * 9. 락 해제
 *
 * 삭제 금지 (ADR 0017 §2-2): delisted_at UPDATE + active=false 만. DELETE 0건.
 */
@Component
class InstrumentSyncJob(
    private val kisPort: KisInstrumentMasterPort,
    private val instrumentRepo: BatchInstrumentJpaRepository,
    private val aliasRepo: BatchInstrumentAliasJpaRepository,
    private val redisTemplate: StringRedisTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(InstrumentSyncJob::class.java)

    companion object {
        private const val SYNC_LOCK_KEY = "instrument:sync:running"
        private const val SYNC_LAST_AT_KEY = "instrument:sync:lastAt"
        private const val SYNC_LOCK_TTL_SECONDS = 600L
        private const val KAFKA_TOPIC = "instrument.updated.v1"
    }

    /**
     * 종목 마스터 sync 실행.
     * @param cronTag 로깅용 cron 시각 태그 (예: "06:05", "boot")
     * @return InstrumentSyncResult — sync 결과
     */
    fun run(cronTag: String): InstrumentSyncResult {
        val startedAt = Instant.now()

        // 1. 동시 실행 락 획득
        val lockAcquired =
            redisTemplate
                .opsForValue()
                .setIfAbsent(SYNC_LOCK_KEY, "true", Duration.ofSeconds(SYNC_LOCK_TTL_SECONDS)) == true

        if (!lockAcquired) {
            log.warn("[instrument-sync] cron={} — 이미 실행 중 (락 존재) skip", cronTag)
            return InstrumentSyncResult(
                cronTag = cronTag,
                fetched = 0,
                inserted = 0,
                updated = 0,
                delisted = 0,
                failed = 0,
                startedAt = startedAt,
                finishedAt = Instant.now(),
            )
        }

        return try {
            doSync(cronTag, startedAt)
        } finally {
            redisTemplate.delete(SYNC_LOCK_KEY)
        }
    }

    private fun doSync(
        cronTag: String,
        startedAt: Instant,
    ): InstrumentSyncResult {
        // 2. KIS fetch
        val kisInstruments: List<Instrument>
        try {
            kisInstruments = kisPort.fetchAll()
            log.info("[instrument-sync] cron={} KIS fetch 완료 — {} 건", cronTag, kisInstruments.size)
        } catch (e: Exception) {
            log.warn(
                "[instrument-sync] cron={} KIS fetch 실패 — 시드 fallback 유지. err={}",
                cronTag,
                e.message,
            )
            return InstrumentSyncResult(
                cronTag = cronTag,
                fetched = 0,
                inserted = 0,
                updated = 0,
                delisted = 0,
                failed = 1,
                startedAt = startedAt,
                finishedAt = Instant.now(),
            )
        }

        // 3. diff
        val existingActive = instrumentRepo.findAllByActiveTrue().associateBy { it.code }
        val kisMap = kisInstruments.associateBy { it.code }

        val newCodes = kisMap.keys - existingActive.keys
        val delistedCodes = existingActive.keys - kisMap.keys
        val existingCodes = existingActive.keys.intersect(kisMap.keys)

        val today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString()
        var inserted = 0
        var updated = 0
        var delisted = 0
        var failed = 0

        val changes = mutableListOf<Map<String, String>>()

        // 4-A. INSERT NEW
        for (code in newCodes) {
            val ins = kisMap[code] ?: continue
            try {
                upsertInstrument(ins)
                upsertAlias(ins.code, ins.name)
                inserted++
                changes.add(mapOf("code" to ins.code, "action" to "INSERT", "market" to ins.market.name, "name" to ins.name))
            } catch (e: Exception) {
                log.warn("[instrument-sync] INSERT 실패 code={} err={}", code, e.message)
                failed++
            }
        }

        // 4-B. UPDATE CHANGED
        for (code in existingCodes) {
            val ins = kisMap[code] ?: continue
            val existing = existingActive[code] ?: continue
            val changed =
                existing.name != ins.name ||
                    existing.market != ins.market.name ||
                    existing.type != ins.type.name ||
                    existing.sector != ins.sector
            if (changed) {
                try {
                    upsertInstrument(ins)
                    upsertAlias(ins.code, ins.name)
                    updated++
                    changes.add(mapOf("code" to ins.code, "action" to "UPDATE", "market" to ins.market.name, "name" to ins.name))
                } catch (e: Exception) {
                    log.warn("[instrument-sync] UPDATE 실패 code={} err={}", code, e.message)
                    failed++
                }
            }
        }

        // 4-C. DELIST (DELETE 금지 — ADR 0017 §2-2)
        for (code in delistedCodes) {
            val existing = existingActive[code] ?: continue
            try {
                instrumentRepo.markDelisted(code, today)
                delisted++
                changes.add(mapOf("code" to code, "action" to "DELIST", "market" to existing.market, "name" to existing.name))
            } catch (e: Exception) {
                log.warn("[instrument-sync] DELIST 실패 code={} err={}", code, e.message)
                failed++
            }
        }

        // 5. Kafka publish
        publishKafka(changes, startedAt)

        // 6. Redis invalidate
        invalidateRedis(changes.mapNotNull { it["code"] })

        // 7. lastAt SET
        redisTemplate.opsForValue().set(SYNC_LAST_AT_KEY, Instant.now().toString(), Duration.ofDays(1))

        val result =
            InstrumentSyncResult(
                cronTag = cronTag,
                fetched = kisInstruments.size,
                inserted = inserted,
                updated = updated,
                delisted = delisted,
                failed = failed,
                startedAt = startedAt,
                finishedAt = Instant.now(),
            )
        log.info(result.toString())
        return result
    }

    @Transactional
    fun upsertInstrument(ins: Instrument) {
        instrumentRepo.upsert(
            code = ins.code,
            isin = ins.isin,
            name = ins.name,
            nameEn = ins.nameEn,
            market = ins.market.name,
            type = ins.type.name,
            sector = ins.sector,
        )
    }

    @Transactional
    fun upsertAlias(
        code: String,
        name: String,
    ) {
        aliasRepo.upsert(code = code, alias = name, source = "KRX")
    }

    private fun publishKafka(
        changes: List<Map<String, String>>,
        syncedAt: Instant,
    ) {
        if (changes.isEmpty()) return
        try {
            val byMarket = changes.groupBy { it["market"] ?: "UNKNOWN" }
            for ((market, marketChanges) in byMarket) {
                val payload =
                    mapOf(
                        "syncedAt" to syncedAt.toString(),
                        "changes" to marketChanges,
                    )
                kafkaTemplate.send(KAFKA_TOPIC, market, objectMapper.writeValueAsString(payload))
            }
        } catch (e: Exception) {
            log.warn("[instrument-sync] Kafka publish 실패 (비치명) — err={}", e.message)
        }
    }

    private fun invalidateRedis(changedCodes: List<String>) {
        try {
            // instrument:list:* 전체 삭제
            val listKeys = redisTemplate.keys("instrument:list:*") ?: emptySet()
            if (listKeys.isNotEmpty()) redisTemplate.delete(listKeys)

            // instrument:search:prefix:* 전체 삭제 (lazy 재구성)
            val searchKeys = redisTemplate.keys("instrument:search:*") ?: emptySet()
            if (searchKeys.isNotEmpty()) redisTemplate.delete(searchKeys)

            // 변경된 코드별 by-code cache 삭제
            for (code in changedCodes) {
                redisTemplate.delete("instrument:by-code:$code")
            }
            log.debug("[instrument-sync] Redis invalidate 완료 — codes={}", changedCodes.size)
        } catch (e: Exception) {
            log.warn("[instrument-sync] Redis invalidate 실패 (비치명) — err={}", e.message)
        }
    }
}
