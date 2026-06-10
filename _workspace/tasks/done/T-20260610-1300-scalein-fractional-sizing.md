---
id: T-20260610-1300-scalein-fractional-sizing
agent: strategy
model: opus
branch: hotfix/scalein-fractional-sizing
status: DONE
startedAt: 2026-06-10T13:00:00.000+09:00
completedAt: 2026-06-10T13:30:00.000+09:00
pr: #TBD
parentPhase: ADR 0121 §2-A
---

# 목표
FixedFractional 슬롯 환산의 scale-in 경로 누락 정정 — #871이 `executeBuy`(비-scale-in)만 슬롯 환산하고 `evaluateScaleInExitSlice` 회차 budget을 빠뜨려 scale_in_config 전략에서 FixedFractional이 AllCapital과 비트 동일하게 무력화되던 V78 결함 정정(ADR 0121 §2-A 누락된 쌍둥이 경로 완성).

## 결과물 체크리스트
- [x] `fractionalSlot` 단일 함수 추출 + `buyBudget`·scale-in 회차 budget 두 경로 동일 규칙 공유
- [x] scale-in 회차 budget = 슬롯 base × weight%(AllCapital 회귀 0 · FixedFractional 슬롯 base)
- [x] ADR 0121 §2-A amend(scale-in 경로 포함 명시)
- [x] 테스트 3건(totalSlices=1 executeBuy 등가 · FixedFractional≠AllCapital 결함 재발 가드 · AllCapital totalSlices=3 회귀 0)
- [x] :api:check + 전체 check GREEN + ktlintFormat
- [x] CHANGELOG hotfix row

## 의존성
- ADR 0121 §2-A (#871 구현의 결함 정정)

## 마지막 활동
2026-06-10T13:00:00.000+09:00 — 작업 시작

## 비고
공유 `ScaleInExitCalculator`(strategy-engine 라이브 경로)는 무변경 — 수정이 `JvmBacktestEngine`(백테스트 전용) 내부로 한정. 라이브 자본 흐름 무영향.
