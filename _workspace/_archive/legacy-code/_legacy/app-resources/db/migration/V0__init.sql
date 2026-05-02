-- Phase B-1: placeholder 초기 마이그레이션.
-- market-data·strategy 모듈이 V1__*, V2__* 로 실제 스키마를 추가한다.

CREATE TABLE IF NOT EXISTS magicjar_schema_info (
    key         VARCHAR(64)  PRIMARY KEY,
    value       TEXT         NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO magicjar_schema_info (key, value)
VALUES ('bootstrap', 'phase-b-stage1')
ON CONFLICT (key) DO NOTHING;
