-- V19: 분봉 캐시 메타데이터 테이블 (candle-data-policy.md §5, ADR 0033 T19-T2)
--
-- (code, interval) 복합 PK로 종목×봉 단위 캐시 상태를 추적한다.
-- D1(일봉)은 영구 보존이라 이 테이블 대상이 아님 (분봉 M30·M5·M1만 기록).
-- last_accessed_at 인덱스는 LRU eviction 정렬 쿼리 최적화용.

CREATE TABLE IF NOT EXISTS instrument_cache_meta (
    code             VARCHAR(12)   NOT NULL,
    interval         VARCHAR(8)    NOT NULL,
    last_accessed_at TIMESTAMPTZ   NOT NULL,
    oldest_bar       TIMESTAMPTZ   NOT NULL,
    newest_bar       TIMESTAMPTZ   NOT NULL,
    byte_estimate    BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (code, interval)
);

CREATE INDEX IF NOT EXISTS idx_cache_meta_lru
    ON instrument_cache_meta (last_accessed_at);
