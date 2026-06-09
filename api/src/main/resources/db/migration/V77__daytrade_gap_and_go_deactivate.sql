-- V77: 단타 발굴 프리셋 재검증 — DAYTRADE_GAP_AND_GO 비활성 (클린 MDD 게이트 적용)
--
-- 출처: _workspace/strategy/2026-06-10-v77-preset-revalidation-report.md (task T-20260610-0344).
-- 엔진 결함 2건 fix(#862 백테스트 equity mark-to-market · #861 donchian 현재봉 제외) 배포 후,
-- V76 검증을 풀 게이트(G3 MDD≤8% 실제 적용)로 재검증한 결과 반영.
--
-- ★ 재검증 핵심(실측, 80 유동주 · IS 2021-01~2024-05 / OOS 2024-06~2026-06):
--   V76은 MDD가 cash-only 아티팩트(≈100%)라 G3를 proxy로 우회한 채 H1을 승격했다.
--   #862 fix로 클린 MDD가 복원되자 H1 OOS MDD = 35.07%(G3 한도 8%의 4배 초과).
--   H1은 G1(OOS +3.37)·G2(398거래)·G5(TP·SL 양축 plateau)·G8(레짐 PF 1.19/1.43)을 모두 통과하나
--   하드 게이트 G3 단독 실패 → 승격 근거 소멸. 민감도 grid 전 점에서 MDD 33~37%로 구조적(튜닝 무관).
--
-- ★ 정직 LOCK: 이전 사이클 승격(V76)을 클린 게이트가 부정할 때 고수하지 않고 비활성화한다.
--   builtin row는 삭제하지 않고 is_active=FALSE만(역사 evidence + ON CONFLICT 재배포 안정성 — V76 V75-2 선례).
--
-- ★ 신규 승격 0건: 재검증 6종(H1·H2·H6·V75-1·V75-2·V75-3) 전부 클린 MDD G3 탈락. 억지 승격 0 LOCK.
--   H2 VOLUME_BREAKOUT·H6 RANGE_BREAKOUT — #861 donchian fix로 처음 매칭(0→798·790거래)됐으나
--   레짐-A PF<1(0.94·0.945) + MDD 21~26%로 G8·G3 동시 탈락. → V77 신규 INSERT 0.

UPDATE strategy_research_candidate
SET is_active = FALSE,
    market_fit = '[비활성 — 2026-06-10 V77 재검증] 백테스트 MDD 측정 결함이 고쳐진 뒤(클린 mark-to-market) '
        || '검증 구간(OOS 2024-06~2026-06, 유동주 80종) 최대낙폭이 약 35%로 측정되어 단타 자본보호 한도(8%)를 크게 초과했습니다. '
        || '검증 구간 수익·승률·양 레짐 우위·파라미터 안정성은 통과했으나 낙폭 한도 미달로 비활성화했습니다(재검증 리포트 §3-1). '
        || '낙폭은 현 백테스트의 풀집중 사이징에 기인하며, 사이징/게이트 정합 재설계 후 재검증 대상입니다.',
    updated_at = NOW()
WHERE candidate_key = 'DAYTRADE_GAP_AND_GO';
