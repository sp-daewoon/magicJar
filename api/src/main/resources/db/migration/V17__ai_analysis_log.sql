-- T16 R1 — AI 분석 보조 audit trail (사용자 트리거 한정, ADR 0046).
--
-- 모든 AnalysisAssistant 호출을 기록한다.
--   - 사용자 비용 추적
--   - 모델·프롬프트 버전 별 응답 품질 사후 분석
--   - 멀티테넌트 (user_id 기준 격리, ADR 0043)
--
-- 본 테이블은 audit-only — 도메인 흐름·UI 캐시 용도 X.

CREATE TABLE IF NOT EXISTS ai_analysis_log (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    request_type    VARCHAR(40) NOT NULL,         -- SYMBOL_COMMENTARY · COMPANY_ANALYSIS · CHART_INTERPRETATION · INDICATOR_MEANING · JOURNAL_RETROSPECTIVE
    target_ref      VARCHAR(64),                  -- code · indicator id · trade id (request_type별 의미)
    input_summary   TEXT        NOT NULL,         -- 입력 요약 (1~500자) — full prompt 저장 X (PII·비용)
    output_text     TEXT        NOT NULL,         -- 응답 본문 (면책 포함)
    provider        VARCHAR(20) NOT NULL,         -- anthropic · mock · gemini
    model           VARCHAR(80) NOT NULL,         -- claude-sonnet-4-5 · mock 등
    prompt_version  VARCHAR(20) NOT NULL,         -- v1 ...
    tokens_input    INT         NOT NULL DEFAULT 0,
    tokens_output   INT         NOT NULL DEFAULT 0,
    cost_usd        NUMERIC(12,6) NOT NULL DEFAULT 0.0,
    duration_ms     BIGINT      NOT NULL DEFAULT 0,
    success         BOOLEAN     NOT NULL DEFAULT TRUE,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 사용자별 최근 호출 조회 (UI 히스토리·일일 한도 산출).
CREATE INDEX IF NOT EXISTS idx_ai_analysis_log_user_created
    ON ai_analysis_log (user_id, created_at DESC);

-- 비용 집계 (월별·provider별).
CREATE INDEX IF NOT EXISTS idx_ai_analysis_log_provider_created
    ON ai_analysis_log (provider, created_at);

COMMENT ON TABLE ai_analysis_log IS 'AI 분석 보조 호출 audit (T16 R1, ADR 0046). 사용자 트리거만 기록.';
COMMENT ON COLUMN ai_analysis_log.request_type IS '5종 sealed: SYMBOL_COMMENTARY · COMPANY_ANALYSIS · CHART_INTERPRETATION · INDICATOR_MEANING · JOURNAL_RETROSPECTIVE';
COMMENT ON COLUMN ai_analysis_log.input_summary IS '프롬프트 전문이 아닌 요약 (PII·비용 보호).';
COMMENT ON COLUMN ai_analysis_log.cost_usd IS 'pricePerInputToken·pricePerOutputToken 기반 추정. mock = 0.';
