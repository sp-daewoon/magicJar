package kr.spdaewoon.magicjar.consumer.signal

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMarketIndexDailyJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 진입 가드 ① — **시장 레짐 필터** (2026-07-08 즉효 3종, 최고 관리자 결재).
 *
 * ## 근거 (2026-07-08)
 * 105 전략 25왕복 10승15패에서 손실이 **DOWNTREND 레짐 3일에 집중(-4,175)** 됐다. 하락장에서 신규 진입을
 * 강행하면 룰 알파가 시장 역풍에 잠식된다. 본 게이트는 시장 레짐이 차단 레짐(default DOWNTREND)일 때 **신규 BUY
 * 시그널 발행만 skip** 한다. SELL/청산/손절/익절은 **절대 무영향**(fail-closed 불변식 — 보유분 회수는 막지 않음, 롱온리).
 *
 * ## 레짐 소스 (조사 후 선택)
 * 장중 라이브 추세 서비스는 **부재**하다: market-trend-engine 은 순수 math lib 로 api REST(TrendCalculator) 에서만
 * 호출되고, consumer 실시간 경로에서 지수를 장중 재분류하는 컴포넌트는 없다. 따라서 전 전략 공유 레짐 SoT 인
 * `market_index_daily.regime`(V86, batch EOD 롤업 00:30 KST)의 **최신 영업일값**을 폴백으로 쓴다 — 장중에는 사실상
 * "전일 EOD 레짐"이다. (`strategy_daily_performance.market_regime` 은 이 값을 전략×일자로 복제한 사영이므로 원천인
 * market_index_daily 를 직접 읽는 것이 중복 0·최신.) 라이브 분류기가 후속 도입되면 소스만 교체하면 된다(호출부 불변).
 *
 * ## 캐시 — 매 캔들 DB 부담 회피 (보수적 캐시)
 * 지수 코드별 레짐 판정을 짧은 TTL(default 60s)로 캐시한다. 레짐은 EOD 단위로만 바뀌므로 60s stale 은 무해하다.
 *
 * ## graceful — 조회 실패·regime null·bean 부재 시 skip 안 함(false)
 * repository bean 부재·조회 실패·regime 컬럼 null 이면 [isBuyBlockedByRegime] 는 false 를 반환한다(= 앞단 skip 안 함).
 * 즉 "차단 레짐임을 확신할 때만 막고, 모르면 진행"한다(정상 매매를 레짐 데이터 결함으로 막지 않는다).
 *
 * ## skip 로그 dedupe (스팸 방지)
 * 같은 (code, regime) 반복 skip 은 로그를 1회로 억제한다(캐시 TTL 주기로 재로그). 행동(skip 여부)은 그대로.
 *
 * // AI 호출 0 — 룰 기반 진입 가드 (ADR 0023 §2-4 정합)
 */
@Component
class RegimeEntryGate(
    private val marketIndexDailyRepositoryProvider: ObjectProvider<ConsumerMarketIndexDailyJpaRepository>,
    @Value("\${magicjar.strategy.entry-guard.regime.enabled:true}")
    private val enabled: Boolean = true,
    // 레짐 판정 기준 지수 — default 코스피(0001). 대형주 시장 전반 방향의 대표 지수.
    @Value("\${magicjar.strategy.entry-guard.regime.index-code:0001}")
    private val indexCode: String = "0001",
    // 신규 BUY 를 차단할 레짐 목록(콤마 구분). default DOWNTREND. HIGH_VOL 등 확장은 yml 로.
    @Value("\${magicjar.strategy.entry-guard.regime.block-regimes:DOWNTREND}")
    private val blockRegimesRaw: String = "DOWNTREND",
    @Value("\${magicjar.strategy.entry-guard.regime.cache-ttl-millis:60000}")
    private val cacheTtlMillis: Long = 60_000,
    private val clock: Clock = Clock.System,
) {
    private val log = LoggerFactory.getLogger(RegimeEntryGate::class.java)

    private val blockRegimes: Set<String> =
        blockRegimesRaw
            .split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    private data class CacheEntry(
        val blocked: Boolean,
        val regime: String?,
        val expiresAtEpochMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** 최근에 로그한 (code→regime) — 반복 skip 로그 dedupe. */
    private val lastLoggedRegime = ConcurrentHashMap<String, String>()

    /**
     * 현재 시장 레짐이 차단 레짐이라 신규 BUY 발행을 skip 해야 하는가.
     *
     * @param code 진입 대상 종목(로그·dedupe 용 — 레짐은 지수 단위 전역이라 판정 자체엔 미사용).
     * @return true = 차단 레짐(BUY skip) / false = 진행(비차단·데이터 결함·비활성).
     */
    fun isBuyBlockedByRegime(code: String): Boolean {
        if (!enabled || blockRegimes.isEmpty()) return false

        val nowMs = clock.now().toEpochMilliseconds()
        val entry =
            cache[indexCode]?.takeIf { it.expiresAtEpochMs > nowMs }
                ?: computeRegime(nowMs).also { cache[indexCode] = it }

        if (entry.blocked) {
            logSkipDeduped(code, entry.regime)
        }
        return entry.blocked
    }

    private fun computeRegime(nowMs: Long): CacheEntry {
        val repo =
            marketIndexDailyRepositoryProvider.getIfAvailable()
                ?: return CacheEntry(blocked = false, regime = null, expiresAtEpochMs = nowMs + cacheTtlMillis)

        val regime =
            try {
                repo
                    .findLatestByIndexCode(indexCode)
                    ?.regime
                    ?.trim()
                    ?.uppercase()
            } catch (e: Exception) {
                // 조회 실패 → skip 안 함(false). 정상 매매를 레짐 데이터 결함으로 막지 않는다(graceful).
                log.warn("RegimeEntryGate 레짐 조회 실패 — 앞단 skip 미적용(진행): indexCode={} err={}", indexCode, e.message)
                null
            }
        val blocked = regime != null && blockRegimes.contains(regime)
        return CacheEntry(blocked = blocked, regime = regime, expiresAtEpochMs = nowMs + cacheTtlMillis)
    }

    /** 같은 (code, regime) 반복 skip 은 로그 1회로 억제(캐시 TTL 주기로 재로그 자연 허용). */
    private fun logSkipDeduped(
        code: String,
        regime: String?,
    ) {
        val prev = lastLoggedRegime.put(code, regime ?: "")
        if (prev == (regime ?: "")) return
        log.info(
            "RegimeEntryGate BUY skip — 차단 레짐({}) 진입 억제(SELL/청산 무영향): code={} indexCode={} blockRegimes={}",
            regime,
            code,
            indexCode,
            blockRegimes,
        )
    }
}
