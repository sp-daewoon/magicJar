-- 1번 트랙: KIS 주문 어댑터용 신규 테이블 6개 (V8은 옆 세션 분봉 시드로 점유)
-- spec §4.1 / plan T1. 멀티테넌트 확장 path를 위해 user_id 처음부터 박힘.

-- ───── 1. users (시드 사용자 1명, 인증 도입 시 컬럼 추가만) ─────
CREATE TABLE users (
  user_id UUID PRIMARY KEY,
  username VARCHAR(64) UNIQUE NOT NULL,
  display_name VARCHAR(128),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

-- ───── 2. user_trading_config (사용자별 KIS 키·계좌·모드 등록 데이터) ─────
CREATE TABLE user_trading_config (
  user_id UUID PRIMARY KEY REFERENCES users(user_id),
  trading_mode VARCHAR(16) NOT NULL,
  account_no VARCHAR(20) NOT NULL,
  account_product_code VARCHAR(2) NOT NULL,
  kis_app_key_encrypted TEXT NOT NULL,
  kis_app_secret_encrypted TEXT NOT NULL,
  hts_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK (trading_mode IN ('MOCK_KIS','REAL_KIS'))
);

-- ───── 3. orders (주문 메타, mutable projection, fast read) ─────
-- V6의 trade_order는 placeholder. 1번 트랙은 신규 orders 테이블로 정공법 진행.
-- trade_order는 deprecate (legacy 보존, 추후 cleanup 트랙).
CREATE TABLE orders (
  order_id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(user_id),
  account_no VARCHAR(20) NOT NULL,
  kis_mode VARCHAR(8) NOT NULL,
  intent_id UUID NOT NULL,
  kis_order_no VARCHAR(20) NOT NULL,
  krx_fwdg_ord_orgno VARCHAR(8) NOT NULL,
  code VARCHAR(7) NOT NULL,
  side VARCHAR(8) NOT NULL,
  qty INT NOT NULL CHECK (qty > 0),
  price NUMERIC(15,2),
  order_type VARCHAR(8) NOT NULL,
  status VARCHAR(20) NOT NULL,
  filled_qty INT NOT NULL DEFAULT 0,
  avg_fill_price NUMERIC(15,2),
  reject_reason TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE (kis_mode, kis_order_no),
  CHECK (kis_mode IN ('MOCK_KIS','REAL_KIS')),
  CHECK (side IN ('BUY','SELL')),
  CHECK (order_type IN ('MARKET','LIMIT')),
  CHECK (status IN ('PENDING','PARTIALLY_FILLED','FILLED','CANCELED','REJECTED'))
);

-- ───── 4. order_events (라이프사이클 audit trail, append-only INSERT only) ─────
-- 멱등 dedup: WS·REST 둘 다 같은 체결 ingest해도 (kis_mode, kis_order_no, event_type, cumulative_qty)
-- UNIQUE 제약으로 한 번만 통과.
CREATE TABLE order_events (
  event_id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(order_id),
  user_id UUID NOT NULL,
  account_no VARCHAR(20) NOT NULL,
  kis_mode VARCHAR(8) NOT NULL,
  kis_order_no VARCHAR(20) NOT NULL,
  event_type VARCHAR(20) NOT NULL,
  cumulative_qty INT,
  partial_qty INT,
  exec_price NUMERIC(15,2),
  source VARCHAR(8),
  payload JSONB,
  occurred_at TIMESTAMPTZ NOT NULL,
  ingested_at TIMESTAMPTZ NOT NULL,
  UNIQUE (kis_mode, kis_order_no, event_type, cumulative_qty),
  CHECK (event_type IN ('PLACED','MODIFIED','CANCELED','EXECUTED','REJECTED','RECEIPT_ACCEPTED'))
);

-- ───── 5. daily_order_counter (DailyOrderLimit Guard용) ─────
CREATE TABLE daily_order_counter (
  counter_date DATE NOT NULL,
  user_id UUID NOT NULL,
  account_no VARCHAR(20) NOT NULL,
  count INT NOT NULL DEFAULT 0,
  total_value_krw NUMERIC(18,2) NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (counter_date, user_id, account_no)
);

-- ───── 6. kill_switch_state (KillSwitch 상태, 1차는 GLOBAL 1행) ─────
CREATE TABLE kill_switch_state (
  scope VARCHAR(16) PRIMARY KEY,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  reason TEXT,
  toggled_by VARCHAR(64),
  toggled_at TIMESTAMPTZ NOT NULL
);
INSERT INTO kill_switch_state (scope, is_active, reason, toggled_by, toggled_at)
VALUES ('GLOBAL', TRUE, 'initial — guard layer default ON', 'system', NOW());

-- ───── 인덱스 ─────
CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);
CREATE INDEX idx_orders_pending ON orders(kis_mode, status)
  WHERE status IN ('PENDING','PARTIALLY_FILLED');
CREATE INDEX idx_order_events_order ON order_events(order_id, occurred_at);
CREATE INDEX idx_order_events_user ON order_events(user_id, occurred_at DESC);
