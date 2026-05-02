-- V11: REJECTED Audit Trail — orders·order_events 스키마 확장 + order_reject_audit 테이블
-- spec §9.2, ADR 0034 §2-1
-- Guard reject · KisApiException 시 orders INSERT 가능하도록 kis_order_no NULLABLE 전환.

-- ───── 1. orders 테이블 변경 ─────
-- kis_order_no: REJECTED 주문은 KIS까지 도달하지 않으므로 NULL 허용
ALTER TABLE orders ALTER COLUMN kis_order_no DROP NOT NULL;
ALTER TABLE orders ALTER COLUMN krx_fwdg_ord_orgno DROP NOT NULL;

-- REJECTED 상세 정보 컬럼 추가
ALTER TABLE orders ADD COLUMN IF NOT EXISTS reject_reason_code VARCHAR(64);  -- RejectReason.code (예: KILL_SWITCH, SESSION_CLOSED)
ALTER TABLE orders ADD COLUMN IF NOT EXISTS reject_detail TEXT;               -- RejectReason.toDetail() 상세 메시지
ALTER TABLE orders ADD COLUMN IF NOT EXISTS reject_source VARCHAR(32);        -- 'GUARD' | 'KIS_API' | 'INTERNAL'

-- 기존 UNIQUE(kis_mode, kis_order_no) 제약: NULL은 UNIQUE 비교에서 제외됨 (PostgreSQL 표준)
-- 따라서 두 개의 NULL 행이 같은 (kis_mode, NULL)로 공존 가능 — PLACED rows만 NOT NULL이므로 충돌 없음.
-- 추가로 PLACED 주문에 대한 Partial UNIQUE 인덱스로 명시적 보장:
CREATE UNIQUE INDEX IF NOT EXISTS idx_orders_placed_unique
    ON orders (kis_mode, kis_order_no)
    WHERE kis_order_no IS NOT NULL;

COMMENT ON COLUMN orders.reject_reason_code IS 'RejectReason sealed class code (KILL_SWITCH, SESSION_CLOSED, NOT_BUSINESS_DAY, DAILY_LIMIT, POSITION_LIMIT, DRAWDOWN_STOP, PRICE_SANITY, KIS_API:*, INTERNAL)';
COMMENT ON COLUMN orders.reject_detail      IS 'Guard 또는 KisApiException 상세 메시지';
COMMENT ON COLUMN orders.reject_source      IS 'GUARD | KIS_API | INTERNAL';

-- ───── 2. order_events 테이블 변경 ─────
-- REJECTED 이벤트는 kis_order_no가 없음 (KIS 호출 전 거부)
ALTER TABLE order_events ALTER COLUMN kis_order_no DROP NOT NULL;

-- 기존 UNIQUE(kis_mode, kis_order_no, event_type, cumulative_qty) 제약:
-- NULL 포함 행이 여러 개 존재할 수 있음 — REJECTED 이벤트 중복 허용 (intent_id 기준 멱등으로 보완)
-- 기존 제약 삭제 후 NOT NULL 행만 적용하는 PARTIAL 인덱스로 교체:
ALTER TABLE order_events DROP CONSTRAINT IF EXISTS order_events_kis_mode_kis_order_no_event_type_cumulative__key;

CREATE UNIQUE INDEX IF NOT EXISTS idx_order_events_placed_dedup
    ON order_events (kis_mode, kis_order_no, event_type, cumulative_qty)
    WHERE kis_order_no IS NOT NULL;

-- ───── 3. order_reject_audit — append-only reject 전용 audit 테이블 ─────
-- spec §9.2, ADR 0034 §2-1
-- intent_id UNIQUE — 같은 intent의 중복 reject INSERT 차단.
-- orders 테이블 JOIN 없이 단독 조회 가능 (운영 디버깅 편의).
CREATE TABLE IF NOT EXISTS order_reject_audit (
    id              BIGSERIAL        PRIMARY KEY,
    intent_id       UUID             NOT NULL,
    order_id        UUID,                                          -- orders 테이블 FK (REJECTED row 생성 후 채움)
    user_id         UUID             NOT NULL,
    code            VARCHAR(8)       NOT NULL,
    side            VARCHAR(8)       NOT NULL,
    qty             INT              NOT NULL,
    price           NUMERIC(15, 2),
    reject_source   VARCHAR(32)      NOT NULL,                     -- GUARD | KIS_API | INTERNAL
    reject_reason   VARCHAR(64)      NOT NULL,                     -- RejectReason.code
    reject_detail   TEXT,
    occurred_at     TIMESTAMPTZ      NOT NULL,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    UNIQUE (intent_id),
    CHECK (side IN ('BUY', 'SELL')),
    CHECK (reject_source IN ('GUARD', 'KIS_API', 'INTERNAL'))
);

COMMENT ON TABLE  order_reject_audit IS 'Guard reject · KisApiException audit trail (ADR 0034). 90일 retention.';
COMMENT ON COLUMN order_reject_audit.intent_id      IS '주문 의도 UUID — 중복 reject INSERT 방지 키';
COMMENT ON COLUMN order_reject_audit.reject_source  IS 'GUARD=가드 체인, KIS_API=KIS 응답 오류, INTERNAL=시스템 내부 오류';
COMMENT ON COLUMN order_reject_audit.reject_reason  IS 'RejectReason.code (sealed class)';

CREATE INDEX IF NOT EXISTS idx_reject_user_time ON order_reject_audit (user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_reject_code_time ON order_reject_audit (code, occurred_at DESC);
