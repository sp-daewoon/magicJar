package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * KIS 호환 DSL 전략 정의 — KIS raw parser.py L555 StrategyDefinition 1:1 port.
 *
 * KIS raw name: StrategyDefinition → DslStrategyDefinition (rename — Kotlin Definition 충돌 회피).
 * Source: parser.py:L555 StrategyDefinition dataclass
 *
 * magicJar 확장 wrapper는 [kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition].
 * 이 클래스는 KIS 호환 inner 레이어 — buy_condition/sell_condition 텍스트 파싱 결과 AST 보관.
 *
 * @param name 전략 영문명 (KIS raw name 필드)
 * @param nameKo 전략 한국어명 (KIS raw name_ko 필드)
 * @param buyCondition 매수 조건 AST (KIS raw buy_condition 텍스트 → DslNode 파싱 결과)
 * @param sellCondition 매도 조건 AST (KIS raw sell_condition 텍스트 → DslNode 파싱 결과)
 * @param params 추가 파라미터 (KIS raw params — initial_capital·position_size 등)
 */
data class DslStrategyDefinition(
    val name: String,
    val nameKo: String = "",
    val buyCondition: DslNode?,
    val sellCondition: DslNode?,
    val params: Map<String, Any> = emptyMap(),
)
