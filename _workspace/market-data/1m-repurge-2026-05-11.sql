-- 1m 분봉 재수집 SQL (2026-05-11 dogfooding 결함 정정)
-- 목적: FHKST03010230 단일 call(120건) 한계로 미수집된 1m 분봉을 전부 삭제 후
--       consumer 재기동 시 CandleBootstrapRunner가 1m 부족 감지 → KIS cold-start 재트리거
--
-- 실행 전 확인:
--   SELECT code, COUNT(*), MIN(ts), MAX(ts)
--   FROM candle WHERE interval='1m'
--   GROUP BY code ORDER BY code;
--
-- 예상 결과: 종목당 당일 ≤ 120건 (페이지네이션 이전 구조 한계)
-- 목표 결과: 종목당 당일 ≥ 350건 (정규장 9:00~15:30 ≈ 390건)

-- Step 1: 현재 1m 분봉 현황 확인 (삭제 전 스냅샷용)
SELECT
    code,
    COUNT(*)            AS cnt,
    MIN(TO_TIMESTAMP(ts)) AS earliest,
    MAX(TO_TIMESTAMP(ts)) AS latest
FROM candle
WHERE interval = '1m'
GROUP BY code
ORDER BY code;

-- Step 2: 1m 분봉 전체 삭제 (재수집을 위해)
-- consumer 재기동 후 CandleBootstrapRunner가 count1m < 350 감지 → cold-start 자동 트리거
DELETE FROM candle WHERE interval = '1m';

-- Step 3: 삭제 확인
SELECT COUNT(*) AS remaining_1m FROM candle WHERE interval = '1m';
-- 예상: 0

-- 이후 액션:
--   docker compose restart consumer-app
--   또는 scripts/build-and-deploy.sh consumer-app
--
-- consumer 부팅 시 CandleBootstrapRunner가 1m=0 < 350 감지 후
-- ColdStartExecutor.submit(code) → KIS FHKST03010230 페이지네이션 전수 수집 시작
--
-- 완료 후 검증:
--   SELECT code, COUNT(*), MIN(TO_TIMESTAMP(ts)), MAX(TO_TIMESTAMP(ts))
--   FROM candle WHERE interval='1m'
--   GROUP BY code ORDER BY code;
-- 예상: 종목당 당일 ≥ 350건, 9:00~15:30 구간 연속성 확인
