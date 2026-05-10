-- =============================================================================
-- hotfix: 일봉 ts 비표준 데이터 정리 (사용자 dogfooding 발견)
-- 작성: market-data (2026-05-10)
--
-- 문제: YahooCandleFetcherAdapter가 1d 캔들 ts를 KST 00:00:00이 아닌
--   Yahoo epoch 원본(장 관련 시각, 예: 2026-05-07 15:30:11 KST)으로 저장.
--   결과: 같은 날에 ts가 다른 두 레코드가 생길 수 있음 (UQ는 ts까지 포함이므로 충돌 없음).
--   프론트엔드 차트(LWC v4)가 같은 날 두 캔들을 인식해 마지막 1봉이 이상하게 표시.
--
-- 수정 방법:
--   1. 비표준 ts(KST 00:00:00이 아닌 1d 캔들) 식별
--   2. 해당 날짜에 표준 ts(00:00:00) 레코드가 이미 있으면 비표준 레코드 DELETE
--   3. 표준 ts 레코드가 없으면 ts를 표준화 UPDATE
--
-- 실행 환경: psql 또는 DBeaver에서 직접 실행.
-- 타임존: DB는 TIMESTAMPTZ(UTC) 저장. KST 00:00:00 = UTC 전날 15:00:00.
-- =============================================================================

-- ── 1. 영향 레코드 확인 (실행 전 확인용) ───────────────────────────────────
SELECT
    id,
    code,
    interval,
    ts AT TIME ZONE 'Asia/Seoul' AS ts_kst,
    source
FROM candle
WHERE interval = '1d'
  -- KST 00:00:00이 아닌 것: epoch seconds mod 54000(15시간)이 0이 아닌 것 또는
  -- UTC 15:00:00 외의 시각
  AND EXTRACT(SECOND FROM ts AT TIME ZONE 'Asia/Seoul') != 0
ORDER BY code, ts DESC;

-- ── 2. 비표준 1d 레코드 중 같은 날(KST) 표준 레코드가 이미 있으면 DELETE ────
DELETE FROM candle AS non_std
WHERE non_std.interval = '1d'
  AND EXTRACT(SECOND FROM non_std.ts AT TIME ZONE 'Asia/Seoul') != 0
  AND EXISTS (
      SELECT 1 FROM candle AS std
      WHERE std.code = non_std.code
        AND std.interval = '1d'
        AND (std.ts AT TIME ZONE 'Asia/Seoul')::date = (non_std.ts AT TIME ZONE 'Asia/Seoul')::date
        AND EXTRACT(SECOND FROM std.ts AT TIME ZONE 'Asia/Seoul') = 0
  );

-- ── 3. 비표준 1d 레코드 중 같은 날 표준 레코드가 없으면 ts 표준화 UPDATE ────
-- KST 날짜의 00:00:00으로 ts 변경
UPDATE candle
SET ts = ((ts AT TIME ZONE 'Asia/Seoul')::date)::timestamp AT TIME ZONE 'Asia/Seoul'
WHERE interval = '1d'
  AND EXTRACT(SECOND FROM ts AT TIME ZONE 'Asia/Seoul') != 0
  AND NOT EXISTS (
      SELECT 1 FROM candle AS std
      WHERE std.code = candle.code
        AND std.interval = '1d'
        AND (std.ts AT TIME ZONE 'Asia/Seoul')::date = (candle.ts AT TIME ZONE 'Asia/Seoul')::date
        AND std.id != candle.id
        AND EXTRACT(SECOND FROM std.ts AT TIME ZONE 'Asia/Seoul') = 0
  );

-- ── 4. 마녀공장(439090) 누락 일봉 확인 (DailyCloseFinalizer fix 후 재수집 필요) ──
-- consumer 재기동 후 DailyCloseFinalizer가 16:00 KST에 자동 실행됨.
-- 5/5(어린이날 공휴일)·5/6은 휴장 여부 확인 필요:
SELECT * FROM market_calendar
WHERE trading_date BETWEEN '2026-05-05' AND '2026-05-07'
ORDER BY trading_date;

-- NOTE: 5/5 어린이날은 공휴일(휴장), 5/6은 대체공휴일 여부 확인 필요.
-- 5/7(수) 정상 거래일이면 consumer 재기동 시 ColdStartExecutor가 Yahoo cold-start로
-- 439090 1d 캔들을 backfill 할 수 있음 (watchlist에 있는 경우).
