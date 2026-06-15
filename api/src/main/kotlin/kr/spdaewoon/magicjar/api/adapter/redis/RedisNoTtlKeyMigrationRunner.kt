package kr.spdaewoon.magicjar.api.adapter.redis

import kr.spdaewoon.magicjar.domain.redis.RedisKeyMigrationRule
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * Copy-only Redis key migration for the naming-convention registry.
 *
 * Safety contract:
 * - Default OFF: enabled only with `magicjar.redis-key-migration.enabled=true`.
 * - Default dry-run: writes only with `magicjar.redis-key-migration.dry-run=false`.
 * - Source key must have no TTL (`TTL == -1`). Expiring keys are skipped and allowed to
 *   expire/regenerate naturally so stale data is not extended into a new namespace.
 * - Existing target keys are not replaced. Legacy keys are never deleted.
 * - Critical trading keys(account/order/risk/KIS token) are not present in the registry's
 *   copy rule list; a missing TTL there should be treated as an incident, not migrated.
 */
@Component
@ConditionalOnProperty(prefix = "magicjar.redis-key-migration", name = ["enabled"], havingValue = "true")
class RedisNoTtlKeyMigrationRunner(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${magicjar.redis-key-migration.dry-run:true}")
    private val dryRun: Boolean = true,
    @Value("\${magicjar.redis-key-migration.scan-count:500}")
    private val scanCount: Long = 500,
    @Value("\${magicjar.redis-key-migration.max-copy-candidates:1000}")
    private val maxCopyCandidates: Int = 1000,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(RedisNoTtlKeyMigrationRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val rules = RedisKeyRegistry.noTtlCopyMigrationRules
        if (dryRun) {
            log.warn("[RedisKeyMigration] DRY-RUN mode — Redis writes disabled")
        }

        val total = MigrationStats()
        for (rule in rules) {
            val stats = migrateRule(rule, maxCopyCandidates - total.copyCandidates)
            total += stats
            if (total.copyCandidates >= maxCopyCandidates) {
                log.warn(
                    "[RedisKeyMigration] max-copy-candidates 도달 — 이후 rule skip: max={} copied={} dryRun={}",
                    maxCopyCandidates,
                    total.copied,
                    dryRun,
                )
                break
            }
        }

        log.info(
            "[RedisKeyMigration] 완료 dryRun={} scanned={} noTtlCandidates={} copied={} targetExists={} ttlSkipped={} missing={} sameKey={} errors={}",
            dryRun,
            total.scanned,
            total.copyCandidates,
            total.copied,
            total.targetExists,
            total.ttlSkipped,
            total.missing,
            total.sameKey,
            total.errors,
        )
    }

    private fun migrateRule(
        rule: RedisKeyMigrationRule,
        remainingBudget: Int,
    ): MigrationStats {
        if (remainingBudget <= 0) return MigrationStats()

        val stats = MigrationStats()
        val keys = scan(rule.legacyPattern)
        for (sourceKey in keys) {
            stats.scanned++
            val targetKey = rule.targetFor(sourceKey) ?: continue
            if (sourceKey == targetKey) {
                stats.sameKey++
                continue
            }

            val ttl = sourceTtl(sourceKey)
            when {
                ttl == -2L -> {
                    stats.missing++
                    continue
                }
                ttl != -1L -> {
                    stats.ttlSkipped++
                    continue
                }
            }

            if (redisTemplate.hasKey(targetKey) == true) {
                stats.targetExists++
                continue
            }

            stats.copyCandidates++
            if (dryRun) {
                log.info("[RedisKeyMigration] dry-run copy candidate rule={} {} -> {}", rule.name, sourceKey, targetKey)
            } else {
                if (copyWithoutReplace(sourceKey, targetKey)) {
                    stats.copied++
                    log.info("[RedisKeyMigration] copied rule={} {} -> {}", rule.name, sourceKey, targetKey)
                } else {
                    stats.errors++
                    log.warn("[RedisKeyMigration] copy skipped/failed rule={} {} -> {}", rule.name, sourceKey, targetKey)
                }
            }

            if (stats.copyCandidates >= remainingBudget) break
        }
        log.info(
            "[RedisKeyMigration] rule={} scanned={} noTtlCandidates={} copied={} targetExists={} ttlSkipped={} sameKey={} errors={}",
            rule.name,
            stats.scanned,
            stats.copyCandidates,
            stats.copied,
            stats.targetExists,
            stats.ttlSkipped,
            stats.sameKey,
            stats.errors,
        )
        return stats
    }

    private fun scan(pattern: String): List<String> {
        val options =
            ScanOptions
                .scanOptions()
                .match(pattern)
                .count(scanCount.coerceAtLeast(1))
                .build()
        val keys = mutableListOf<String>()
        redisTemplate.execute { connection ->
            connection.scan(options).use { cursor ->
                while (cursor.hasNext()) {
                    keys += String(cursor.next(), StandardCharsets.UTF_8)
                }
            }
            null
        }
        return keys
    }

    private fun sourceTtl(key: String): Long =
        runCatching { redisTemplate.getExpire(key) }
            .getOrElse { ex ->
                log.warn("[RedisKeyMigration] TTL 조회 실패 — skip: key={} err={}", key, ex.message)
                -2L
            }

    private fun copyWithoutReplace(
        sourceKey: String,
        targetKey: String,
    ): Boolean =
        runCatching {
            val raw =
                redisTemplate.execute { connection ->
                    connection.execute(
                        "COPY",
                        sourceKey.toByteArray(StandardCharsets.UTF_8),
                        targetKey.toByteArray(StandardCharsets.UTF_8),
                    )
                }
            redisCopySucceeded(raw)
        }.getOrElse { ex ->
            log.warn(
                "[RedisKeyMigration] Redis COPY 실패 — source={} target={} err={}",
                sourceKey,
                targetKey,
                ex.message,
            )
            false
        }

    private fun redisCopySucceeded(raw: Any?): Boolean =
        when (raw) {
            is Boolean -> raw
            is Number -> raw.toLong() == 1L
            is ByteArray -> String(raw, StandardCharsets.UTF_8) == "1"
            else -> raw?.toString() == "1"
        }

    private data class MigrationStats(
        var scanned: Int = 0,
        var copyCandidates: Int = 0,
        var copied: Int = 0,
        var targetExists: Int = 0,
        var ttlSkipped: Int = 0,
        var missing: Int = 0,
        var sameKey: Int = 0,
        var errors: Int = 0,
    ) {
        operator fun plusAssign(other: MigrationStats) {
            scanned += other.scanned
            copyCandidates += other.copyCandidates
            copied += other.copied
            targetExists += other.targetExists
            ttlSkipped += other.ttlSkipped
            missing += other.missing
            sameKey += other.sameKey
            errors += other.errors
        }
    }
}
