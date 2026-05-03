package com.mj.api.application.market

import com.mj.domain.common.Interval
import com.mj.domain.market.cache.port.CacheMetaRepository
import com.mj.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * LRU 캔들 캐시 eviction 서비스 (T19-T7).
 *
 * 동작 흐름:
 * 1. instrument_cache_meta의 byte_estimate 합산 → total bytes 계산
 * 2. total ≤ threshold → 즉시 종료 (eviction 불필요)
 * 3. total > threshold → 초과분 + 10% buffer 만큼 evict
 *    - last_accessed_at ASC 순서로 (code, interval) 페어 1개씩 선택
 *    - candle 테이블 해당 (code, interval) DELETE
 *    - instrument_cache_meta 해당 row DELETE
 *    - 누적 회수 바이트가 목표에 도달하면 종료
 *
 * 대상 interval: D1(일봉)은 영구 보존 — eviction 대상에서 제외.
 * meta row에 D1이 존재하더라도 삭제하지 않는다.
 *
 * 트랜잭션: evict 루프 전체를 단일 트랜잭션으로 묶어 중간 실패 시 롤백.
 * 단, 대용량 삭제 시 lock 경합이 우려되면 loop 내 개별 트랜잭션으로 전환 가능.
 *
 * @param maxBytes           캐시 용량 상한 (bytes). default 8 GB.
 * @param candleRepository   candle 테이블 포트
 * @param cacheMetaRepository instrument_cache_meta 포트
 */
@Service
class CacheEvictionService(
    private val candleRepository: CandleRepository,
    private val cacheMetaRepository: CacheMetaRepository,
    @Value("\${magicjar.candle.cache.max-bytes:8589934592}") private val maxBytes: Long,
) {
    private val log = LoggerFactory.getLogger(CacheEvictionService::class.java)

    /**
     * 용량 초과 시 LRU 순서로 캐시 항목을 evict한다.
     *
     * @return eviction 결과 요약 (삭제 항목 수, 회수 bytes)
     */
    @Transactional
    fun evictIfNeeded(): EvictionResult {
        // 1. 전체 캐시 byte 합산 (D1 포함 합산 — meta row가 있는 경우)
        val allMeta = cacheMetaRepository.listByLastAccessedAsc(Int.MAX_VALUE)
        val totalBytes = allMeta.sumOf { it.byteEstimate }

        if (totalBytes <= maxBytes) {
            log.debug(
                "[eviction] skip — totalBytes={} maxBytes={} (under threshold)",
                totalBytes,
                maxBytes,
            )
            return EvictionResult(evictedCount = 0, reclaimedBytes = 0L)
        }

        // 2. 초과분 + 10% buffer = 목표 회수 바이트
        val excessBytes = totalBytes - maxBytes
        val targetReclaim = (excessBytes * 1.1).toLong()

        log.info(
            "[eviction] START — totalBytes={} maxBytes={} excessBytes={} targetReclaim={}",
            totalBytes,
            maxBytes,
            excessBytes,
            targetReclaim,
        )

        var reclaimedBytes = 0L
        var evictedCount = 0

        // 3. LRU 순서(last_accessed_at ASC) 순서대로 evict
        //    allMeta는 이미 ASC 정렬 상태 (listByLastAccessedAsc)
        for (meta in allMeta) {
            if (reclaimedBytes >= targetReclaim) break

            // D1은 영구 보존 — 건너뜀
            if (meta.interval == Interval.D1) continue

            val rowsDeleted = candleRepository.deleteByCodeAndInterval(meta.code, meta.interval)
            cacheMetaRepository.deleteByCodeAndInterval(meta.code, meta.interval)

            reclaimedBytes += meta.byteEstimate
            evictedCount++

            log.info(
                "[eviction] evicted code={} interval={} rows={} byteEstimate={} reclaimedSoFar={}",
                meta.code,
                meta.interval,
                rowsDeleted,
                meta.byteEstimate,
                reclaimedBytes,
            )
        }

        log.info(
            "[eviction] DONE — evictedCount={} reclaimedBytes={} targetReclaim={}",
            evictedCount,
            reclaimedBytes,
            targetReclaim,
        )

        return EvictionResult(evictedCount = evictedCount, reclaimedBytes = reclaimedBytes)
    }

    /**
     * eviction 결과 요약.
     *
     * @param evictedCount  삭제된 (code, interval) 페어 수
     * @param reclaimedBytes 회수된 추정 바이트
     */
    data class EvictionResult(
        val evictedCount: Int,
        val reclaimedBytes: Long,
    )
}
