package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisBalanceRateLimitedException
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.risk.port.CapitalOutcome
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * KIS 잔고 기반 [CapitalSnapshotProvider] 실제 어댑터 — 주문경로 자본 산정
 * (ADR 0081 §2-5 + ADR 0116 R1-3 + **F10 hotfix 2026-06-12**).
 *
 * ### 책임 — 주문경로 전용 fresh 조회 (ADR 0116 R1-3 자본캐시 제거)
 * 본 provider의 유일한 호출처는 [SignalGeneratedKafkaListener.buildContext]의 `currentCapital` —
 * **주문 수량/한도(POSITION_LIMIT 예산·DAILY_LOSS_LIMIT) 산정 경로**다. stale 잔고는 오버주문 위험이므로
 * (사용자 지시 — 주문 관련 캐시 제거) Redis 표시용 cache를 **우회하고 KIS [KisBalanceQueryPort]로 직접 조회**한다.
 *
 * ### F10 — 조회 실패 시 0 fabricate 폐기 → 재시도 → stale-cache → skip (2026-06-12 최고 관리자 결재)
 * 14:12 라이브 incident: 잔고조회가 rate limit(EGW00215)로 실패하자 **capital=0 을 fabricate** →
 * "총자산 0원 × 70% = 한도 0원"으로 POSITION_LIMIT 거짓 차단 + AutoPause 오염 → 실계좌 자산이 있는데도
 * 정상 매수 기회 상실. 결재 지침에 따라 어떤 실패에서도 0 을 만들지 않고 다음 3단으로 처리한다:
 *
 * 1. **재시도** — rate limit 류는 하부 [KisBalanceQueryPort] 구현이 backoff 재시도(default 2회, 1s+ 간격).
 *    소진 시 [KisBalanceRateLimitedException] 으로 드러난다.
 * 2. **최근 성공 stale-cache** — 재시도도 실패하면 유효 TTL(default 5분) 내 최근 성공 snapshot 을
 *    `stale=true` 로 재사용([CapitalOutcome.Available]). "0" 보다 "직전 실제 잔고"가 훨씬 사실에 가깝다.
 * 3. **그래도 없으면 skip** — TTL 초과·캐시 없음이면 [CapitalOutcome.Unavailable] 반환 → 호출부가
 *    이번 시그널만 조용히 audit(skip) 종결(POSITION_LIMIT 위반·Slack·AutoPause 전부 제외).
 *
 * 성공 조회 때마다 capital snapshot 을 in-memory 로 갱신해 다음 실패 시 재료로 쓴다(주문 직전 stale 우려는
 * "장중 정상 조회는 fresh, 실패 N분만 직전값" 이므로 0-fabricate 보다 안전 — 결재 §2 명시).
 *
 * ### 모듈 경계 정합 (ADR 0004 LOCK)
 * - consumer → domain (`CapitalSnapshotProvider`·`CapitalOutcome`·`KisBalanceQueryPort` port) ✅
 * - consumer → api 의존 0 LOCK ✅
 *
 * ### 진보 페르소나 (ADR 0087)
 * - ✨ [CapitalOutcome] sealed 로 "값 있음 / 조회 불가" 를 타입 분리 — 0 fabricate 경로를 컴파일에서 제거.
 * - 🔄 재시도는 하부 adapter(DRY — `ConsumerKisBalanceQueryAdapter` 단일 retry 로직 재사용), 본 provider 는
 *   stale-cache·skip 정책만 책임(SRP).
 *
 * ### mock 합성 0 LOCK (feedback_no_mock_data)
 * 실패 시 mock 합성 0 금지 — Unavailable(skip) 또는 직전 실제 성공값(stale)만.
 *
 * ### AI 호출 0 LOCK (룰 5 + ADR 0023 §2-4)
 */
@Component
class KisCapitalSnapshotProviderAdapter(
    private val balanceQueryPortProvider: ObjectProvider<KisBalanceQueryPort>? = null,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
    // F10 — 최근 성공 capital snapshot 유효 시간(분). 이 시간 내면 조회 실패 시 직전 성공값(stale)을 재사용.
    @Value("\${magicjar.risk.capital-stale-cache-ttl-minutes:5}")
    private val staleCacheTtlMinutes: Long = 5,
    private val clock: () -> Instant = Instant::now,
) : CapitalSnapshotProvider {
    private val log = LoggerFactory.getLogger(KisCapitalSnapshotProviderAdapter::class.java)

    /** key=userId → 최근 성공 capital snapshot(값 + 시각). 같은 partition(userId) 단일 consumer thread 처리 — in-memory 충분. */
    private val recentCapital = ConcurrentHashMap<String, CapitalSnapshot>()

    private data class CapitalSnapshot(
        val capital: BigDecimal,
        val at: Instant,
    )

    /**
     * 레거시 시그니처 — 하위호환 위임. 주문경로는 [capitalOutcome] 을 직접 쓴다.
     * Unavailable 은 레거시 의미(값 없음)대로 0 환원하되, **주문경로 호출부는 이 메서드를 쓰지 않는다**.
     */
    override fun getCapital(userId: String): BigDecimal =
        when (val outcome = capitalOutcome(userId)) {
            is CapitalOutcome.Available -> outcome.capital
            is CapitalOutcome.Unavailable -> BigDecimal.ZERO
        }

    /**
     * 자본 산출(F10 권위 진입점) — fresh 조회 → (실패 시) stale-cache → (없으면) Unavailable.
     *
     * **0 fabricate 절대 금지**: 어떤 실패에서도 [CapitalOutcome.Available] 0 을 만들지 않는다. 실제 0 잔고는
     * KIS 가 성공적으로 0 을 반환한 경우에만 발생(그 경우만 정상 POSITION_LIMIT 평가 대상).
     */
    override fun capitalOutcome(userId: String): CapitalOutcome {
        val port = balanceQueryPortProvider?.getIfAvailable()
        if (port == null) {
            log.error(
                "KisCapitalSnapshotProvider KIS 조회 bean 부재 — capital 산출 불가(skip · 0 fabricate 금지) userId={}",
                maskUserId(userId),
            )
            return staleCacheOr(userId, reason = "PROVIDER_UNAVAILABLE")
        }

        val accountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        return try {
            // 캐시 우회 — 주문 수량/한도 산정에 stale 잔고 금지(ADR 0116 R1-3). USER_PATH acquire 는 port 구현이 보장.
            // rate limit backoff 재시도(default 2회)도 port 구현이 캡슐화 — 소진 시 KisBalanceRateLimitedException.
            val snapshot = port.fetchFullBalance(userId, accountType)
            val netAsset = snapshot.balance.netAssetValue
            recentCapital[userId] = CapitalSnapshot(netAsset, clock())
            log.info(
                "KisCapitalSnapshotProvider KIS fresh 조회 — netAsset={} (userId={} type={}, 캐시 우회 — 주문경로)",
                netAsset,
                maskUserId(userId),
                accountType.name,
            )
            CapitalOutcome.Available(capital = netAsset, stale = false)
        } catch (e: KisBalanceRateLimitedException) {
            // 재시도까지 모두 소진된 rate limit — 0 fabricate 금지. stale-cache → skip.
            log.warn(
                "KisCapitalSnapshotProvider 잔고 rate limit 재시도 소진 — stale-cache 시도(userId={})",
                maskUserId(userId),
            )
            staleCacheOr(userId, reason = "KIS_RATE_LIMIT")
        } catch (e: Exception) {
            log.warn(
                "KisCapitalSnapshotProvider KIS 직접 조회 실패 — stale-cache 시도(userId={} err={})",
                maskUserId(userId),
                e.message,
            )
            staleCacheOr(userId, reason = "KIS_LOOKUP_FAILED:${e.message ?: "UNKNOWN"}")
        }
    }

    /**
     * 조회 실패 fallback — 유효 TTL 내 최근 성공 snapshot 이 있으면 stale 로 재사용, 없으면 Unavailable(skip).
     * **0 fabricate 금지** — 어떤 경로도 capital=0 을 합성하지 않는다.
     */
    private fun staleCacheOr(
        userId: String,
        reason: String,
    ): CapitalOutcome {
        val cached = recentCapital[userId]
        if (cached != null) {
            val ageMinutes = Duration.between(cached.at, clock()).toMinutes()
            if (ageMinutes <= staleCacheTtlMinutes) {
                log.info(
                    "KisCapitalSnapshotProvider capital stale-cache 사용 — capital={} ageMin={} ttlMin={} reason={} (userId={})",
                    cached.capital,
                    ageMinutes,
                    staleCacheTtlMinutes,
                    reason,
                    maskUserId(userId),
                )
                return CapitalOutcome.Available(capital = cached.capital, stale = true)
            }
            log.warn(
                "KisCapitalSnapshotProvider stale-cache 만료(ageMin={} > ttlMin={}) — 시그널 skip reason={} (userId={})",
                ageMinutes,
                staleCacheTtlMinutes,
                reason,
                maskUserId(userId),
            )
        } else {
            log.warn(
                "KisCapitalSnapshotProvider stale-cache 없음 — 시그널 skip reason={} (userId={})",
                reason,
                maskUserId(userId),
            )
        }
        return CapitalOutcome.Unavailable(reason)
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
