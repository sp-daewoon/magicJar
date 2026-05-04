-- B 작업: 1번 트랙 user_id UUID → VARCHAR(64) 일괄 전환
-- 사유: 차트 cache(R3-3)와 일관성. 운영 시 KIS 계좌번호(8자리) 등 사람이 읽는 ID 사용.
-- 영향: V9(users·user_trading_config·orders·order_events·daily_order_counter) + V11(order_reject_audit)

-- ───── FK 제약 제거 (ALTER TYPE은 양쪽 컬럼 동일 type 필요) ─────
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_user_id_fkey;
ALTER TABLE user_trading_config DROP CONSTRAINT IF EXISTS user_trading_config_user_id_fkey;

-- ───── ALTER TYPE: UUID → VARCHAR(64) ─────
-- PostgreSQL: UUID → text cast는 자동 (36자 hex 문자열로 변환됨)
ALTER TABLE users
  ALTER COLUMN user_id TYPE VARCHAR(64) USING user_id::VARCHAR;

ALTER TABLE user_trading_config
  ALTER COLUMN user_id TYPE VARCHAR(64) USING user_id::VARCHAR;

ALTER TABLE orders
  ALTER COLUMN user_id TYPE VARCHAR(64) USING user_id::VARCHAR;

ALTER TABLE order_events
  ALTER COLUMN user_id TYPE VARCHAR(64) USING user_id::VARCHAR;

ALTER TABLE daily_order_counter
  ALTER COLUMN user_id TYPE VARCHAR(64) USING user_id::VARCHAR;

ALTER TABLE order_reject_audit
  ALTER COLUMN user_id TYPE VARCHAR(64) USING user_id::VARCHAR;

-- ───── 기존 시드 row의 user_id 통일 ─────
-- 단일 사용자 모드 — 기존 UUID 시드(00000000-...-0001 등) 다 '74683223'으로 통일.
-- 멀티테넌트 도입 시 user 등록 endpoint에서 사용자별 String ID 발급.
UPDATE users               SET user_id = '74683223';
UPDATE user_trading_config SET user_id = '74683223';
UPDATE orders              SET user_id = '74683223';
UPDATE order_events        SET user_id = '74683223';
UPDATE daily_order_counter SET user_id = '74683223';
UPDATE order_reject_audit  SET user_id = '74683223';

-- ───── FK 재추가 ─────
ALTER TABLE user_trading_config
  ADD CONSTRAINT user_trading_config_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES users(user_id);

ALTER TABLE orders
  ADD CONSTRAINT orders_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES users(user_id);
