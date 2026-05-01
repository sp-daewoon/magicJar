-- 005930·000660 분봉 시드 (H1/M30/M5/M1) — 종목상세 차트 분봉 클릭 시 즉시 표시.
-- 한국 장 시간 KST 09:00~15:00 + 영업일만, 월~금. 가격은 V7과 일관성 위해
-- 베이스가격 ± 1% sin 변동. ON CONFLICT DO NOTHING — Flyway 1회만 실행되지만 안전.
--
-- 데이터 양:
--   H1: 005930·000660 × 7일 × 7봉/일 = ~98 row
--   M30: × 7일 × 13봉/일 = ~182 row
--   M5: × 3일 × 78봉/일 = ~468 row
--   M1: × 1일 × 391봉/일 = ~782 row
--   합계 ~1500 row (V7 005930 D1 89봉과 별개).

-- ───── H1 (60분봉) — 7 영업일 × 09~15시 ─────
INSERT INTO candle (code, interval, open_time, open, high, low, close, volume, trade_count)
SELECT
    code,
    'H1' AS interval,
    d AS open_time,
    base AS open,
    base + (base * 0.005)::numeric(18, 4) AS high,
    base - (base * 0.005)::numeric(18, 4) AS low,
    (base + (base * 0.003 * SIN(EXTRACT(EPOCH FROM d) / 3600.0)))::numeric(18, 4) AS close,
    (base_volume * (0.7 + ABS(SIN(EXTRACT(EPOCH FROM d) / 3600.0)) * 0.6))::bigint AS volume,
    1000::bigint AS trade_count
FROM (
    SELECT
        sym.code,
        sym.base,
        sym.base_volume,
        d
    FROM (
        VALUES
            ('005930', 60000::numeric, 1000000::bigint),
            ('000660', 130000::numeric, 500000::bigint)
    ) AS sym(code, base, base_volume)
    CROSS JOIN generate_series(
        date_trunc('hour', NOW() AT TIME ZONE 'Asia/Seoul' - INTERVAL '7 days') AT TIME ZONE 'Asia/Seoul',
        date_trunc('hour', NOW() AT TIME ZONE 'Asia/Seoul' - INTERVAL '1 hour') AT TIME ZONE 'Asia/Seoul',
        INTERVAL '1 hour'
    ) AS d
    WHERE EXTRACT(DOW FROM d AT TIME ZONE 'Asia/Seoul') NOT IN (0, 6)
      AND EXTRACT(HOUR FROM d AT TIME ZONE 'Asia/Seoul') BETWEEN 9 AND 15
) gen
WHERE base IS NOT NULL  -- generated subquery FKs
ON CONFLICT (code, interval, open_time) DO NOTHING;

-- ───── M30 (30분봉) — 7 영업일 × 09:00~15:00 30분 단위 ─────
INSERT INTO candle (code, interval, open_time, open, high, low, close, volume, trade_count)
SELECT
    code,
    'M30' AS interval,
    d,
    base,
    base + (base * 0.003)::numeric(18, 4),
    base - (base * 0.003)::numeric(18, 4),
    (base + (base * 0.002 * SIN(EXTRACT(EPOCH FROM d) / 1800.0)))::numeric(18, 4),
    (base_volume * 0.5 * (0.7 + ABS(SIN(EXTRACT(EPOCH FROM d) / 1800.0)) * 0.6))::bigint,
    500::bigint
FROM (
    SELECT sym.code, sym.base, sym.base_volume, d
    FROM (
        VALUES ('005930', 60000::numeric, 1000000::bigint),
               ('000660', 130000::numeric, 500000::bigint)
    ) AS sym(code, base, base_volume)
    CROSS JOIN generate_series(
        date_trunc('hour', NOW() AT TIME ZONE 'Asia/Seoul' - INTERVAL '7 days') AT TIME ZONE 'Asia/Seoul',
        date_trunc('hour', NOW() AT TIME ZONE 'Asia/Seoul' - INTERVAL '30 minutes') AT TIME ZONE 'Asia/Seoul',
        INTERVAL '30 minutes'
    ) AS d
    WHERE EXTRACT(DOW FROM d AT TIME ZONE 'Asia/Seoul') NOT IN (0, 6)
      AND EXTRACT(HOUR FROM d AT TIME ZONE 'Asia/Seoul') BETWEEN 9 AND 14
) gen
ON CONFLICT (code, interval, open_time) DO NOTHING;

-- ───── M5 (5분봉) — 최근 3 영업일 ─────
INSERT INTO candle (code, interval, open_time, open, high, low, close, volume, trade_count)
SELECT
    code,
    'M5' AS interval,
    d,
    base,
    base + (base * 0.002)::numeric(18, 4),
    base - (base * 0.002)::numeric(18, 4),
    (base + (base * 0.0015 * SIN(EXTRACT(EPOCH FROM d) / 300.0)))::numeric(18, 4),
    (base_volume * 0.1 * (0.7 + ABS(SIN(EXTRACT(EPOCH FROM d) / 300.0)) * 0.6))::bigint,
    100::bigint
FROM (
    SELECT sym.code, sym.base, sym.base_volume, d
    FROM (
        VALUES ('005930', 60000::numeric, 1000000::bigint),
               ('000660', 130000::numeric, 500000::bigint)
    ) AS sym(code, base, base_volume)
    CROSS JOIN generate_series(
        date_trunc('hour', NOW() AT TIME ZONE 'Asia/Seoul' - INTERVAL '3 days') AT TIME ZONE 'Asia/Seoul',
        date_trunc('hour', NOW() AT TIME ZONE 'Asia/Seoul' - INTERVAL '5 minutes') AT TIME ZONE 'Asia/Seoul',
        INTERVAL '5 minutes'
    ) AS d
    WHERE EXTRACT(DOW FROM d AT TIME ZONE 'Asia/Seoul') NOT IN (0, 6)
      AND EXTRACT(HOUR FROM d AT TIME ZONE 'Asia/Seoul') BETWEEN 9 AND 14
) gen
ON CONFLICT (code, interval, open_time) DO NOTHING;

-- ───── M1 (1분봉) — 최근 1 영업일만 (382 봉 × 2종목) ─────
INSERT INTO candle (code, interval, open_time, open, high, low, close, volume, trade_count)
SELECT
    code,
    'M1' AS interval,
    d,
    base,
    base + (base * 0.001)::numeric(18, 4),
    base - (base * 0.001)::numeric(18, 4),
    (base + (base * 0.0008 * SIN(EXTRACT(EPOCH FROM d) / 60.0)))::numeric(18, 4),
    (base_volume * 0.02 * (0.7 + ABS(SIN(EXTRACT(EPOCH FROM d) / 60.0)) * 0.6))::bigint,
    20::bigint
FROM (
    SELECT sym.code, sym.base, sym.base_volume, d
    FROM (
        VALUES ('005930', 60000::numeric, 1000000::bigint),
               ('000660', 130000::numeric, 500000::bigint)
    ) AS sym(code, base, base_volume)
    CROSS JOIN generate_series(
        date_trunc('hour', NOW() AT TIME ZONE 'Asia/Seoul' - INTERVAL '1 day') AT TIME ZONE 'Asia/Seoul',
        date_trunc('hour', NOW() AT TIME ZONE 'Asia/Seoul' - INTERVAL '1 minute') AT TIME ZONE 'Asia/Seoul',
        INTERVAL '1 minute'
    ) AS d
    WHERE EXTRACT(DOW FROM d AT TIME ZONE 'Asia/Seoul') NOT IN (0, 6)
      AND EXTRACT(HOUR FROM d AT TIME ZONE 'Asia/Seoul') BETWEEN 9 AND 14
) gen
ON CONFLICT (code, interval, open_time) DO NOTHING;
