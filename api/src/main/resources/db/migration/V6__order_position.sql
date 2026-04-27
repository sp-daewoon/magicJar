-- V6: 주문/포지션 테이블 (consumer-app 영속, api-app 조회 용도)

CREATE TABLE IF NOT EXISTS trade_order (
    id          UUID         PRIMARY KEY,
    code        VARCHAR(12)  NOT NULL,
    side        VARCHAR(8)   NOT NULL,
    qty         INT          NOT NULL,
    price       NUMERIC(20,4),
    type        VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trade_order_code       ON trade_order (code);
CREATE INDEX IF NOT EXISTS idx_trade_order_created_at ON trade_order (created_at DESC);

CREATE TABLE IF NOT EXISTS position (
    code        VARCHAR(12)  PRIMARY KEY,
    qty         INT          NOT NULL,
    avg_price   NUMERIC(20,4) NOT NULL,
    opened_at   TIMESTAMPTZ  NOT NULL
);
