-- V10: KRX 영업일 캘린더 테이블 + 2026~2027 시드
-- spec §9.1, ADR 0032 (Market Session FSM)
-- 캘린더 source = 정적 Flyway 시드 (외부 API 의존 X)
-- 갱신 주기: 분기/연도 단위 수동 또는 MarketCalendarSyncJob(선택)

CREATE TABLE IF NOT EXISTS market_calendar (
    calendar_date   DATE         PRIMARY KEY,
    is_business_day BOOLEAN      NOT NULL,
    holiday_name    VARCHAR(64),
    holiday_reason  VARCHAR(32),   -- PUBLIC_HOLIDAY | TEMPORARY_CLOSURE | WEEKEND
    source          VARCHAR(16)  NOT NULL DEFAULT 'STATIC_SEED',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  market_calendar                IS 'KRX 영업일·휴장일 캘린더 (ADR 0032)';
COMMENT ON COLUMN market_calendar.calendar_date  IS '해당 날짜 (KST 기준)';
COMMENT ON COLUMN market_calendar.is_business_day IS '영업일 여부 (true=정규 거래일)';
COMMENT ON COLUMN market_calendar.holiday_reason IS 'PUBLIC_HOLIDAY | TEMPORARY_CLOSURE | WEEKEND';
COMMENT ON COLUMN market_calendar.source         IS '데이터 출처 (STATIC_SEED | KIS_API | MANUAL)';

CREATE INDEX IF NOT EXISTS idx_market_calendar_business
    ON market_calendar (is_business_day, calendar_date);

-- ============================================================
-- 2026 한국 공휴일 + KRX 특수 휴장
-- 참고: KRX는 법정 공휴일 + 근로자의날(5/1) + 연말(12/31) 휴장
-- ============================================================
INSERT INTO market_calendar (calendar_date, is_business_day, holiday_name, holiday_reason) VALUES
    ('2026-01-01', false, '신정',               'PUBLIC_HOLIDAY'),
    ('2026-01-21', false, '설날 연휴',           'PUBLIC_HOLIDAY'),
    ('2026-01-22', false, '설날',               'PUBLIC_HOLIDAY'),
    ('2026-01-23', false, '설날 연휴',           'PUBLIC_HOLIDAY'),
    ('2026-03-01', false, '삼일절',              'PUBLIC_HOLIDAY'),
    ('2026-05-01', false, '근로자의 날',          'PUBLIC_HOLIDAY'),
    ('2026-05-05', false, '어린이날',            'PUBLIC_HOLIDAY'),
    ('2026-05-25', false, '부처님 오신 날',       'PUBLIC_HOLIDAY'),
    ('2026-06-06', false, '현충일',              'PUBLIC_HOLIDAY'),
    ('2026-08-15', false, '광복절',              'PUBLIC_HOLIDAY'),
    ('2026-08-17', false, '광복절 대체공휴일',     'PUBLIC_HOLIDAY'),
    ('2026-09-25', false, '추석 연휴',           'PUBLIC_HOLIDAY'),
    ('2026-09-26', false, '추석',               'PUBLIC_HOLIDAY'),
    ('2026-09-28', false, '추석 대체공휴일',      'PUBLIC_HOLIDAY'),
    ('2026-10-03', false, '개천절',              'PUBLIC_HOLIDAY'),
    ('2026-10-09', false, '한글날',              'PUBLIC_HOLIDAY'),
    ('2026-12-25', false, '성탄절',              'PUBLIC_HOLIDAY'),
    ('2026-12-31', false, '연말 휴장',           'TEMPORARY_CLOSURE')
ON CONFLICT (calendar_date) DO NOTHING;

-- ============================================================
-- 2027 한국 공휴일
-- ============================================================
INSERT INTO market_calendar (calendar_date, is_business_day, holiday_name, holiday_reason) VALUES
    ('2027-01-01', false, '신정',               'PUBLIC_HOLIDAY'),
    ('2027-02-08', false, '설날 연휴',           'PUBLIC_HOLIDAY'),
    ('2027-02-09', false, '설날',               'PUBLIC_HOLIDAY'),
    ('2027-02-10', false, '설날 연휴',           'PUBLIC_HOLIDAY'),
    ('2027-03-01', false, '삼일절',              'PUBLIC_HOLIDAY'),
    ('2027-05-01', false, '근로자의 날',          'PUBLIC_HOLIDAY'),
    ('2027-05-05', false, '어린이날',            'PUBLIC_HOLIDAY'),
    ('2027-05-13', false, '부처님 오신 날',       'PUBLIC_HOLIDAY'),
    ('2027-06-06', false, '현충일',              'PUBLIC_HOLIDAY'),
    ('2027-08-15', false, '광복절',              'PUBLIC_HOLIDAY'),
    ('2027-08-16', false, '광복절 대체공휴일',    'PUBLIC_HOLIDAY'),
    ('2027-09-14', false, '추석 연휴',           'PUBLIC_HOLIDAY'),
    ('2027-09-15', false, '추석',               'PUBLIC_HOLIDAY'),
    ('2027-09-16', false, '추석 연휴',           'PUBLIC_HOLIDAY'),
    ('2027-10-04', false, '개천절 대체공휴일',    'PUBLIC_HOLIDAY'),
    ('2027-10-09', false, '한글날',              'PUBLIC_HOLIDAY'),
    ('2027-12-25', false, '성탄절',              'PUBLIC_HOLIDAY'),
    ('2027-12-31', false, '연말 휴장',           'TEMPORARY_CLOSURE')
ON CONFLICT (calendar_date) DO NOTHING;

-- ============================================================
-- 주말 자동 시드 (2026-01-01 ~ 2027-12-31)
-- DOW: 0=일요일, 6=토요일
-- ============================================================
INSERT INTO market_calendar (calendar_date, is_business_day, holiday_reason)
SELECT d::date, false, 'WEEKEND'
FROM generate_series('2026-01-01'::date, '2027-12-31'::date, '1 day'::interval) d
WHERE EXTRACT(DOW FROM d) IN (0, 6)
ON CONFLICT (calendar_date) DO NOTHING;

-- ============================================================
-- 평일이면서 위에서 등록되지 않은 날 → 영업일
-- 주말·휴일은 이미 is_business_day=false로 등록됨 → CONFLICT 시 DO NOTHING으로 건너뜀
-- ============================================================
INSERT INTO market_calendar (calendar_date, is_business_day, holiday_name, holiday_reason)
SELECT d::date, true, NULL, NULL
FROM generate_series('2026-01-01'::date, '2027-12-31'::date, '1 day'::interval) d
WHERE EXTRACT(DOW FROM d) NOT IN (0, 6)
ON CONFLICT (calendar_date) DO NOTHING;
