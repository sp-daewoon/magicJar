-- V102: system_config.active_trading_mode — 활성 주문 모드 DB 단일 권위
--
-- 근거: 감사 _workspace/audit/2026-09-19-mock-real-boundary-audit.md §5 설계 A.
--
-- 목적: MOCK/REAL 주문 모드를 "재기동 없이" 전환하기 위한 DB 단일 권위.
--       기존에는 MAGICJAR_MODE env(정적)만 있어 모드 전환 시 서비스 재기동이 필요했다.
--       이 컬럼을 도입해 TradingModeGate가 주문 시점마다 DB를 읽어 min(env 상한, DB 활성모드)로 판정한다.
--
-- 안전 불변식:
--   - default 'MOCK' (안전 기본값). 판정 실패/불명 시 MOCK fail-closed.
--   - DB 활성모드는 env REAL 상한(MAGICJAR_MODE=REAL + ALLOW_REAL + 사용자 동의)이 열려 있을 때만
--     REAL로 작동한다. env 상한이 MOCK이면 DB 값과 무관하게 무조건 MOCK.
--   - 시세/차트/지표는 이 컬럼과 무관하게 항상 REAL credential로 조회한다(모드 분리).
--   - CHECK 제약으로 REAL/MOCK 외 값 차단.
--
-- zero-regression: 기존 행(id=1 싱글톤)은 default 'MOCK'로 백필 → 도입 전과 동일하게 MOCK 동작.

ALTER TABLE system_config
    ADD COLUMN IF NOT EXISTS active_trading_mode VARCHAR(4) NOT NULL DEFAULT 'MOCK';

-- REAL/MOCK 외 값 차단 (자본 안전 — 오타로 인한 미정의 모드 방지)
ALTER TABLE system_config
    DROP CONSTRAINT IF EXISTS chk_system_config_active_trading_mode;
ALTER TABLE system_config
    ADD CONSTRAINT chk_system_config_active_trading_mode
        CHECK (active_trading_mode IN ('REAL', 'MOCK'));
