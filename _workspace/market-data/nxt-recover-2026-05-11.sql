-- NXT cover 재수집 SQL — ADR 0063 채택 (2026-05-11)
-- 목적: KRX-only(J) 수집분 정리 후 KRX+NXT 통합(UN) 재수집 트리거
--
-- 주의:
--   1. 이 SQL은 1m candle 기존 행을 삭제한다. consumer 재기동 전 실행.
--   2. CandleBootstrapRunner가 count1m < 700 감지 → cold-start 자동 트리거.
--   3. 일봉(1d·1wk·1mo·1y)도 UN으로 재수집 대상.
--
-- 실행 순서:
--   Step 1: 1m candle 삭제 (KRX-only 데이터 정리)
--   Step 2: 확인 쿼리 (삭제 전 row 수 체크 권장)
--   Step 3: consumer 재기동 → CandleBootstrapRunner 자동 재트리거

-- ============================================================
-- Step 0: 삭제 전 현황 확인 (DRY-RUN — 실제 삭제 전 실행 권장)
-- ============================================================

SELECT
    code,
    interval,
    COUNT(*) AS row_count,
    MIN(ts AT TIME ZONE 'Asia/Seoul') AS min_kst,
    MAX(ts AT TIME ZONE 'Asia/Seoul') AS max_kst,
    MIN(EXTRACT(HOUR FROM ts AT TIME ZONE 'Asia/Seoul')) AS min_hour,
    MAX(EXTRACT(HOUR FROM ts AT TIME ZONE 'Asia/Seoul')) AS max_hour
FROM candle
WHERE interval = '1m'
GROUP BY code, interval
ORDER BY code, interval;

-- ============================================================
-- Step 1: 1m candle 전체 삭제 (KRX-only 수집분 정리)
-- ============================================================
-- 옵션 A: 전체 1m 삭제 (추천 — UN 통합 재수집으로 완전 교체)

DELETE FROM candle WHERE interval = '1m';

-- ============================================================
-- Step 2: 일봉(1d) 삭제 — UN 통합 재수집 대상 (선택 사항)
-- ============================================================
-- DELETE FROM candle WHERE interval = '1d';

-- ============================================================
-- Step 3: 삭제 후 확인
-- ============================================================

SELECT
    interval,
    COUNT(*) AS remaining_count
FROM candle
WHERE interval IN ('1m', '1d')
GROUP BY interval;

-- ============================================================
-- Step 4: consumer 재기동 후 검증 쿼리 (재수집 완료 후 실행)
-- ============================================================

-- 1m 시각 분포 확인 (기대: min_hour=8, max_hour=19)
SELECT
    code,
    MIN(EXTRACT(HOUR FROM ts AT TIME ZONE 'Asia/Seoul')) AS min_h,
    MAX(EXTRACT(HOUR FROM ts AT TIME ZONE 'Asia/Seoul')) AS max_h,
    COUNT(*) AS count
FROM candle
WHERE code IN ('000660', '005930')
  AND interval = '1m'
GROUP BY code;

-- 당일 시각별 분포 (000660 기준 — 기대: 8시~19시 각 60건 내외)
SELECT
    EXTRACT(HOUR FROM ts AT TIME ZONE 'Asia/Seoul') AS h,
    COUNT(*) AS cnt
FROM candle
WHERE code = '000660'
  AND interval = '1m'
  AND (ts AT TIME ZONE 'Asia/Seoul')::date = CURRENT_DATE
GROUP BY 1
ORDER BY 1;
