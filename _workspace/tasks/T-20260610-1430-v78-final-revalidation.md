---
id: T-20260610-1430-v78-final-revalidation
agent: strategy-engine
model: opus
branch: agent/strategy-engine/v78-final-revalidation-r2
status: IN_PROGRESS
startedAt: 2026-06-10T14:30:00+09:00
completedAt:
pr:
parentPhase: ADR 0121 Wave 3
---

# 목표
ADR 0121 LOCK대로 V78 최종 재검증(재실행) — 사이징 정합 FixedFractional(10%/5) + 신 G3(포트폴리오 MDD≤12%)로 V77 6종(H1·H2·H6·V75-1·V75-2·V75-3) 재판정 + 승격/비활성 집행. #873(scale-in 슬롯 환산 연결) 배포 후 방법론 성립.

## 결과물 체크리스트
- [x] 사이징 작동 사전 점검(AllCapital vs FixedFractional 비트 비동일 확인 — 결함 재발 가드)
- [x] 6종 overfit FixedFractional + AllCapital 비교 컬럼 실측
- [x] 레짐 A/B PF 실측(FixedFractional)
- [x] H1 + 승격후보 4종 G5 sensitivity plateau 실측
- [x] V78 Flyway 집행(INSERT 2 H2·H6 + UPDATE 1 H1, V75-2 비활성 유지)
- [x] 시드 검증(라이브 DB 적용 — 활성 5/비활성 1)
- [x] 임시 Strategy row(76~81) 정리(DELETE)
- [x] 리포트 _workspace/strategy/2026-06-10-v78-final-revalidation-report.md + evidence 5건
- [x] CHANGELOG Wave 3 슬롯 in-place
- [ ] :api:check GREEN
- [ ] PR --base main

## 의존성
- ADR 0121 (사이징 정합 + 신 G3 12% LOCK)
- #871 FixedFractional 구현 + #873 scale-in 슬롯 환산 연결(base fa3c4840)

## 마지막 활동
2026-06-10T14:30:00+09:00 — V78 재검증 실행·집행·리포트·CHANGELOG 완료, gradle check 대기

## 비고
사전 점검 통과(H1 AllCap MDD 0.3277 vs Frac 0.0789). 6종 中 5종 신 G3 통과 — H1 active 유지·H2·H6 신규 승격·V75-1/V75-3 active 유지·V75-2 비활성 유지(Frac MDD 12.30%>12%). V78 확정(V77=discovery_scorecard 점유). AI 0·mock 0·엔진 무변경·실주문 0.
