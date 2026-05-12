-- V18: position 테이블 신규 — T-RISK Phase 2 (ADR 0067 §5-4 G-1 + ADR 0042 정합)
-- 보유 포지션 영속 — risk 평가용 (RiskPositionPort 구현 기반)

CREATE TABLE position (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     VARCHAR(40)     NOT NULL,
    code        VARCHAR(8)      NOT NULL,
    qty         BIGINT          NOT NULL,
    avg_price   NUMERIC(19, 4)  NOT NULL,
    entry_ts    TIMESTAMPTZ     NOT NULL,
    source      VARCHAR(10)     NOT NULL
        CONSTRAINT chk_position_source CHECK (source IN ('MOCK', 'REAL')),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_position_user ON position(user_id, code);

COMMENT ON TABLE position IS 'T-RISK Phase 2 — 보유 포지션 영속 (ADR 0067 §5-4 G-1 + ADR 0042 정합)';
COMMENT ON COLUMN position.user_id    IS '사용자 ID';
COMMENT ON COLUMN position.code       IS '종목 코드 (KIS 6자리)';
COMMENT ON COLUMN position.qty        IS '보유 수량 (0=청산, 행 유지)';
COMMENT ON COLUMN position.avg_price  IS '평균 매수 단가';
COMMENT ON COLUMN position.entry_ts   IS '최초 진입 ts';
COMMENT ON COLUMN position.source     IS 'MOCK·REAL — TradingMode 정합';
