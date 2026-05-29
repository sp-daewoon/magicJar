package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration

/**
 * KIS 잔고 기반 [CapitalSnapshotProvider] 실제 어댑터 — T-ACCT Phase 3 (ADR 0081 §2-5 + ADR 0067 §7-1).
 *
 * ### 책임
 * 1차로 Redis cache `account:balance:{userId}:{REAL|MOCK}` 를 읽고,
 * cache miss 시 consumer의 [KisBalanceQueryPort] 구현으로 KIS 잔고를 1회 조회해 cache를 보강한다.
 * 이후 `KisAccountBalance.balance.netAssetValue` (순자산금액 = `nass_amt`) 추출 → BigDecimal 반환.
 *
 * ### 모듈 경계 정합 (ADR 0004 LOCK)
 * - consumer → domain (`CapitalSnapshotProvider` port + `KisAccountBalance` domain) ✅
 * - consumer → api 의존 0 LOCK ✅ — Redis 직접 access (cache key 양 모듈 공유)
 * - api `AccountQueryService.fetchOrLoad` 가 cache write (`account:balance:{userId}:{accountType}` TTL 10s)
 * - consumer 본 어댑터도 동일 key를 읽고, miss 시 domain port [KisBalanceQueryPort] 로 cache를 보강
 *
 * ### Cache key 매트릭스
 * | 모듈 | 동작 | 위치 |
 * |---|---|---|
 * | api  | write (TTL 10s) | `AccountQueryService.CACHE_KEY_PREFIX = "account:balance"` |
 * | consumer | read + fallback write | 본 어댑터 |
 *
 * cache key prefix는 코드 중복 회피 위해 string literal — api 모듈 의존 회피 (ADR 0004 LOCK).
 * key prefix 변경 시 양 모듈 동시 갱신 의무 (운영 룰).
 *
 * ### fail-safe 정책 (ADR 0081 §2-5 + 자본 보호 LOCK)
 * fallback 까지 실패한 결함 케이스는 `BigDecimal.ZERO` 반환 — POSITION_LIMIT 룰 동작:
 *
 * 1. **cache miss + fallback bean 부재/실패** → 0 반환
 * 2. **Redis exception** (connection 실패 등) → 0 반환 (graceful)
 * 3. **JSON 역직렬화 실패** (cache 오염) → 0 반환 (graceful)
 *
 * **자본 보호 evidence** (RiskGatewayEvaluator L146-179 검증):
 * - `currentCapital == 0` → `percentBudget = 0` → `budget = min(absoluteLimit, 0) = 0`
 * - 모든 BUY signal `unitCost > 0 > budget = 0` → POSITION_LIMIT BLOCKED
 * - 결과: cache 결함 시 모든 매수 차단 — 자본 보호 fail-safe 정합 ✅
 *
 * **revert graceful 정합** (ADR 0067 §7-1):
 * - default no-op bean (`CapitalSnapshotProviderAdapter.defaultCapitalSnapshotProvider`)이
 *   `@ConditionalOnMissingBean(CapitalSnapshotProvider::class)` 보유 → 본 `@Component` 등록 시 자동 비활성
 * - 본 어댑터 revert 시 default 자동 fallback 복귀 (자본 영향 0)
 *
 * ### KIS endpoint 호출 정책 (ADR 0037 정합)
 * 정상 경로는 Redis cache hit로 KIS 호출 0건. cache miss 때만 `inquire-balance` 1건으로 자본을 복구한다.
 *
 * ### mock 합성 0 LOCK (feedback_no_mock_data)
 * fallback 실패 시 0 반환 — fixture 합성 0. 명시적 자본 보호.
 *
 * ### AI 호출 0 LOCK (룰 5 + ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 * LLM provider import 0 — 룰 기반 자본 조회 전용.
 *
 * spec 인용: ADR 0081 §2-5 KisCapitalSnapshotProviderAdapter + ADR 0067 §7-1 CapitalSnapshotProvider.
 */
@Component
class KisCapitalSnapshotProviderAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val balanceQueryPortProvider: ObjectProvider<KisBalanceQueryPort>? = null,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
    @Value("\${magicjar.risk.capital-cache-ttl-seconds:60}")
    private val capitalCacheTtlSeconds: Long = 60,
) : CapitalSnapshotProvider {
    private val log = LoggerFactory.getLogger(KisCapitalSnapshotProviderAdapter::class.java)

    /**
     * 사용자 자본 (순자산금액) 조회 — Redis cache 우선, miss 시 KIS fallback.
     *
     * @return `KisAccountBalance.balance.netAssetValue` 또는 fail-safe `BigDecimal.ZERO`
     */
    override fun getCapital(userId: String): BigDecimal {
        val cacheKey = cacheKeyOf(userId)

        val cached =
            try {
                redisTemplate.opsForValue().get(cacheKey)
            } catch (e: Exception) {
                log.warn(
                    "KisCapitalSnapshotProvider Redis 조회 실패 — fail-safe 0 (userId={} err={})",
                    maskUserId(userId),
                    e.message,
                )
                return BigDecimal.ZERO
            }

        if (cached == null) {
            log.debug(
                "KisCapitalSnapshotProvider cache MISS — KIS fallback 시도 (userId={})",
                maskUserId(userId),
            )
            return fetchAndCache(userId, cacheKey)
        }

        return try {
            val snapshot: KisAccountBalance = objectMapper.readValue(cached)
            val netAsset = snapshot.balance.netAssetValue
            log.debug(
                "KisCapitalSnapshotProvider cache HIT — netAsset={} (userId={})",
                netAsset,
                maskUserId(userId),
            )
            netAsset
        } catch (e: Exception) {
            log.warn(
                "KisCapitalSnapshotProvider JSON 역직렬화 실패 — fail-safe 0 (userId={} err={})",
                maskUserId(userId),
                e.message,
            )
            BigDecimal.ZERO
        }
    }

    private fun fetchAndCache(
        userId: String,
        cacheKey: String,
    ): BigDecimal {
        val port = balanceQueryPortProvider?.getIfAvailable()
        if (port == null) {
            log.warn(
                "KisCapitalSnapshotProvider KIS fallback bean 부재 — fail-safe 0 (userId={})",
                maskUserId(userId),
            )
            return BigDecimal.ZERO
        }

        val accountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        return try {
            val snapshot = port.fetchFullBalance(userId, accountType)
            cacheSnapshot(cacheKey, snapshot)
            val netAsset = snapshot.balance.netAssetValue
            log.info(
                "KisCapitalSnapshotProvider KIS fallback HIT — netAsset={} (userId={} type={})",
                netAsset,
                maskUserId(userId),
                accountType.name,
            )
            netAsset
        } catch (e: Exception) {
            log.warn(
                "KisCapitalSnapshotProvider KIS fallback 실패 — fail-safe 0 (userId={} err={})",
                maskUserId(userId),
                e.message,
            )
            BigDecimal.ZERO
        }
    }

    private fun cacheSnapshot(
        cacheKey: String,
        snapshot: KisAccountBalance,
    ) {
        runCatching {
            val ttl = Duration.ofSeconds(capitalCacheTtlSeconds.coerceAtLeast(1))
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(snapshot), ttl)
        }.onFailure { e ->
            log.warn("KisCapitalSnapshotProvider cache write 실패: key={} err={}", cacheKey, e.message)
        }
    }

    private fun cacheKeyOf(userId: String): String = "$CACHE_KEY_PREFIX:$userId:${RuntimeModeParser.accountTypeOrMock(magicjarMode).name}"

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    companion object {
        /**
         * Redis cache key prefix — api `AccountQueryService.CACHE_KEY_PREFIX` 와 동일.
         *
         * 모듈 경계 LOCK (ADR 0004): consumer → api 의존 회피 위해 string literal 중복 보유.
         * 변경 시 양 모듈 동시 갱신 의무 (운영 룰 — ADR 0081 §2-5).
         */
        const val CACHE_KEY_PREFIX = "account:balance"
    }
}
