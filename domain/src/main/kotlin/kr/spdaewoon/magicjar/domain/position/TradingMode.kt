package kr.spdaewoon.magicjar.domain.position

/**
 * 거래 모드 — [kr.spdaewoon.magicjar.domain.order.execution.TradingMode] 단일 출처로 통일 (감사 §6 P2-6 · 2026-07-03).
 *
 * 동일 enum(MOCK/REAL) 2벌이 domain 에 공존하던 것(position/ vs order.execution/ — convention §2-4 위반)을
 * order.execution 판으로 수렴한다(코드 컨벤션 §2-4 typealias 3단계 통일의 1단계).
 *
 * - JPA 는 name 문자열로 저장하고 두 벌의 상수 name 이 동일(MOCK/REAL)이므로 **데이터 영향 0**.
 * - 기존 참조(레포 전체 1건 — PositionJpaAdapter 의 `valueOf`/`MOCK` 접근)는 typealias 로 그대로 컴파일된다.
 * - 신규 코드는 order.execution 판을 직접 import 한다(deprecation 경고로 유도).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Deprecated(
    message = "domain.order.execution.TradingMode 로 통일 (감사 §6 P2-6). 신규 코드는 order.execution 판을 직접 import.",
    replaceWith = ReplaceWith("TradingMode", imports = ["kr.spdaewoon.magicjar.domain.order.execution.TradingMode"]),
)
typealias TradingMode = kr.spdaewoon.magicjar.domain.order.execution.TradingMode
