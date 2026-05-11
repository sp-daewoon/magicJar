-- aggregate-trigger-2026-05-11.sql
-- 목적: 5m·15m·30m·90m·1h candle 전량 제거 후 consumer 재기동 시
--       CandleBootstrapRunner 5m 검사 분기 → AggregateBackfillRunner 자동 재트리거
--
-- ADR 0062 §9 NXT cover — 1m 베이스(DB 120,188건) 유지, 합산 row만 제거
-- 실행 대상: magicjar-postgres 컨테이너 또는 psql 직접
--
-- 사용 방법 (옵션 a — DB 정리 후 재기동):
--   1. 이 SQL 실행 (5m~1h 제거)
--   2. scripts/build-and-deploy.sh consumer-app  (또는 재기동)
--   3. CandleBootstrapRunner 5m 검사 분기 → AggregateBackfillRunner 자동 실행
--
-- 사용 방법 (옵션 b — admin endpoint 직접 호출, DB 정리 불필요):
--   curl -X POST http://localhost:8082/admin/aggregate/all
--
-- 검증 쿼리 (실행 전 현황):
SELECT interval, COUNT(*) AS cnt
FROM candle
WHERE interval IN ('5m', '15m', '30m', '90m', '1h')
GROUP BY interval
ORDER BY interval;

-- !! 실행 전 현황 확인 후 아래 DELETE 실행 !!

-- 5m·15m·30m·90m·1h candle 전량 제거 (1m·1d·1wk·1mo·1y 보존)
DELETE FROM candle
WHERE interval IN ('5m', '15m', '30m', '90m', '1h');

-- 제거 후 확인 (기대: 0건)
SELECT interval, COUNT(*) AS cnt
FROM candle
WHERE interval IN ('5m', '15m', '30m', '90m', '1h')
GROUP BY interval
ORDER BY interval;

-- 1m 보존 확인 (기대: ~120,188건 이상)
SELECT interval, COUNT(*) AS cnt
FROM candle
WHERE interval = '1m'
GROUP BY interval;
