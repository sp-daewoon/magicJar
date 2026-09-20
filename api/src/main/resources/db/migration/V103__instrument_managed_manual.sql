-- V103: instrument.managed_manual — 사용자 온디맨드 편입 플래그 (ADR 0143)
--
-- 근거: ADR 0143 §3 관리 유니버스 판정 —
--   managed(code) = avls_scale ∈ {1,2}(대·중형) ∨ managed_manual=true(편입) ∨ 보유(quantity>0)
--
-- 목적: 대·중형 밖 종목이라도 사용자가 검색→확인 후 편입하면 관리 대상에 합류시키는 플래그.
--   대·중형 여부는 avls_scale에서 파생 판정하므로 본 컬럼에 백필하지 않는다(대·중형은 false로 두어도 managed).
--   보유 종목은 strategy_position.quantity>0으로 런타임 판정(본 컬럼 무관).
--
-- 안전: add-only nullable-default. 기존 동작 무변경(zero-regression). 온보딩 필터는 이 컬럼 + avls_scale을 함께 본다.

ALTER TABLE instrument
    ADD COLUMN IF NOT EXISTS managed_manual BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_instrument_managed_manual
    ON instrument (managed_manual) WHERE managed_manual = true;

COMMENT ON COLUMN instrument.managed_manual IS
    '사용자 온디맨드 편입 플래그 (ADR 0143). 대·중형 밖 종목을 검색→확인 후 관리 유니버스에 편입할 때 true. 대·중형은 avls_scale에서 파생 판정하므로 본 컬럼과 무관.';
