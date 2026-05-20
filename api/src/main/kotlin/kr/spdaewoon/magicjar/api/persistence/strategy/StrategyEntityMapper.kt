package kr.spdaewoon.magicjar.api.persistence.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition

/**
 * Strategy JPA Entity ↔ Domain 변환 단일 책임 mapper.
 *
 * ### 의도 (P2-4 진보 페르소나 refactor — ADR 0087)
 * 좌우명: "레거시는 빚이다. 이자가 붙기 전에 갚아라"
 *
 * `StrategyJpaAdapter`·`StrategyLookupJpaAdapter` 양 어댑터가 동일한 `toDomain()` 약 42줄을
 * 각자 복제 보유했었음 (DRY 위반 — 한 쪽 schema 변경 시 양쪽 동기화 누락 risk).
 * Strategy entity 변환 책임을 단일 object로 추출하여 추가 어댑터(예: backtest·screener
 * read-only path)에서도 재사용 가능하도록 캡슐화.
 *
 * ### 헌법 §6 (ADR 0051) 정합
 * - Spring bean 0 (object 정적 singleton) — JPA Repository default fun 함정 영역 아님
 * - Repository interface 미터치 — `findActiveByCodeAndInterval` 등 query inference 무관
 *
 * ### 자본 path 안전성
 * - Pure function (DB·KIS·외부 IO 0건)
 * - exception은 caller에 전파 (ObjectMapper readValue 실패 등은 caller가 fallback 결정)
 * - 단, `safeComplexityClass`·`safeDisabledReason`은 enum 변환 실패를 default로 흡수
 *   (DB에 invalid enum row 존재 시 service 전체 down 회피 — 기존 어댑터 정합)
 *
 * AI 호출 0 — refactor only
 */
object StrategyEntityMapper {
    /**
     * Entity → Domain 변환.
     *
     * @param entity JPA entity (영속 직후 또는 조회 결과)
     * @param objectMapper `eval_metric_summary` JSONB 역직렬화용
     */
    fun toDomain(
        entity: StrategyJpaEntity,
        objectMapper: ObjectMapper,
        buyCondition: DslNode? = null,
        sellCondition: DslNode? = null,
    ): Strategy {
        val dsl =
            DslStrategyDefinition(
                name = entity.name,
                nameKo = entity.nameKo ?: "",
                buyCondition = buyCondition,
                sellCondition = sellCondition,
                buyConditionText = entity.dslEntry,
                sellConditionText = entity.dslExit,
            )
        val definition =
            StrategyDefinition(
                dsl = dsl,
                universe = Universe(market = entity.universeMarket),
                tf = entity.tf,
            )
        val complexity =
            StrategyComplexity(
                astDepth = entity.astDepth,
                nodeCount = entity.nodeCount,
                indicatorCallCount = entity.indicatorCallCount,
                estimatedEvalTimeMs = entity.estimatedEvalTimeMs,
                complexityClass = safeComplexityClass(entity.complexityClass),
            )
        val disabledReason = safeDisabledReason(entity.disabledReason)

        @Suppress("UNCHECKED_CAST")
        val evalMetricSummary: Map<String, Any>? =
            entity.evalMetricSummary?.let {
                runCatching { objectMapper.readValue(it, Map::class.java) as Map<String, Any> }.getOrNull()
            }

        return Strategy(
            id = entity.id,
            userId = entity.userId,
            definition = definition,
            complexity = complexity,
            disabledReason = disabledReason,
            lastDisabledAt = entity.lastDisabledAt,
            evalMetricSummary = evalMetricSummary,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    /**
     * `complexity_class` 문자열 → enum.
     *
     * DB에 invalid 값(미래 enum 추가 후 rollback 등) 존재 시 [ComplexityClass.LOW] default.
     * service 전체 down 회피 — 기존 어댑터 정합 (P2-4 회귀 0 LOCK).
     */
    fun safeComplexityClass(value: String): ComplexityClass =
        runCatching { ComplexityClass.valueOf(value) }.getOrDefault(ComplexityClass.LOW)

    /**
     * `disabled_reason` 문자열(nullable) → enum.
     *
     * null 또는 invalid 값은 [StrategyDisabledReason.NULL] default.
     */
    fun safeDisabledReason(value: String?): StrategyDisabledReason =
        if (value == null) {
            StrategyDisabledReason.NULL
        } else {
            runCatching { StrategyDisabledReason.valueOf(value) }.getOrDefault(StrategyDisabledReason.NULL)
        }
}
