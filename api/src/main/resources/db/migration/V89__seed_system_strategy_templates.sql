-- ============================================================================
-- V89 — SYSTEM 전략 템플릿 시드 (스마트머니 6 + 미너비니 4 = 10건)
-- ============================================================================
-- spec: docs/superpowers/specs/2026-06-18-strategy-template-data-model-design.md §5.3
-- 출처: frontend serializer(serializeBlockTree) 채취 DSL — 수기 추정 0 (frontend/scripts/extract-preset-dsl.mjs, 채취 후 삭제).
--       smartMoneyPresets.ts(6) + minerviniFavorites.ts(4) 실파일 채취 → 합계 10건
--       (spec/plan은 11건으로 기재했으나 현행 프론트 하드코딩은 미너비니 4건이라 실데이터 10건 시드 — 최종보고에 명기).
-- 멱등: name UNIQUE 제약이 없어 (owner='SYSTEM', name) NOT EXISTS 가드로 재실행 안전.
-- owner='SYSTEM' = 시작화면 템플릿 + 자동매매 평가 비대상(eval 경로 owner='USER' 가드와 짝).
-- origin: chk_strategy_origin CHECK(NULL|USER_DSL|PAPER_DISCOVERY) — 'SEED' 류 금지. SYSTEM은 owner로 식별, origin은 NULL(legacy).
-- ast_depth/node_count 등 복잡도 컬럼 0 = 템플릿은 평가 비대상.
-- status='ACTIVE' = chk_strategy_status 허용값. owner='SYSTEM' eval 가드로 실제 평가에서는 제외됨.
-- ============================================================================

-- 멱등 INSERT 헬퍼 — owner='SYSTEM' AND name 미존재 시에만 적재.
INSERT INTO strategy (user_id, owner, name, name_ko, dsl_entry, dsl_exit, universe_market, tf,
                      take_profit_pct, fixed_stop_loss_pct, ast_depth, node_count, indicator_call_count,
                      estimated_eval_time_ms, complexity_class, status, vi_action_policy, created_at, updated_at)
SELECT v.user_id, v.owner, v.name, v.name_ko, v.dsl_entry, v.dsl_exit, v.universe_market, v.tf,
       v.take_profit_pct, v.fixed_stop_loss_pct, v.ast_depth, v.node_count, v.indicator_call_count,
       v.estimated_eval_time_ms, v.complexity_class, v.status, v.vi_action_policy, now(), now()
FROM (VALUES
    -- 스마트머니 6 (smartMoneyPresets.ts)
    (NULL::BIGINT, 'SYSTEM', 'fvg-bullish-reclaim', 'FVG 매수 후보',
     '(fvg_bullish_active(50) == 1) AND (close > fvg_bullish_mid(50))',
     'close < fvg_bullish_bottom(50)',
     'KOSPI', '3m', NULL::NUMERIC, NULL::NUMERIC, 0, 0, 0, 0, 'LOW', 'ACTIVE', 'ALERT_ONLY'),
    (NULL, 'SYSTEM', 'order-block-bullish-retest', 'OB 매수 후보',
     '(ob_bullish_active(20, 20, 50) == 1) AND (close > ob_bullish_mid(20, 20, 50))',
     'close < ob_bullish_bottom(20, 20, 50)',
     'KOSPI', '3m', NULL, NULL, 0, 0, 0, 0, 'LOW', 'ACTIVE', 'ALERT_ONLY'),
    (NULL, 'SYSTEM', 'fvg-bearish-reject', 'FVG 매도 후보',
     '(fvg_bearish_active(50) == 1) AND (close < fvg_bearish_mid(50))',
     'close > fvg_bearish_top(50)',
     'KOSPI', '3m', NULL, NULL, 0, 0, 0, 0, 'LOW', 'ACTIVE', 'ALERT_ONLY'),
    (NULL, 'SYSTEM', 'order-block-bearish-retest', 'OB 매도 후보',
     '(ob_bearish_active(20, 20, 50) == 1) AND (close < ob_bearish_mid(20, 20, 50))',
     'close > ob_bearish_top(20, 20, 50)',
     'KOSPI', '3m', NULL, NULL, 0, 0, 0, 0, 'LOW', 'ACTIVE', 'ALERT_ONLY'),
    (NULL, 'SYSTEM', 'order-block-engulfing-bullish-retest', 'OB 장악형 매수 후보',
     '(ob_bullish_active(20, 20, 100, engulfing, body) == 1) AND (close > ob_bullish_mid(20, 20, 100, engulfing, body))',
     'close < ob_bullish_bottom(20, 20, 100, engulfing, body)',
     'KOSPI', '3m', NULL, NULL, 0, 0, 0, 0, 'LOW', 'ACTIVE', 'ALERT_ONLY'),
    (NULL, 'SYSTEM', 'order-block-engulfing-bearish-retest', 'OB 장악형 매도 후보',
     '(ob_bearish_active(20, 20, 100, engulfing, body) == 1) AND (close < ob_bearish_mid(20, 20, 100, engulfing, body))',
     'close > ob_bearish_top(20, 20, 100, engulfing, body)',
     'KOSPI', '3m', NULL, NULL, 0, 0, 0, 0, 'LOW', 'ACTIVE', 'ALERT_ONLY'),
    -- 미너비니 4 (minerviniFavorites.ts)
    (NULL, 'SYSTEM', 'minervini-trend-template', '미너비니 추세 템플릿',
     '(((close > ma(50)) AND (ma(50) > ma(150))) AND (ma(150) > ma(200))) AND (close > ma(200))',
     'close < ma(50)',
     'KOSPI', '1d', NULL, NULL, 0, 0, 0, 0, 'LOW', 'ACTIVE', 'ALERT_ONLY'),
    (NULL, 'SYSTEM', 'minervini-near-52w-high', '미너비니 신고가 근접',
     '((close >= donchian_upper(252) * 0.85) AND (close > ma(50))) AND (ma(50) > ma(150))',
     'close < ma(50)',
     'KOSPI', '1d', NULL, NULL, 0, 0, 0, 0, 'LOW', 'ACTIVE', 'ALERT_ONLY'),
    (NULL, 'SYSTEM', 'minervini-vcp-contraction', '미너비니 VCP 수축',
     '(((close > ma(50)) AND (ma(20) > ma(50))) AND (natr(14) < natr(50))) AND (volume < volume_ma(20))',
     'close < ma(20)',
     'KOSPI', '1d', NULL, NULL, 0, 0, 0, 0, 'LOW', 'ACTIVE', 'ALERT_ONLY'),
    (NULL, 'SYSTEM', 'minervini-breakout-volume', '미너비니 돌파 거래량',
     '(((close >= donchian_upper(20)) AND (volume > volume_ma(20) * 1.5)) AND (close > ma(50))) AND (ma(20) > ma(50))',
     'close < donchian_lower(10)',
     'KOSPI', '1d', NULL, NULL, 0, 0, 0, 0, 'LOW', 'ACTIVE', 'ALERT_ONLY')
) AS v (user_id, owner, name, name_ko, dsl_entry, dsl_exit, universe_market, tf,
        take_profit_pct, fixed_stop_loss_pct, ast_depth, node_count, indicator_call_count,
        estimated_eval_time_ms, complexity_class, status, vi_action_policy)
WHERE NOT EXISTS (
    SELECT 1 FROM strategy s WHERE s.owner = 'SYSTEM' AND s.name = v.name
);
