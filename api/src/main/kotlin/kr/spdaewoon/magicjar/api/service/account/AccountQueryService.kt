package kr.spdaewoon.magicjar.api.service.account

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.api.controller.account.AccountBalanceResponse
import kr.spdaewoon.magicjar.api.controller.account.AccountHoldingResponse
import kr.spdaewoon.magicjar.api.controller.account.AccountSummaryResponse
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitException
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 계좌 잔고 쿼리 서비스 — T-ACCT Phase 2 (ADR 0081 §2-1 + spec §4-1 LOCK).
 *
 * ### 책임
 * - Redis `account:balance:{userId}` cache lookup (TTL 5s — C-2 자본 시각화 정합, 2026-05-20)
 * - cache miss → [KisBalanceQueryPort] 1회 호출 (output1 + output2 동시 흡수)
 * - 응답 cache write + 3 endpoint별 응답 매핑
 *
 * ### KIS 호출 빈도 (ADR 0037 정합)
 * - cache hit = KIS 0 호출
 * - cache miss = KIS 1 호출 (`inquire-balance` 1건만 — 추가 endpoint 0)
 * - frontend auto-refresh 30s = KIS 최대 1회 / 5s (사용자 trigger 시 즉시성)
 * - 자본 시각화 즉시성 우선 — 30s → 5s 단축 (frontend 30s 자동 갱신 vs Dashboard 토글·새로고침 시 신선도 차이 해소)
 *
 * ### mock 합성 0 LOCK (feedback_no_mock_data)
 * - KIS upstream 실패 → [KisBalanceQueryPort]가 IllegalStateException 또는 WebClientResponseException 발행
 * - 본 service는 예외를 그대로 propagate (controller에서 ResponseStatusException 502 변환)
 * - cache write 0 (실패 시 cache 오염 방지)
 *
 * ### AI 호출 0 LOCK (룰 5 + ADR 0023 §2-4)
 * - LLM provider import 0
 * - 잔고 분석 코멘트는 W4 T-AI 후보 (본 트랙 비범위)
 *
 * ### CapitalSnapshotProvider 결합 (ADR 0067 §7-1 + ADR 0081 §2-5)
 * - 본 service의 cache write 결과를 consumer `KisCapitalSnapshotProviderAdapter`가 Redis 읽기로 재사용
 * - api endpoint 호출 시 cache 갱신 → consumer 룰 평가 시 cache hit → KIS 미호출
 */
@Service
class AccountQueryService(
    private val kisBalanceQueryPort: KisBalanceQueryPort,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AccountQueryService::class.java)
    private val loadLocks = ConcurrentHashMap<String, Any>()
    private val rateLimitFailures = ConcurrentHashMap<String, LoadFailure>()

    companion object {
        const val CACHE_KEY_PREFIX = "account:balance"

        /**
         * Account 잔고 cache TTL — EGW00201 burst hotfix (2026-05-20, 5s → 10s 재조정).
         *
         * 변경 이력:
         *   - 1차: 30s default
         *   - 2차 (PR #594 C-2): 30s → 5s "돈 관련 실시간" dogfooding
         *   - 3차 (본 hotfix): 5s → 10s — EGW00201 "초당 거래건수 초과" burst 흡수
         *     * 사용자 evidence 2026-05-20: REAL+MOCK 동시 polling 5s + schedule worker + Bootstrap
         *       → KIS 호출 burst → EGW00201 → 500/502 반복
         *     * 10s 는 frontend 5s polling 중 2번에 1번만 cache miss → KIS 호출 빈도 절반
         *     * 신선도 손실은 사용자 toggle 시 invalidateQueries 가 즉시 refetch trigger 로 흡수
         */
        val CACHE_TTL: Duration = Duration.ofSeconds(10)
        private val RATE_LIMIT_FAILURE_TTL: Duration = Duration.ofSeconds(2)
    }

    private data class LoadFailure(
        val at: Instant,
        val exception: RuntimeException,
    )

    /**
     * 잔고 응답 — output2 11 필드.
     */
    fun getBalance(
        userId: String,
        accountType: AccountType,
        refresh: Boolean = false,
    ): AccountBalanceResponse {
        val snapshot = fetchOrLoad(userId, accountType, refresh)
        return AccountBalanceResponse.from(snapshot.balance, toIso(snapshot.fetchedAt))
    }

    /**
     * 보유 종목 응답 — output1 향상판 리스트.
     */
    fun getPositions(
        userId: String,
        accountType: AccountType,
        refresh: Boolean = false,
    ): List<AccountHoldingResponse> {
        val snapshot = fetchOrLoad(userId, accountType, refresh)
        return snapshot.holdings.map { AccountHoldingResponse.from(it) }
    }

    /**
     * 계좌 요약 응답 — Dashboard 헤더용 (5 필드 + fetchedAt).
     *
     * 수익률 = totalProfitLoss / totalPurchaseAmount × 100 (매입금액 0 시 0 반환).
     */
    fun getSummary(
        userId: String,
        accountType: AccountType,
        refresh: Boolean = false,
    ): AccountSummaryResponse {
        val snapshot = fetchOrLoad(userId, accountType, refresh)
        val balance = snapshot.balance
        val rate =
            if (balance.totalPurchaseAmount.signum() == 0) {
                BigDecimal.ZERO
            } else {
                balance.totalProfitLoss
                    .divide(balance.totalPurchaseAmount, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100))
                    .setScale(2, RoundingMode.HALF_UP)
            }
        return AccountSummaryResponse(
            totalEvaluation = balance.totalEvaluation,
            cashD2 = balance.cashD2,
            totalProfitLoss = balance.totalProfitLoss,
            profitLossRate = rate,
            holdingsCount = snapshot.holdings.size,
            fetchedAt = toIso(snapshot.fetchedAt),
        )
    }

    // ── private helper ─────────────────────────────────────────────────────────

    /**
     * Redis cache → KIS 호출 흐름 (1 endpoint 호출로 3 응답 모두 매핑).
     *
     * 헌법 §6 함정 회피: Repository 외부 service의 helper이므로 query inference 무관.
     */
    private fun fetchOrLoad(
        userId: String,
        accountType: AccountType,
        refresh: Boolean,
    ): KisAccountBalance {
        // hotfix 2026-05-20 — cache key 에 accountType 포함 (REAL/MOCK 분리 의무).
        // 사용자 dogfooding evidence: 기존 "$prefix:$userId" 단일 key → REAL/MOCK fetch 시 cache 충돌
        //   → cache miss 폭주 → KIS 호출 burst → EGW00201 "초당 거래건수 초과" 지속 발생.
        // fix: "$prefix:$userId:${accountType}" 분리 → REAL/MOCK 독립 cache → 충돌 해소.
        val cacheKey = "$CACHE_KEY_PREFIX:$userId:${accountType.name}"

        // 1. Redis cache hit
        val cached = if (refresh) null else redisTemplate.opsForValue().get(cacheKey)
        if (cached != null) {
            runCatching { objectMapper.readValue<KisAccountBalance>(cached) }
                .onSuccess { snapshot ->
                    log.debug("account cache HIT: userId={}", maskUserId(userId))
                    return snapshot
                }.onFailure { ex ->
                    log.warn(
                        "account cache 역직렬화 실패 — KIS 재조회: userId={} err={}",
                        maskUserId(userId),
                        ex.message,
                    )
                    // graceful: cache 오염 시 즉시 삭제 + KIS 재조회
                    redisTemplate.delete(cacheKey)
                }
        }

        if (refresh) {
            log.info("account 강제 새로고침 — Redis cache bypass: userId={} type={}", maskUserId(userId), accountType.name)
        }

        // 2. cache miss 또는 refresh=true → KIS REST 직접 호출
        val lock = loadLocks.computeIfAbsent(cacheKey) { Any() }
        return synchronized(lock) {
            if (!refresh) {
                val coalesced = redisTemplate.opsForValue().get(cacheKey)
                if (coalesced != null) {
                    runCatching { objectMapper.readValue<KisAccountBalance>(coalesced) }
                        .onSuccess { snapshot ->
                            log.debug("account cache HIT after single-flight wait: userId={}", maskUserId(userId))
                            return@synchronized snapshot
                        }.onFailure { ex ->
                            log.warn(
                                "account cache 역직렬화 실패(single-flight 재확인) — KIS 재조회: userId={} err={}",
                                maskUserId(userId),
                                ex.message,
                            )
                            redisTemplate.delete(cacheKey)
                        }
                }
            }

            recentRateLimitFailure(cacheKey)?.let { failure ->
                log.warn(
                    "account KIS rate-limit single-flight 실패 공유: userId={} type={}",
                    maskUserId(userId),
                    accountType.name,
                )
                throw failure
            }

            try {
                loadFromKisAndCache(userId, accountType, cacheKey)
                    .also { rateLimitFailures.remove(cacheKey) }
            } catch (ex: RuntimeException) {
                if (isRateLimitFailure(ex)) {
                    rateLimitFailures[cacheKey] = LoadFailure(Instant.now(), ex)
                } else {
                    rateLimitFailures.remove(cacheKey)
                }
                throw ex
            }
        }
    }

    private fun recentRateLimitFailure(cacheKey: String): RuntimeException? {
        val failure = rateLimitFailures[cacheKey] ?: return null
        if (Duration.between(failure.at, Instant.now()) <= RATE_LIMIT_FAILURE_TTL) {
            return failure.exception
        }
        rateLimitFailures.remove(cacheKey, failure)
        return null
    }

    private fun isRateLimitFailure(ex: RuntimeException): Boolean =
        ex is KisRateLimitException ||
            (
                ex is WebClientResponseException &&
                    classifyKisError(ex.statusCode.value(), ex.responseBodyAsString) is KisErrorClass.RateLimit
            )

    private fun loadFromKisAndCache(
        userId: String,
        accountType: AccountType,
        cacheKey: String,
    ): KisAccountBalance {
        val snapshot = kisBalanceQueryPort.fetchFullBalance(userId, accountType)
        log.info(
            "account KIS 잔고조회 성공: userId={} type={} holdings={}건 cash={} netAsset={}",
            maskUserId(userId),
            accountType.name,
            snapshot.holdings.size,
            snapshot.balance.cashBalance,
            snapshot.balance.netAssetValue,
        )

        // 3. cache write (TTL 30s)
        runCatching {
            val json = objectMapper.writeValueAsString(snapshot)
            redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL)
        }.onFailure { ex ->
            // cache write 실패는 graceful — 응답은 정상 반환
            log.warn("account cache write 실패: userId={} err={}", maskUserId(userId), ex.message)
        }
        return snapshot
    }

    private fun toIso(at: Instant): String = DateTimeFormatter.ISO_INSTANT.format(at)

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
