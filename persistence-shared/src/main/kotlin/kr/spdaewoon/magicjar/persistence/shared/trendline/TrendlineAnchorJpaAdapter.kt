package kr.spdaewoon.magicjar.persistence.shared.trendline

import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.domain.strategy.dsl.TrendlineAnchors
import kr.spdaewoon.magicjar.domain.strategy.port.TrendlineAnchorCacheInvalidator
import kr.spdaewoon.magicjar.domain.strategy.port.TrendlinePricePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap

/**
 * [TrendlinePricePort] JPA 어댑터 — `trendline` 테이블 anchor 조회 + TTL 캐시 (ADR 0140 §3-2 · R2).
 *
 * ## 모듈 합류 (헌법 §11-1 · ADR 0088 패턴)
 * persistence-shared 에 위치해 api(백테스트)·consumer(실시간 평가) 양쪽 ApplicationContext 에 `@Component`
 * 로 합류한다. strategy-engine [kr.spdaewoon.magicjar.strategy.JvmStrategyEvaluator] 의
 * `trendlinePricePort: TrendlinePricePort?` 생성자 파라미터가 이 빈을 optional 주입받는다(빈 부재 시 null →
 * ERROR 로그 + 조건 false, §11-2 silent 금지). RiskConfigJpaAdapter 선례(2026-06-10 incident fix) 1:1 정합.
 *
 * ## 캐시 (ADR 0140 §3-2 LOCK — sandbox 100ms/CPU 50ms 예산 보호)
 * 평가는 봉 경계마다 반복 호출된다. anchor 는 선 수정 전까지 불변이므로 선 id 별 [TrendlineAnchors] 를
 * **프로세스 로컬 TTL 캐시**(ConcurrentHashMap)로 보관한다. 선형보간 자체는 순수 산술(예산 무영향)이지만
 * DB 왕복은 hot path 예산을 잠식하므로 캐시가 이를 흡수한다. 외부 캐시(Redis) 의존 없이 in-memory 로 격리 —
 * ADR 0140 §3-2 "in-memory/Redis" 중 in-memory 선택(신규 인프라 자원 0, spec §1 audit 정합).
 *
 * ### 무효화 ([TrendlineAnchorCacheInvalidator])
 * - **같은 JVM(api)**: 선 수정(PUT)·삭제(DELETE) 시 `TrendlineService` 가 [invalidate] 즉시 호출 → 다음 조회가 fresh.
 * - **다른 JVM(consumer)**: 무효화 호출을 못 받으므로 **TTL 만료**로 자연 갱신(default 30s). 봉 경계 평가 주기
 *   (분 단위) > TTL 이라 실매매 기준 drift 는 TTL 상한으로 bound. ADR 0140 §3-2 "TTL 짧은 in-memory 캐시" 정합.
 *
 * ### null(부재/삭제) 정합 (§11-2)
 * `anchorsOf` 가 null 이면 부재는 캐시하지 않는다(선 재생성 즉시 반영). 평가기가 ERROR 로그 + 조건 false 처리.
 *
 * 헌법 §6 (ADR 0051): interface body fun 0건 — 변환 helper 는 본 클래스 private fun.
 *
 * // AI 호출 0 — T-SIG/추세매매 트랙 정책
 */
@Component
class TrendlineAnchorJpaAdapter(
    private val repository: TrendlineAnchorJpaRepository,
    // TTL=0 이면 매 조회 만료(캐시 무효화 로직 테스트 seam) · default 30s.
    @Value("\${magicjar.trendline.cache-ttl-ms:30000}") private val cacheTtlMs: Long,
) : TrendlinePricePort,
    TrendlineAnchorCacheInvalidator {
    private val log = LoggerFactory.getLogger(TrendlineAnchorJpaAdapter::class.java)

    /** trendlineId → (anchor 스냅샷, 만료 epoch millis). 부재는 미캐시. */
    private val cache: ConcurrentHashMap<Long, CacheEntry> = ConcurrentHashMap()

    @Transactional(readOnly = true)
    override fun anchorsOf(trendlineId: Long): TrendlineAnchors? {
        val now = System.currentTimeMillis()
        cache[trendlineId]?.let { entry ->
            if (entry.expiresAt > now) return entry.anchors
            cache.remove(trendlineId, entry) // 만료 — 제거 후 재조회
        }

        val row = repository.findById(trendlineId).orElse(null)
        if (row == null) {
            // 부재/삭제 — 캐시하지 않음(재생성 즉시 반영). 평가기가 §11-2 ERROR 로그 + 조건 false.
            return null
        }

        val anchors = row.toAnchors()
        cache[trendlineId] = CacheEntry(anchors, now + cacheTtlMs)
        return anchors
    }

    override fun invalidate(trendlineId: Long) {
        val removed = cache.remove(trendlineId) != null
        if (removed) {
            log.debug("trendline anchor 캐시 무효화 — id={}", trendlineId)
        }
    }

    override fun invalidateAll() {
        val size = cache.size
        cache.clear()
        log.debug("trendline anchor 캐시 전체 무효화 — {} entries", size)
    }

    // --- private helper (헌법 §6 — interface 아닌 클래스 내부) ---

    private fun TrendlineAnchorRow.toAnchors(): TrendlineAnchors =
        TrendlineAnchors(
            anchor1Ts = anchor1Ts.toKotlinInstant(),
            anchor1Price = anchor1Price,
            anchor2Ts = anchor2Ts.toKotlinInstant(),
            anchor2Price = anchor2Price,
            extendLeft = extendLeft,
            extendRight = extendRight,
        )

    private data class CacheEntry(
        val anchors: TrendlineAnchors,
        val expiresAt: Long,
    )
}
