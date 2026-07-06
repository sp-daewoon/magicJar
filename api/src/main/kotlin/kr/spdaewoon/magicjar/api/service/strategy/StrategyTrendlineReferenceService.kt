package kr.spdaewoon.magicjar.api.service.strategy

import kr.spdaewoon.magicjar.api.persistence.strategy.ReferencingStrategyView
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyTrendlineRefJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyTrendlineRefJpaRepository
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.TrendlineRefs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 선-전략 참조 무결성 동기화 서비스 (ADR 0140 §3-3 · R2 backend-core).
 *
 * 전략 저장(생성·수정) 훅에서 진입·청산 AST 를 [TrendlineRefs.collectAll] 로 훑어 참조 추세선 id 집합을 얻고
 * `strategy_trendline_ref` 테이블을 그 집합과 일치하도록 동기화한다(add/remove diff). 선 삭제 가드
 * ([kr.spdaewoon.magicjar.api.service.trendline.TrendlineService.delete])가 이 테이블을 조회한다.
 *
 * ## 참조 행 정리 정책 (ADR 0140 §3-3 정합)
 * - **생성·수정** → [sync] 로 참조 집합 재계산(diff upsert). DSL 에서 선을 빼면 해당 행 제거.
 * - **soft delete(DELETED)** → [clearByStrategyId] 명시 정리(영구 삭제 의도).
 * - **hard delete** → FK ON DELETE CASCADE(strategy) 자동 정리(별도 호출 불요).
 * - **보관(ARCHIVED)** → 행 유지(가역). 삭제 가드 조회([findLiveReferencing])가 status 로 필터해 제외.
 *
 * 진보 페르소나(ADR 0087): id 추출은 domain 순수함수([TrendlineRefs]) 재사용(DRY). 서비스는 영속만 담당(SRP).
 */
@Service
class StrategyTrendlineReferenceService(
    private val refRepository: StrategyTrendlineRefJpaRepository,
) {
    private val log = LoggerFactory.getLogger(StrategyTrendlineReferenceService::class.java)

    /**
     * 전략 참조 집합을 AST 기준으로 동기화(diff upsert).
     *
     * @param strategyId 저장된 전략 id
     * @param entryAst 진입 조건 AST
     * @param exitAst 청산 조건 AST(nullable)
     */
    @Transactional
    fun sync(
        strategyId: Long,
        entryAst: DslNode,
        exitAst: DslNode?,
    ) {
        val desired = TrendlineRefs.collectAll(entryAst, exitAst)
        val current = refRepository.findByStrategyId(strategyId).map { it.trendlineId }.toSet()

        val toRemove = current - desired
        val toAdd = desired - current

        toRemove.forEach { refRepository.deleteByStrategyIdAndTrendlineId(strategyId, it) }
        toAdd.forEach { refRepository.save(StrategyTrendlineRefJpaEntity(strategyId = strategyId, trendlineId = it)) }

        if (toAdd.isNotEmpty() || toRemove.isNotEmpty()) {
            log.info(
                "전략 추세선 참조 동기화 — strategyId={} +{} -{} (총 {}개 참조)",
                strategyId,
                toAdd.size,
                toRemove.size,
                desired.size,
            )
        }
    }

    /** 전략 삭제(soft delete) 시 참조 행 정리. */
    @Transactional
    fun clearByStrategyId(strategyId: Long) {
        val removed = refRepository.deleteByStrategyId(strategyId)
        if (removed > 0) {
            log.info("전략 삭제로 추세선 참조 {}건 정리 — strategyId={}", removed, strategyId)
        }
    }

    /** 특정 추세선을 참조하는 살아있는 전략(삭제 가드·강제삭제 대상). */
    @Transactional(readOnly = true)
    fun findLiveReferencing(trendlineId: Long): List<ReferencingStrategyView> = refRepository.findLiveReferencingStrategies(trendlineId)

    /** 추세선 강제 삭제 시 참조 행 정리(선-전략 링크 제거). */
    @Transactional
    fun clearByTrendlineId(trendlineId: Long) {
        val removed = refRepository.deleteByTrendlineId(trendlineId)
        if (removed > 0) {
            log.info("추세선 강제 삭제로 참조 {}건 정리 — trendlineId={}", removed, trendlineId)
        }
    }
}
