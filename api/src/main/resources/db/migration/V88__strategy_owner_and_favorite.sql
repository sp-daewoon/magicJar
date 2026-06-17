-- ============================================================================
-- V88 — strategy owner 컬럼 + strategy_favorite 테이블
-- ============================================================================
-- spec: docs/superpowers/specs/2026-06-18-strategy-template-data-model-design.md §5
-- 목적: 프리셋(SYSTEM)·사용자 전략(USER)을 한 테이블에서 다루고, 즐겨찾기를 DB로 승격.
-- consumer: GET /api/strategies/start, POST/DELETE /api/strategies/{id}/favorite
-- 컨벤션: IF NOT EXISTS LOCK · owner DEFAULT 'USER'(기존 26행 무손실) · user_id NULL=SYSTEM
-- ============================================================================

ALTER TABLE strategy ADD COLUMN IF NOT EXISTS owner VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE strategy ALTER COLUMN user_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS ix_strategy_owner ON strategy (owner);

CREATE TABLE IF NOT EXISTS strategy_favorite (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    strategy_id BIGINT      NOT NULL REFERENCES strategy(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_strategy_favorite_user_strategy
    ON strategy_favorite (user_id, strategy_id);

COMMENT ON COLUMN strategy.owner IS 'spec 2026-06-18 §5.1 — SYSTEM(시드 프리셋·자동매매 비대상) | USER';
COMMENT ON TABLE strategy_favorite IS 'spec 2026-06-18 §5.2 — 사용자별 전략 즐겨찾기(localStorage 승격)';
