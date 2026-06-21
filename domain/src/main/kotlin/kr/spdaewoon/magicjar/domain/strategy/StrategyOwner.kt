package kr.spdaewoon.magicjar.domain.strategy

/**
 * 전략 소유 주체 — `strategy.owner` VARCHAR(16) 컬럼(V88)에 enum 이름으로 영속된다.
 *
 * - [SYSTEM]: 시드 프리셋(시작화면 템플릿·자동매매 비대상).
 * - [USER]: 사용자가 만든 전략.
 * - [AUTOPILOT]: magicjar-autopilot 자율 루프가 생성·관리하는 전략 (spec 2026-06-22 §2-0-C).
 *   **격리·롤백·kill switch 의 단일 식별자** — 루프 폐기(`/autopilot rollback`)·kill 은 owner=AUTOPILOT
 *   전략만 대상으로 하고 사용자 수동 전략·후성 오버나잇과 절대 비간섭한다.
 *   `MAGICJAR_AUTOPILOT_ENABLED=false`(default)면 이 owner 값은 생성되지 않는다(zero-regression).
 *
 * owner 컬럼은 VARCHAR 이므로 새 상수 추가에 DDL 변경이 필요 없다(V91 ROLLBACK 노트 참조).
 */
enum class StrategyOwner { SYSTEM, USER, AUTOPILOT }
