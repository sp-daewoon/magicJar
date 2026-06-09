---
id: T-20260609-1407-market-daily-backfill-completion-gap-awa
agent: market-data
model: opus
branch: hotfix/market-daily-backfill-completion-gap-aware
status: DONE
startedAt: 2026-06-09T14:07:24.383+09:00
completedAt: 2026-06-09T14:19:30.308+09:00
pr: #842
parentPhase: ?
---

# 목표
R-BF progress 완료판정 결함 fix — isSymbolComplete OR→gap-aware(over-skip 닫기) + KIS-unavailable 무한 재fetch cooldown 가드(over-fetch 닫기) + audit entity_id varchar(40) overflow 정정

## 결과물 체크리스트
- [ ] isSymbolComplete gap-aware 교체
- [ ] attempted-no-advance cooldown 마킹(audit 기반·Flyway 0)
- [ ] audit entity_id ≤40 정정
- [ ] 단위테스트 5종 추가
- [ ] :consumer:check GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-09T14:07:24.392+09:00 — 작업 시작

## 비고
