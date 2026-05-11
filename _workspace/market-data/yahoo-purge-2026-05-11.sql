-- Yahoo Finance candle 전수 삭제 SQL (2026-05-11)
-- 실행 순서: 1. 백업 → 2. 영향 행 수 확인 → 3. DELETE → 4. 검증
--
-- [1] 백업 (실행 완료 — 백업은 git에 포함하지 않음):
--   docker exec magicjar-postgres pg_dump -U magicjar -t candle magicjar \
--     > _workspace/market-data/backup_candle_yahoo_purge_<ts>.sql
--
-- [2] 영향 행 수 확인 (2026-05-11 실행 결과)
-- source | interval | count
-- YAHOO  | 15m      |  8691
-- YAHOO  | 1d       |  3178  ← 월봉 오염 (ONE_DAY to "max" 결함)
-- YAHOO  | 1h       | 20558
-- YAHOO  | 1m       | 15050
-- YAHOO  | 30m      |  4490
-- YAHOO  | 5m       | 25495
-- YAHOO  | 90m      |  1658
-- 총 79120건
SELECT
    source,
    interval,
    COUNT(*),
    MIN(ts),
    MAX(ts)
FROM candle
WHERE source = 'YAHOO'
GROUP BY source, interval
ORDER BY interval;

-- [3] DELETE 실행 (2026-05-11 완료 — DELETE 79120)
DELETE FROM candle WHERE source = 'YAHOO';

-- [4] 삭제 후 검증
SELECT
    interval,
    source,
    COUNT(*),
    MIN(ts),
    MAX(ts)
FROM candle
WHERE code = '000660'
GROUP BY interval, source
ORDER BY interval, source;

-- 기대 결과: source = 'KIS' 행만 존재
-- 1d / KIS 행의 ts 간격이 영업일 단위 (매일 1건)인지 확인

-- [5] 전체 source 분포 확인 (2026-05-11 검증 완료)
-- source | count
-- KIS    | 3082
SELECT source, COUNT(*) FROM candle GROUP BY source ORDER BY source;
