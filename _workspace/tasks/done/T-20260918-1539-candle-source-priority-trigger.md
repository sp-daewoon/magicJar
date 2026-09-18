---
id: T-20260918-1539-candle-source-priority-trigger
agent: backend-core
model: opus
branch: agent/backend-core/candle-source-priority-trigger
status: DONE
startedAt: 2026-09-18T15:39:26.434+09:00
completedAt: 2026-09-18T15:44:22.277+09:00
pr: #1201
parentPhase: ?
---

# 목표
source_priority 자동화: DB 트리거(V101)로 신규 candle의 source_priority 자동 채움(NULL 방지, 배치부담0) + CandleSource.fromLabel에 POOL/BF backfill 약어 매핑 보강

## 결과물 체크리스트
- [x] V101 트리거
- [x] fromLabel POOL/BF 보강
- [x] fromLabel 테스트
- [x] 빌드검증

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-09-18T15:39:26.447+09:00 — 작업 시작

## 비고
