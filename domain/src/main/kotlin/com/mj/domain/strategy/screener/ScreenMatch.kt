package com.mj.domain.strategy.screener

import com.mj.domain.strategy.dsl.EvalReason
import java.time.Instant

/**
 * 스크리너 매칭 종목 (ADR 0040).
 *
 * 스크리닝 조건을 통과한 단일 종목 정보.
 * 매칭 근거(entryReasons)는 DSL 평가 trace를 그대로 포함 — UI 상세 설명용.
 *
 * @property code         KIS 단축코드 6자리
 * @property name         한글 종목명
 * @property entryReasons DSL entry 조건 평가 trace ([EvalReason] 목록)
 * @property matchedAt    매칭 시각 (screener 실행 기준 캔들의 openTime)
 */
data class ScreenMatch(
    val code: String,
    val name: String,
    val entryReasons: List<EvalReason>,
    val matchedAt: Instant,
) {
    init {
        require(code.isNotBlank()) { "code must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
    }
}
