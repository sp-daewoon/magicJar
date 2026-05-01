-- 005930(삼성전자) 일봉 시드 — 최근 90 영업일.
-- 차트 검증용(Phase F R2 — F-R1-002). 실제 운영은 batch DailyCandleBackfillJob이 KIS API에서 채움.
-- generate_series로 동적 생성: 가격은 60,000원 기준 ±5% sin 변동, 주말 제외.
-- ON CONFLICT DO NOTHING — Flyway는 1회 실행이지만 안전망.

INSERT INTO candle (code, interval, open_time, open, high, low, close, volume, trade_count)
SELECT
    '005930' AS code,
    'D1' AS interval,
    -- KST 09:00 기준 → UTC TIMESTAMPTZ
    (d::date + TIME '09:00:00') AT TIME ZONE 'Asia/Seoul' AS open_time,
    -- 가격: 60,000 ± 3,000 sin
    ROUND((60000 + 3000 * SIN(EXTRACT(EPOCH FROM d) / 86400.0))::numeric, 4) AS open,
    ROUND((60000 + 3000 * SIN(EXTRACT(EPOCH FROM d) / 86400.0) + 500 + 200 * ABS(SIN(EXTRACT(EPOCH FROM d) / 43200.0)))::numeric, 4) AS high,
    ROUND((60000 + 3000 * SIN(EXTRACT(EPOCH FROM d) / 86400.0) - 500 - 200 * ABS(SIN(EXTRACT(EPOCH FROM d) / 43200.0)))::numeric, 4) AS low,
    ROUND((60000 + 3000 * SIN((EXTRACT(EPOCH FROM d) / 86400.0) + 0.3))::numeric, 4) AS close,
    (10000000 + 5000000 * ABS(SIN(EXTRACT(EPOCH FROM d) / 86400.0 + 1.5)))::bigint AS volume,
    100000::bigint AS trade_count
FROM generate_series(
    (CURRENT_DATE - INTERVAL '125 days')::date,
    (CURRENT_DATE - INTERVAL '1 day')::date,
    '1 day'::interval
) AS d
WHERE EXTRACT(DOW FROM d) NOT IN (0, 6)
ON CONFLICT (code, interval, open_time) DO NOTHING;
