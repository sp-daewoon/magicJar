package com.mj.consumer.infrastructure.cache

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.InstrumentType
import com.mj.domain.market.instrument.port.InstrumentSearchPort
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate

/**
 * Redis 기반 종목 검색 어댑터 (ADR 0037 §2-4).
 *
 * 1차 단순 전략:
 * - Redis ZSET `instrument:idx:code` — 코드 prefix 검색 (ZRANGEBYLEX)
 * - Redis ZSET `instrument:idx:name` — 종목명 substring 검색 (full scan)
 * - Redis HASH `instrument:data:{code}` — 종목 상세 데이터 캐시 (TTL 1일)
 *
 * rebuild 이벤트: InstrumentMasterSyncJob 완료 후 호출
 */
@Component
class RedisInstrumentSearchAdapter(
    private val redisTemplate: RedisTemplate<String, String>,
) : InstrumentSearchPort {
    private val log = LoggerFactory.getLogger(RedisInstrumentSearchAdapter::class.java)

    companion object {
        private const val IDX_CODE = "instrument:idx:code"
        private const val IDX_NAME = "instrument:idx:name"
        private const val DATA_PREFIX = "instrument:data:"
        private const val LIST_KEY_PREFIX = "instrument:list:"
        private val TTL = Duration.ofDays(1)
    }

    override fun search(
        query: String,
        limit: Int,
    ): List<Instrument> {
        if (query.isBlank()) return emptyList()

        val q = query.lowercase().trim()
        val zOps = redisTemplate.opsForZSet()
        val result = mutableListOf<Instrument>()

        // 1. 코드 prefix 매칭 (ZRANGEBYLEX)
        try {
            val prefix = q.uppercase()
            val range =
                Range.of(
                    Range.Bound.inclusive(prefix),
                    Range.Bound.inclusive("$prefix￿"),
                )
            val codeMembers: Set<String>? = zOps.rangeByLex(IDX_CODE, range, Limit.unlimited())
            codeMembers?.forEach { member ->
                if (result.size >= limit) return@forEach
                val code = member.substringBefore("|")
                fetchFromCache(code)?.let { result.add(it) }
            }
        } catch (ex: Exception) {
            log.warn("[redis-search] 코드 prefix 검색 실패: {}", ex.message)
        }

        // 2. 종목명 substring 매칭
        if (result.size < limit) {
            try {
                val nameMembers: Set<String>? = zOps.range(IDX_NAME, 0, -1)
                val existingCodes = result.map { it.code }.toSet()
                nameMembers?.forEach { member ->
                    if (result.size >= limit) return@forEach
                    val name = member.substringBefore("|")
                    val code = member.substringAfter("|")
                    if (name.contains(q) && code !in existingCodes) {
                        fetchFromCache(code)?.let { result.add(it) }
                    }
                }
            } catch (ex: Exception) {
                log.warn("[redis-search] 종목명 substring 검색 실패: {}", ex.message)
            }
        }

        if (result.isEmpty()) {
            log.debug("[redis-search] Redis 결과 없음, query={}", query)
        }
        return result
    }

    override fun rebuild(instruments: List<Instrument>) {
        if (instruments.isEmpty()) {
            log.warn("[redis-search] rebuild 대상 종목 없음")
            return
        }

        log.info("[redis-search] 인덱스 재빌드 시작: {} 건", instruments.size)

        val zOps = redisTemplate.opsForZSet()

        // 기존 인덱스 삭제
        redisTemplate.delete(IDX_CODE)
        redisTemplate.delete(IDX_NAME)

        instruments.forEach { inst ->
            zOps.add(IDX_CODE, "${inst.code.uppercase()}|${inst.code}", 0.0)
            zOps.add(IDX_NAME, "${inst.name}|${inst.code}", 0.0)

            val dataKey = "$DATA_PREFIX${inst.code}"
            val fields = mutableMapOf<String, String>()
            fields["code"] = inst.code
            fields["name"] = inst.name
            fields["market"] = inst.market.name
            fields["type"] = inst.type.name
            fields["active"] = inst.active.toString()
            fields["listedAt"] = inst.listedAt.toString()
            fields["tradingUnit"] = inst.tradingUnit.toString()
            inst.isin?.let { fields["isin"] = it }
            inst.nameEn?.let { fields["nameEn"] = it }
            inst.sector?.let { fields["sector"] = it }
            inst.delistedAt?.let { fields["delistedAt"] = it.toString() }
            inst.parValue?.let { fields["parValue"] = it.toPlainString() }

            @Suppress("UNCHECKED_CAST")
            (redisTemplate.opsForHash<String, String>() as org.springframework.data.redis.core.HashOperations<String, String, String>)
                .putAll(dataKey, fields)
            redisTemplate.expire(dataKey, TTL)
        }

        // 시장별 universe 캐시
        instruments.groupBy { it.market }.forEach { (market, list) ->
            val listKey = "$LIST_KEY_PREFIX${market.name}"
            redisTemplate.delete(listKey)
            list.forEach { inst -> zOps.add(listKey, inst.code, 0.0) }
            redisTemplate.expire(listKey, TTL)
        }

        log.info("[redis-search] 인덱스 재빌드 완료: {} 건", instruments.size)
    }

    private fun fetchFromCache(code: String): Instrument? {
        val dataKey = "$DATA_PREFIX$code"

        @Suppress("UNCHECKED_CAST")
        val hashOps =
            redisTemplate.opsForHash<String, String>()
                as org.springframework.data.redis.core.HashOperations<String, String, String>
        val fields: Map<String, String> = hashOps.entries(dataKey)
        if (fields.isEmpty()) return null

        return runCatching {
            Instrument(
                code = fields["code"] ?: return null,
                isin = fields["isin"],
                name = fields["name"] ?: return null,
                nameEn = fields["nameEn"],
                market = Market.valueOf(fields["market"] ?: "KOSPI"),
                type = InstrumentType.valueOf(fields["type"] ?: "STOCK"),
                sector = fields["sector"],
                listedAt = fields["listedAt"]?.let { LocalDate.parse(it) } ?: LocalDate.EPOCH,
                delistedAt = fields["delistedAt"]?.let { LocalDate.parse(it) },
                parValue = fields["parValue"]?.let { BigDecimal(it) },
                tradingUnit = fields["tradingUnit"]?.toInt() ?: 1,
                active = fields["active"]?.toBoolean() ?: true,
            )
        }.getOrElse {
            log.warn("[redis-search] 캐시 데이터 역직렬화 실패: code={} error={}", code, it.message)
            null
        }
    }
}
