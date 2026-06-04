---
id: T-20260604-1705-backfill-rate-limit-safety
agent: market-data
model: opus
branch: agent/market-data/backfill-rate-limit-safety
status: DONE
startedAt: 2026-06-04T17:05:49.672+09:00
completedAt: 2026-06-04T17:18:33.599+09:00
pr: #721
parentPhase: ?
---

# 목표
캔들 백필 rate-limit safety — KIS 폭주·전건실패 무한재시도 차단

## 결과물 체크리스트
- [x] 회복불가 갭 영구 마킹
- [x] 백필 갭 상한 cap
- [x] 동시 백필 직렬화·throttle
- [x] 회귀 테스트
- [x] CHANGELOG row

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-04T17:05:49.684+09:00 — 작업 시작

## 비고
