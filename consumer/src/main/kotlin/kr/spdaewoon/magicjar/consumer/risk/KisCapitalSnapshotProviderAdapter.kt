package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * KIS 잔고 기반 [CapitalSnapshotProvider] 실제 어댑터 — 주문경로 자본 산정 (ADR 0081 §2-5 + ADR 0116 R1-3).
 *
 * ### 책임 — 주문경로 전용 fresh 조회 (ADR 0116 R1-3 자본캐시 제거)
 * 본 provider의 유일한 호출처는 [SignalGeneratedKafkaListener.buildContext]의 `currentCapital` —
 * **주문 수량/한도(POSITION_LIMIT 예산·DAILY_LOSS_LIMIT) 산정 경로**다. stale 잔고는 오버주문 위험이므로
 * (사용자 지시 — 주문 관련 캐시 제거) Redis cache를 **우회하고 KIS [KisBalanceQueryPort]로 직접 조회**한다.
 * `KisAccountBalance.balance.netAssetValue` (순자산금액 = `nass_amt`) 추출 → BigDecimal 반환.
 *
 * ### 캐시 제거 근거 (ADR 0116 R1-3)
 * - **이전**: Redis cache `account:balance:{userId}:{REAL|MOCK}` 우선 read (TTL 60s) → miss 시 KIS fallback + cache write.
 *   주문 직전 자본이 최대 60s stale → 직전 체결로 줄어든 현금을 모르고 오버주문 risk.
 * - **변경**: cache read/write **0** — 매 주문 평가마다 KIS fresh 조회. 표시용 캐시([AccountQueryService])와 완전 분리.
 * - **KIS 부하 보호**: 직접 조회는 [KisBalanceQueryPort] 구현([ConsumerKisBalanceQueryAdapter])이 **USER_PATH priority**로
 *   rate limiter acquire → ADR 0116 §2-B의 USER 버킷(4)이 주문경로 자본조회를 보호 (BG burst에 안 굶음).
 *
 * ### 모듈 경계 정합 (ADR 0004 LOCK)
 * - consumer → domain (`CapitalSnapshotProvider` port + `KisBalanceQueryPort` port) ✅
 * - consumer → api 의존 0 LOCK ✅
 *
 * ### 표시용 캐시 분리 (ADR 0116 R1-3)
 * api `AccountQueryService`(표시용 — `/balance`·`/positions`·`/summary`)는 **캐시 유지** (TTL 10s).
 * 본 주문경로 provider만 캐시 우회. 두 경로의 신선도 요구가 다름 (표시=즉시성 OK / 주문=오버주문 방지 fresh 필수).
 *
 * ### fail-safe 정책 (ADR 0081 §2-5 + 자본 보호 LOCK — 보존)
 * KIS 직접 조회 실패 케이스는 `BigDecimal.ZERO` 반환 → POSITION_LIMIT 룰 동작:
 * 1. **fallback bean 부재** → 0 반환
 * 2. **KIS 조회 실패** (rate limit·upstream·credential) → 0 반환 (graceful)
 *
 * **자본 보호 evidence** (RiskGatewayEvaluator 검증):
 * - `currentCapital == 0` → `percentBudget = 0` → `budget = min(absoluteLimit, 0) = 0`
 * - 모든 BUY signal `unitCost > 0 > budget = 0` → POSITION_LIMIT BLOCKED
 * - 결과: 자본 조회 결함 시 모든 매수 차단 — 자본 보호 fail-safe 정합 ✅ (캐시 제거로도 불변)
 *
 * **revert graceful 정합** (ADR 0067 §7-1):
 * - default no-op bean (`CapitalSnapshotProviderAdapter.defaultCapitalSnapshotProvider`)이
 *   `@ConditionalOnMissingBean(CapitalSnapshotProvider::class)` 보유 → 본 `@Component` 등록 시 자동 비활성
 *
 * ### mock 합성 0 LOCK (feedback_no_mock_data)
 * 조회 실패 시 0 반환 — fixture 합성 0. 명시적 자본 보호.
 *
 * ### AI 호출 0 LOCK (룰 5 + ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 * LLM provider import 0 — 룰 기반 자본 조회 전용.
 */
@Component
class KisCapitalSnapshotProviderAdapter(
    private val balanceQueryPortProvider: ObjectProvider<KisBalanceQueryPort>? = null,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
) : CapitalSnapshotProvider {
    private val log = LoggerFactory.getLogger(KisCapitalSnapshotProviderAdapter::class.java)

    /**
     * 사용자 자본 (순자산금액) 조회 — KIS 직접 fresh 조회 (캐시 우회 · ADR 0116 R1-3).
     *
     * 주문경로 전용 — stale 잔고 오버주문 방지 위해 매 평가마다 KIS 조회.
     * KIS 호출은 [KisBalanceQueryPort] 구현이 USER_PATH priority로 acquire (USER 버킷 보호).
     *
     * @return `KisAccountBalance.balance.netAssetValue` 또는 fail-safe `BigDecimal.ZERO`
     */
    override fun getCapital(userId: String): BigDecimal {
        val port = balanceQueryPortProvider?.getIfAvailable()
        if (port == null) {
            log.warn(
                "KisCapitalSnapshotProvider KIS 조회 bean 부재 — fail-safe 0 (userId={})",
                maskUserId(userId),
            )
            return BigDecimal.ZERO
        }

        val accountType = RuntimeModeParser.accountTypeOrMock(magicjarMode)
        return try {
            // 캐시 우회 — 주문 수량/한도 산정에 stale 잔고 금지 (ADR 0116 R1-3). USER_PATH acquire는 port 구현이 보장.
            val snapshot = port.fetchFullBalance(userId, accountType)
            val netAsset = snapshot.balance.netAssetValue
            log.info(
                "KisCapitalSnapshotProvider KIS fresh 조회 — netAsset={} (userId={} type={}, 캐시 우회 — 주문경로)",
                netAsset,
                maskUserId(userId),
                accountType.name,
            )
            netAsset
        } catch (e: Exception) {
            log.warn(
                "KisCapitalSnapshotProvider KIS 직접 조회 실패 — fail-safe 0 (userId={} err={})",
                maskUserId(userId),
                e.message,
            )
            BigDecimal.ZERO
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
