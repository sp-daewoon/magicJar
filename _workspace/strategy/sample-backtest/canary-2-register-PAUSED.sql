-- ============================================================================
-- canary 전략 2종 등록 SQL — PAUSED 강제 (2026-06-15 · strategy-engine)
-- ============================================================================
--
-- ⚠️ 자본 안전 (중대):
--   현재 canary cap 3종(QTY·NOTIONAL·DAILY)이 0=비활성 상태("canary=실운영 동일", 2026-06-15 결재).
--   즉 ACTIVE 전략은 종목당 ~33만원 실계좌 매매가 cap 없이 나간다.
--   따라서 본 SQL은 반드시 status='PAUSED'로 등록한다. 활성화는 사용자가 직접 수행한다(맨 아래 §활성화).
--
-- 종목 선정:
--   · 005930 (삼성전자, KOSPI) — 1m 8.1만봉/5m 1.6만봉(2026-06-15까지), 최상위 유동성. 1m 전략에 사용.
--   · 000660 (SK하이닉스, KOSPI) — 1m 8.0만봉/5m 1.6만봉, 변동성+유동성 양호. 5m 전략에 사용.
--   · 후성(093370)은 #86이 사용 + 1m 1,170봉뿐(데이터 빈약) → 회피.
--
-- #86 매도 결함 해소(핵심):
--   #86은 진입·청산이 같은 ma(10) 기준 → ma(10) 근처 진동으로 익절 전 잦은 소손실 회전.
--   본 2종은 ① 진입 지표(donchian_lower)와 청산 지표(ema(20))를 분리
--             ② 청산에 버퍼(ema*0.988/0.985)로 노이즈 dip 무시
--             ③ min_hold_bars로 최소 보유 강제(DSL 매도만 지연 — 손절/익절은 항상 작동)
--             ④ take_profit_pct ≥ 2.2 로 익절이 주 청산이 되게.
--   오프라인 백테스트(2026-03-01~, 정규장): DSL 매도 진동 청산 #86 1,597~1,941건 → 신규 4~25건,
--   익절도달률 6~8% → 48~55%. evidence = canary-2-backtest-evidence.md
--
-- user_id = 74683223 (canary 사용자, #85·#86과 동일)
-- ============================================================================

-- ── 전략 A: 1분봉 (005930) ──────────────────────────────────────────────────
INSERT INTO strategy (
    user_id, name, name_ko,
    dsl_entry,
    dsl_exit,
    universe_market, tf, status,
    codes, intervals,
    take_profit_pct, fixed_stop_loss_pct, min_hold_bars,
    vi_action_policy, origin,
    complexity_class, created_at, updated_at, paused_at
) VALUES (
    74683223,
    'canary_1m_donchian_bounce_005930',
    'canary 1분봉 돈치안 지지반등 (삼성전자)',
    '(low <= donchian_lower(20) * 1.003) AND (close > donchian_lower(20)) AND (close >= ema(20) * 0.99) AND (rsi(14) < 75)',
    'close < ema(20) * 0.988',
    'KOSPI', '1m', 'PAUSED',
    '["005930"]'::jsonb, '["1m"]'::jsonb,
    2.2000, 2.0000, 8,
    'ALERT_ONLY', 'USER_DSL',
    'LOW', NOW(), NOW(), NOW()
);

-- ── 전략 B: 5분봉 (000660) ──────────────────────────────────────────────────
INSERT INTO strategy (
    user_id, name, name_ko,
    dsl_entry,
    dsl_exit,
    universe_market, tf, status,
    codes, intervals,
    take_profit_pct, fixed_stop_loss_pct, min_hold_bars,
    vi_action_policy, origin,
    complexity_class, created_at, updated_at, paused_at
) VALUES (
    74683223,
    'canary_5m_donchian_trend_000660',
    'canary 5분봉 돈치안 추세지지 (SK하이닉스)',
    '(low <= donchian_lower(20) * 1.005) AND (close > donchian_lower(20)) AND (close >= ema(20))',
    'close < ema(20) * 0.985',
    'KOSPI', '5m', 'PAUSED',
    '["000660"]'::jsonb, '["5m"]'::jsonb,
    3.0000, 2.5000, 4,
    'ALERT_ONLY', 'USER_DSL',
    'LOW', NOW(), NOW(), NOW()
);

-- 등록 확인
SELECT id, name, status, tf, take_profit_pct, fixed_stop_loss_pct, min_hold_bars, codes, dsl_entry, dsl_exit
FROM strategy
WHERE name IN ('canary_1m_donchian_bounce_005930', 'canary_5m_donchian_trend_000660')
ORDER BY id;

-- ============================================================================
-- §활성화 (사용자가 직접 — 절대 자동 ACTIVE 금지)
-- ============================================================================
-- 1) 활성화 직전 반드시 cap 점검:
--      장중 소액 1주 한도로 굴리려면 canary cap(QTY/NOTIONAL/DAILY)을 먼저 켜는 것을 권장.
--      cap=0(비활성)이면 종목당 ~33만원 진입이 cap 없이 나간다.
-- 2) 한 번에 1개만 ACTIVE 권장(checklist S: ACTIVE 전략 정확히 1개·종목 1개).
--
-- SQL로 활성화 (예: 1분봉 전략):
--   UPDATE strategy SET status='ACTIVE', paused_at=NULL, updated_at=NOW()
--   WHERE name='canary_1m_donchian_bounce_005930';
--
-- 다시 PAUSED로 내리기:
--   UPDATE strategy SET status='PAUSED', paused_at=NOW(), updated_at=NOW()
--   WHERE name='canary_1m_donchian_bounce_005930';
--
-- (REST enable 엔드포인트 PUT /api/strategy/{id}/enable 는 auto-disabled 복구용이며
--  PAUSED→ACTIVE 전이 게이트는 StrategyService.activate 경로다. SQL UPDATE가 가장 단순·확실.)
-- ============================================================================
