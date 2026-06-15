---
id: T-20260615-1333-kis-rate-limit-hotfix
agent: market-data
model: opus
branch: agent/market-data/kis-rate-limit-hotfix
status: DONE
startedAt: 2026-06-15T13:33:24.397+09:00
completedAt: 2026-06-15T14:03:21.645+09:00
pr: #982
parentPhase: ?
---

# 목표
KIS EGW00201/EGW00215 rate-limit 잔불 진압: watchlist polling 호출량과 scheduler 격리 보강

## 결과물 체크리스트
- [x] 로그 근거 정리
- [x] scheduler 격리 보강
- [x] watchlist polling 호출량 완화
- [x] 회귀 테스트
- [x] 배포 검증

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-15T14:03:21.560+09:00 — PR #981/#982 merge + consumer deploy 후 realtime fresh, EGW00201/EGW00215 미재발, request failure cooldown 동작 확인

## 비고
