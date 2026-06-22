---
id: T-20260622-1814-autopilot-slot-fairness
agent: strategy
model: opus
branch: agent/strategy/autopilot-slot-fairness
status: DONE
startedAt: 2026-06-22T18:14:28.436+09:00
completedAt: 2026-06-22T18:31:33.029+09:00
pr: #1107
parentPhase: ?
---

# 목표
autopilot paper-parallel 슬롯 공정성 — 실슬롯 starve된 owner=AUTOPILOT BUY를 페이퍼 섀도로 스코어링(실자본 0)해 6가설 전부 학습 신호 누적

## 결과물 체크리스트
- [x] V93 autopilot_paper_shadow 테이블 (+ ROLLBACK 노트)
- [x] AutopilotPaperShadowScorer 순수 스코어러 (strategy-engine)
- [x] starve 블록 hook (recordPaperShadowIfStarved — SHADOWABLE rule, fail-open)
- [x] 스케줄러 점수화 (AutopilotPaperShadowScoringWorker 60s + scoreByStrategy rollup)
- [x] 단위테스트 (scorer 9 + recorder 4)
- [x] 진단·접근 반영 (hypotheses 해소 §·DISCARD-MANIFEST·CHANGELOG)

## 접근 채택
**paper-parallel (권장안 채택)**. starve 된 owner=AUTOPILOT BUY 를 실자본 0 으로 페이퍼 섀도 기록 → 후속 캔들 TP/SL 판정 → 가설별 승률·기대값 누적. 실보유 동시 cap 3 불변. interim 공정배분(슬롯 라운드로빈)은 더 침습적이라 보류 — 페이퍼 스코어링으로 학습 목적 달성.

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-22T18:14:28.446+09:00 — 작업 시작

## 비고
