-- =====================================================================
-- V99 — strategy_trendline_ref (추세매매 Phase 2 · ADR 0140 §3-3)
--
-- 선-전략 참조 무결성 테이블. 전략 DSL(dsl_entry/dsl_exit)이 `trendline(id)` 노드로
-- 추세선을 참조하면, 저장 시 파싱으로 id 를 추출해 이 테이블에 동기화한다(R2 backend-core).
--
-- 용도:
--   1. 선 삭제 가드 — 참조 전략 존재 시 DELETE 를 409 로 차단(자본 기준 고아화 방지).
--   2. 강제 삭제(?force=true) — 참조 전략 자동 PAUSED 후 정리.
--
-- 참조 행 정리 정책(ADR 0140 §3-3 정합):
--   - 전략 hard delete → FK ON DELETE CASCADE 로 자동 정리.
--   - 전략 soft delete(status=DELETED) → api 가 명시 정리(deleteByStrategyId).
--   - 전략 보관(ARCHIVED) → 행 유지(가역). 삭제 가드 조회가 status 로 필터(DELETED·ARCHIVED 제외).
--   - 선 강제 삭제 → FK ON DELETE CASCADE(trendline) 로 자동 정리 + api 명시 정리 이중 안전망.
--
-- add-only. 기존 전략·추세선 스키마 무변경.
-- =====================================================================

CREATE TABLE strategy_trendline_ref (
    id BIGSERIAL PRIMARY KEY,
    strategy_id BIGINT NOT NULL,
    trendline_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_strategy_trendline UNIQUE (strategy_id, trendline_id),
    CONSTRAINT fk_str_tl_strategy FOREIGN KEY (strategy_id)
        REFERENCES strategy (id) ON DELETE CASCADE,
    CONSTRAINT fk_str_tl_trendline FOREIGN KEY (trendline_id)
        REFERENCES trendline (id) ON DELETE CASCADE
);

-- 선 삭제 가드·강제 삭제가 trendline_id 로 역참조(전략 조회) → 인덱스 필수.
CREATE INDEX idx_str_tl_trendline ON strategy_trendline_ref (trendline_id);
-- 전략 저장 시 참조 집합 재계산(strategy_id 로 기존 행 조회) → 인덱스.
CREATE INDEX idx_str_tl_strategy ON strategy_trendline_ref (strategy_id);

COMMENT ON TABLE strategy_trendline_ref IS
    '선-전략 참조 무결성 (ADR 0140 §3-3) — DSL trendline(id) 노드 참조를 저장 시 동기화. 삭제 가드·강제삭제 PAUSED 근거';
COMMENT ON COLUMN strategy_trendline_ref.strategy_id IS '참조하는 전략 id (strategy.id, ON DELETE CASCADE)';
COMMENT ON COLUMN strategy_trendline_ref.trendline_id IS '참조되는 추세선 id (trendline.id, ON DELETE CASCADE)';
