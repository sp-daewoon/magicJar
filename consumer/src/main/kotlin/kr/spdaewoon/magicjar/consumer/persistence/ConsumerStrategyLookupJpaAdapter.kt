package kr.spdaewoon.magicjar.consumer.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.autopilot.MagicjarAutopilotProperties
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.StrategyOrigin
import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * Consumer-side StrategyLookupPort implementation.
 *
 * api 모듈 StrategyLookupJpaAdapter는 consumer classpath에 없으므로, 실시간 자동매매 JVM에서 같은 테이블을
 * 직접 조회하고 dsl_entry/dsl_exit 텍스트를 AST로 재파싱한다.
 */
@Component
class ConsumerStrategyLookupJpaAdapter(
    private val strategyJpaRepository: ConsumerStrategyJpaRepository,
    private val objectMapper: ObjectMapper,
    private val kisYamlDslParser: KisYamlDslParser,
    // magicjar-autopilot Phase 1 wiring (spec 2026-06-22 §2) — BUY 구동 평가 owner 집합을 enabled 에 연동.
    private val autopilotProperties: MagicjarAutopilotProperties,
) : StrategyLookupPort {
    private val log = LoggerFactory.getLogger(ConsumerStrategyLookupJpaAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findActiveByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Strategy> {
        val entities =
            strategyJpaRepository.findActiveByCodeAndInterval(
                // enabled=false → ['USER'](AUTOPILOT 제외·byte 동일) / true → ['USER','AUTOPILOT']. spec §2 BUY 구동 게이트.
                owners = autopilotProperties.buyEvalOwners(),
                codeJson = objectMapper.writeValueAsString(listOf(code)),
                intervalJson = objectMapper.writeValueAsString(listOf(interval.code)),
            )
        return entities.mapNotNull { toDomainOrNull(it) }
    }

    /**
     * ADR 0114 §4-2 P2 — 청산 평가 대상: ACTIVE/BUY_BLOCKED ∪ position-driven 폴백.
     *
     * ## BUY/청산 비대칭 (magicjar-autopilot — spec 2026-06-22 §2)
     * - `active`([findActiveByCodeAndInterval]) = **BUY 구동분** → owner 집합이 enabled 에 연동(false면 AUTOPILOT 제외).
     * - `positionHolding`([findDisabledWithPositionByCodeAndInterval]) = **청산 폴백** → enabled 무관,
     *   USER DISABLED-with-position + **AUTOPILOT status 무관 with-position**. enabled=false 로 active 에서 빠진
     *   ACTIVE AUTOPILOT 보유분도 여기서 청산 평가에 다시 합류한다(보유분 청산 항상 보장 — kill/rollback 정합).
     */
    @Transactional(readOnly = true)
    override fun findSellEvalByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Strategy> {
        val codeJson = objectMapper.writeValueAsString(listOf(code))
        val intervalJson = objectMapper.writeValueAsString(listOf(interval.code))
        val active =
            strategyJpaRepository.findActiveByCodeAndInterval(autopilotProperties.buyEvalOwners(), codeJson, intervalJson)
        val positionHolding = strategyJpaRepository.findDisabledWithPositionByCodeAndInterval(codeJson, intervalJson)
        // id 기준 dedup(합집합 — position-driven 폴백이 추가분). DELETED는 native 쿼리에서 이미 제외.
        return (active + positionHolding)
            .distinctBy { it.id }
            .mapNotNull { toDomainOrNull(it) }
    }

    private fun toDomainOrNull(entity: ConsumerStrategyJpaEntity): Strategy? {
        val buy = parseDslOrNull(entity.id, "buy", entity.dslEntry)
        if (buy == null) {
            log.warn("평가 가능 strategy 매수 DSL 파싱 실패로 realtime 평가 제외: strategyId={}", entity.id)
            return null
        }
        val sell = entity.dslExit?.let { parseDslOrNull(entity.id, "sell", it) }
        val dsl =
            DslStrategyDefinition(
                name = entity.name,
                nameKo = entity.nameKo ?: "",
                buyCondition = buy,
                sellCondition = sell,
                buyConditionText = entity.dslEntry,
                sellConditionText = entity.dslExit,
            )
        val definition =
            StrategyDefinition(
                dsl = dsl,
                universe = Universe(market = entity.universeMarket),
                tf = entity.tf,
                fixedStopLoss = entity.fixedStopLossPct,
                takeProfitPct = entity.takeProfitPct,
                // V96 ADR 0135 — trailing stop 영속 → 도메인 (TradePlanFactory 가 TradePlanTrailing 박제·TRAIL 청산 입력).
                //   ★ 이 매핑 누락 시 consumer hot path 에서 trailing 이 null 로 덮여 break-even 레버가 dead. null=비활성(현행).
                trailingStop = entity.trailingStopPct,
                // V73 잔익절 방어 B안 — minHoldBars(진입 후 N캔들 DSL 매도 무시). null=비활성(현행).
                minHoldBars = entity.minHoldBars,
                // V94 ADR 0129 레버 1 — 오버나잇 토글. default true(현행 거동). false + owner=AUTOPILOT 이면
                //   EOD 청산·late-entry 게이트 대상(USER/SYSTEM 은 owner 격리로 미적용). 매핑 LOCK(scale_in drift 교훈).
                allowOvernight = entity.allowOvernight,
                // V82 VI 행동 정책 — invalid row는 ALERT_ONLY(주문 0)로 흡수.
                viActionPolicy = safeViActionPolicy(entity.viActionPolicy),
                // ADR 0114 진입 정책 JSONB(V72) → 도메인 (BUY 가격 산정 입력). 역직렬화 실패 시 null = 전역 default.
                entryPolicy = deserializeEntryPolicy(entity.entryPolicy),
                // ADR 0111 분할매수(scale_in_config)·분할익절(partial_exits) JSONB(V65) → 도메인 wiring.
                //   2026-06-18 P0 재수정 — 이 매핑 누락이 consumer resolveBuyQuantity 분할 분기를 항상 건너뛰게 해
                //   라이브 분할매수를 전량 매수로 만들었다. 미설정 행은 null 유지(현행 단일 진입·전량 익절 — zero-regression).
                scaleIn = deserializeScaleIn(entity.scaleInConfig),
                partialExits = deserializePartialExits(entity.partialExits),
            )
        val complexity =
            StrategyComplexity(
                astDepth = entity.astDepth,
                nodeCount = entity.nodeCount,
                indicatorCallCount = entity.indicatorCallCount,
                estimatedEvalTimeMs = entity.estimatedEvalTimeMs,
                complexityClass = safeComplexityClass(entity.complexityClass),
            )

        @Suppress("UNCHECKED_CAST")
        val evalMetricSummary =
            entity.evalMetricSummary?.let {
                runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any> }.getOrNull()
            }

        return Strategy(
            id = entity.id,
            userId = entity.userId,
            definition = definition,
            complexity = complexity,
            disabledReason = safeDisabledReason(entity.disabledReason),
            origin = safeOrigin(entity.origin),
            // owner(2026-06-22 P0 root fix) — 엔티티 owner 컬럼 매핑 누락으로 default USER 로 떨어지던 것을 복원.
            //   autopilot Guard 가 signal.strategyOwner 로 식별하는 단일 출처가 이 매핑이다(누락 시 Guard 영구 우회).
            owner = safeOwner(entity.owner),
            status = safeStatus(entity.status),
            codes = safeStringList(entity.codes),
            intervals = safeStringList(entity.intervals),
            lastDisabledAt = entity.lastDisabledAt,
            evalMetricSummary = evalMetricSummary,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    private fun parseDslOrNull(
        strategyId: Long?,
        side: String,
        text: String,
    ): DslNode? {
        if (text.isBlank()) return null
        return runCatching { kisYamlDslParser.parse(text) }
            .onFailure { e ->
                log.warn("consumer strategy DSL parse 실패: strategyId={} side={} err={}", strategyId, side, e.message)
            }.getOrNull()
    }

    private fun safeComplexityClass(value: String): ComplexityClass =
        runCatching { ComplexityClass.valueOf(value) }.getOrDefault(ComplexityClass.LOW)

    private fun safeDisabledReason(value: String?): StrategyDisabledReason =
        if (value == null) {
            StrategyDisabledReason.NULL
        } else {
            runCatching { StrategyDisabledReason.valueOf(value) }.getOrDefault(StrategyDisabledReason.NULL)
        }

    private fun safeStatus(value: String): StrategyStatus =
        runCatching { StrategyStatus.valueOf(value) }.getOrDefault(StrategyStatus.ACTIVE)

    private fun safeViActionPolicy(value: String?): ViActionPolicy =
        value
            ?.trim()
            ?.uppercase()
            ?.let { runCatching { ViActionPolicy.valueOf(it) }.getOrNull() }
            ?: ViActionPolicy.ALERT_ONLY

    /**
     * `origin` JSONB 없는 VARCHAR(V81) → enum (ADR 0122 §2-A · consumer capital 분기 판정 키).
     * null(legacy)·invalid → [StrategyOrigin.USER_DSL] default(실 KIS 잔고 경로 — zero-regression).
     */
    private fun safeOrigin(value: String?): StrategyOrigin =
        if (value == null) {
            StrategyOrigin.USER_DSL
        } else {
            runCatching { StrategyOrigin.valueOf(value) }.getOrDefault(StrategyOrigin.USER_DSL)
        }

    /**
     * `owner`(V88) → [StrategyOwner]. null(legacy)·invalid → USER default(zero-regression).
     * autopilot Guard 의 owner=AUTOPILOT 식별 단일 출처 — 이 매핑이 빠지면 Guard 가 우회된다(2026-06-22 P0).
     */
    private fun safeOwner(value: String?): StrategyOwner =
        if (value == null) {
            StrategyOwner.USER
        } else {
            runCatching { StrategyOwner.valueOf(value) }.getOrDefault(StrategyOwner.USER)
        }

    /**
     * `entry_policy` JSONB(V72) → [kr.spdaewoon.magicjar.domain.strategy.EntryPolicy] (ADR 0114).
     * [kr.spdaewoon.magicjar.domain.strategy.EntryPolicySerde] 단일 codec 위임 — JSON String → Map 변환만 담당.
     * 역직렬화·범위 검증 실패 시 null(전역 default MARKET 진입 — 실시간 hot path down 회피).
     */
    private fun deserializeEntryPolicy(value: String?): kr.spdaewoon.magicjar.domain.strategy.EntryPolicy? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
            kr.spdaewoon.magicjar.domain.strategy.EntryPolicySerde
                .fromMap(map)
        }.getOrNull()
    }

    /**
     * `scale_in_config` JSONB(V65) → [ScaleInPlan] (ADR 0111 분할매수). api [StrategyEntityMapper] 정합.
     *
     * 도메인([ScaleInPlan])은 jackson 어노테이션 없는 평탄 데이터 클래스이므로 readTree로 raw 파싱 후 생성한다
     * (도메인은 Spring·jackson import 금지 — 헥사고날 정합). init 검증·역직렬화 실패 시 null 흡수(invalid DB row가
     * 실시간 hot path 평가 전체를 down 시키지 않도록 — `safe*` 패턴 정합). 미설정 행은 null → 단일 진입(zero-regression).
     */
    private fun deserializeScaleIn(value: String?): ScaleInPlan? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val node = objectMapper.readTree(value)
            if (!node.isObject) return null
            val totalSlices = node.get("totalSlices")?.takeIf { it.isNumber }?.asInt() ?: return null
            val sliceWeights =
                node
                    .get("sliceWeights")
                    ?.takeIf { it.isArray }
                    ?.mapNotNull { it?.let { w -> BigDecimal(w.asText()) } }
                    ?.takeIf { it.isNotEmpty() }
            val reentryTrigger =
                node
                    .get("reentryTrigger")
                    ?.asText()
                    ?.let { runCatching { ReentryTrigger.valueOf(it) }.getOrNull() }
                    ?: ReentryTrigger.SIGNAL_REPEAT
            val priceDropPct = node.get("priceDropPct")?.takeIf { !it.isNull }?.let { BigDecimal(it.asText()) }
            val minSliceIntervalSec = node.get("minSliceIntervalSec")?.takeIf { it.isNumber }?.asInt()
            // ADR 0128 — catastrophic floor 필드 복원(api StrategyEntityMapper 정합). write 측은 전 필드 직렬화하므로
            // consumer read 측도 동일 복원해야 floorAutoLiquidate 토글이 매매 평가(RealtimeSignalGenerator)에 전달된다
            //   — 누락 시 갭② 자동청산 wiring 이 영구 dead(항상 false). stopLossFloorPct 도 평가 floor 정합 위해 복원.
            val stopLossFloorPct = node.get("stopLossFloorPct")?.takeIf { !it.isNull }?.let { BigDecimal(it.asText()) }
            val floorAutoLiquidate = node.get("floorAutoLiquidate")?.takeIf { it.isBoolean }?.asBoolean() ?: false
            ScaleInPlan(
                totalSlices = totalSlices,
                sliceWeights = sliceWeights,
                reentryTrigger = reentryTrigger,
                priceDropPct = priceDropPct,
                minSliceIntervalSec = minSliceIntervalSec,
                stopLossFloorPct = stopLossFloorPct,
                floorAutoLiquidate = floorAutoLiquidate,
            )
        }.getOrNull()
    }

    /**
     * `partial_exits` JSONB(V65) → [List]<[PartialExit]> (ADR 0111 분할익절). api [StrategyEntityMapper] 정합.
     *
     * 단계당 [PartialExit.init] + [StrategyDefinition.init]의 cap·weight 합 검증을 통과해야 한다.
     * 역직렬화·검증 실패 시 null 흡수(`safe*` 패턴 정합). 미설정 행은 null → 전량 익절(zero-regression).
     */
    private fun deserializePartialExits(value: String?): List<PartialExit>? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val node = objectMapper.readTree(value)
            if (!node.isArray) return null
            node
                .mapNotNull { stage ->
                    if (stage == null || !stage.isObject) return@mapNotNull null
                    val trigger =
                        stage.get("triggerProfitPct")?.takeIf { !it.isNull }?.let { BigDecimal(it.asText()) } ?: return@mapNotNull null
                    val weight =
                        stage.get("exitWeightPct")?.takeIf { !it.isNull }?.let { BigDecimal(it.asText()) } ?: return@mapNotNull null
                    PartialExit(triggerProfitPct = trigger, exitWeightPct = weight)
                }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun safeStringList(value: String?): List<String> =
        value
            ?.let {
                runCatching {
                    objectMapper
                        .readValue(it, List::class.java)
                        .mapNotNull { item ->
                            item?.toString()?.takeIf(String::isNotBlank)
                        }
                }.getOrNull()
            }
            ?: emptyList()
}
