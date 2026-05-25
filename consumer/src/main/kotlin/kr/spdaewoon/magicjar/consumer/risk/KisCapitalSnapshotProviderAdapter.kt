package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * KIS 잔고 기반 [CapitalSnapshotProvider] 실제 어댑터 — T-ACCT Phase 3 (ADR 0081 §2-5 + ADR 0067 §7-1).
 *
 * ### 책임
 * Redis cache `account:balance:{userId}:{REAL|MOCK}` 읽기 only — api `AccountQueryService`가 30s TTL로 채운 JSON에서
 * `KisAccountBalance.balance.netAssetValue` (순자산금액 = `nass_amt`) 추출 → BigDecimal 반환.
 *
 * ### 모듈 경계 정합 (ADR 0004 LOCK)
 * - consumer → domain (`CapitalSnapshotProvider` port + `KisAccountBalance` domain) ✅
 * - consumer → api 의존 0 LOCK ✅ — Redis 직접 access (cache key 양 모듈 공유)
 * - api `AccountQueryService.fetchOrLoad` 가 cache write (`account:balance:{userId}:{accountType}` TTL 30s)
 * - consumer 본 어댑터가 동일 key 읽기 only — KIS REST 직접 호출 0
 *
 * ### Cache key 매트릭스
 * | 모듈 | 동작 | 위치 |
 * |---|---|---|
 * | api  | write (TTL 30s) | `AccountQueryService.CACHE_KEY_PREFIX = "account:balance"` |
 * | consumer | read only | 본 어댑터 |
 *
 * cache key prefix는 코드 중복 회피 위해 string literal — api 모듈 의존 회피 (ADR 0004 LOCK).
 * key prefix 변경 시 양 모듈 동시 갱신 의무 (운영 룰).
 *
 * ### fail-safe 정책 (ADR 0081 §2-5 + 자본 보호 LOCK)
 * 3 결함 케이스 모두 `BigDecimal.ZERO` 반환 — POSITION_LIMIT 룰 동작:
 *
 * 1. **cache miss** (api endpoint 미호출 또는 TTL 만료) → 0 반환
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
 * ### KIS endpoint 호출 0 LOCK (ADR 0037 정합)
 * 본 어댑터는 Redis 읽기 only — KIS REST/WS 직접 호출 0건. consumer는 추가 KIS endpoint 호출 0
 * (`inquire-balance` 1건만 api 모듈이 사용자 요청 트리거로 호출).
 *
 * ### mock 합성 0 LOCK (feedback_no_mock_data)
 * cache miss 시 0 반환 — fixture 합성 0. 명시적 자본 보호.
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
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
) : CapitalSnapshotProvider {
    private val log = LoggerFactory.getLogger(KisCapitalSnapshotProviderAdapter::class.java)

    /**
     * 사용자 자본 (순자산금액) 조회 — Redis cache 읽기 only.
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
                "KisCapitalSnapshotProvider cache MISS — fail-safe 0 (userId={})",
                maskUserId(userId),
            )
            return BigDecimal.ZERO
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
