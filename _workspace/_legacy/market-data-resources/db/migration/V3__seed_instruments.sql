-- Phase D-1: 종목 마스터 시드.
-- 로컬/MVP 환경에서 종목 페이지(/instruments/{code})가 데이터 부재로 404 나지 않도록
-- KOSPI/KOSDAQ 핵심 종목을 미리 적재한다.
-- 실제 KIS 종목 마스터 동기화 어댑터는 Phase D-2 별도 작업.

INSERT INTO instrument (code, name, market) VALUES
    ('005930', '삼성전자',           'KOSPI'),
    ('000660', 'SK하이닉스',         'KOSPI'),
    ('035420', 'NAVER',             'KOSPI'),
    ('035720', '카카오',             'KOSPI'),
    ('005380', '현대차',             'KOSPI'),
    ('207940', '삼성바이오로직스',   'KOSPI'),
    ('068270', '셀트리온',           'KOSPI'),
    ('051910', 'LG화학',             'KOSPI'),
    ('006400', '삼성SDI',            'KOSPI'),
    ('373220', 'LG에너지솔루션',     'KOSPI'),
    ('105560', 'KB금융',             'KOSPI'),
    ('055550', '신한지주',           'KOSPI'),
    ('293490', '카카오게임즈',       'KOSDAQ'),
    ('247540', '에코프로비엠',       'KOSDAQ'),
    ('086520', '에코프로',           'KOSDAQ'),
    ('091990', '셀트리온헬스케어',   'KOSDAQ')
ON CONFLICT (code) DO NOTHING;
