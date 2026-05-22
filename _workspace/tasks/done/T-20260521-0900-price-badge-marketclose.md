---
id: T-20260521-0900-price-badge-marketclose
agent: frontend
model: opus
branch: agent/frontend/price-badge-marketclose
status: DONE
startedAt: 2026-05-21T09:00:25.643+09:00
completedAt: 2026-05-21T00:44:58+09:00
pr: 638
parentPhase: ?
---

# 목표
지연 데이터 라벨 fall-through + KRX 시간외·NXT 미반영 hotfix

## 결과물 체크리스트
- [x] marketHours.ts fall-through fix
- [x] currentKrxSession enum + 시간외·NXT 매트릭스 확장
- [x] marketHours.test.ts 회귀 가드

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-21T00:44:58+09:00 — PR #638 머지 (커밋 c1f37fe5)

## 비고
2026-05-22 orchestrator 일괄 audit — pr 필드 미갱신 stale 잔재 정리.
