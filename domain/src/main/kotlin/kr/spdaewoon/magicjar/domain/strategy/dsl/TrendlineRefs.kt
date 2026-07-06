package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * DSL AST 에서 [TrendlineRef] id 를 추출하는 순수함수 (ADR 0140 §3-3 — 참조 무결성).
 *
 * 전략 저장/수정 시 이 함수로 진입·청산 AST 를 훑어 참조하는 추세선 id 집합을 얻어
 * `strategy_trendline_ref` 참조 테이블을 동기화한다(R2 backend-core). 선 삭제 가드·강제삭제 PAUSED 가
 * 이 테이블을 조회한다.
 *
 * ### 왜 domain 순수함수인가
 * - Spring·JPA 의존 0 — 파싱 결과 AST 만 입력. 백테스트 lookahead 경고(R3)·참조 동기화(R2)가 재사용.
 * - `TrendlineRef` 노드 신설([ADR 0140] §2 대안 A 채택)로 id 추출이 안전하다 —
 *   IndicatorRef 함수 인자 파싱 오염(대안 B) 없이 전용 노드만 수집.
 *
 * ### 헌법 §6 무관
 * Repository 아님 — object 순수함수. body 있는 top-level 함수로 격리(테스트 용이).
 */
object TrendlineRefs {
    /**
     * [node] 하위 트리에 등장하는 모든 [TrendlineRef.trendlineId] 를 중복 제거해 반환한다.
     *
     * @param node AST root(진입 또는 청산). null 이면 빈 집합(청산 조건 미설정 전략 정합).
     * @return 참조 추세선 id 집합(순서 무관·중복 0).
     */
    fun collect(node: DslNode?): Set<Long> {
        if (node == null) return emptySet()
        val acc = LinkedHashSet<Long>()
        walk(node, acc)
        return acc
    }

    /**
     * 진입 + 청산 AST 를 함께 수집(전략당 참조 집합 = entry ∪ exit).
     */
    fun collectAll(
        entry: DslNode?,
        exit: DslNode?,
    ): Set<Long> = collect(entry) + collect(exit)

    private fun walk(
        node: DslNode,
        acc: MutableSet<Long>,
    ) {
        when (node) {
            is TrendlineRef -> acc.add(node.trendlineId)
            is Condition -> {
                walk(node.left, acc)
                walk(node.right, acc)
            }
            is CompositeCondition -> {
                walk(node.left, acc)
                walk(node.right, acc)
            }
            is ArithmeticExpr -> {
                walk(node.left, acc)
                walk(node.right, acc)
            }
            is IndicatorRef, is LiteralValue -> Unit // leaf — 참조 없음
        }
    }
}
