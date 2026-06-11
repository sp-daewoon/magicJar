---
id: T-20260611-1437-position-source-trigger
agent: backend-core
model: opus
branch: hotfix/position-source-dimension-trigger-key
status: DONE
startedAt: 2026-06-11T14:37:17.124+09:00
completedAt: 2026-06-11T15:05:00+09:00
pr: 923
parentPhase: ?
---

# 목표
원장 귀속 D1 source정합+D2 trigger키 hotfix (REAL canary 1일차 라이브 결함)

## 결과물 체크리스트
- [x] fix — D1(watcher source 조회 + ledger 3쿼리 mode 차원 + reconcileDrift 권위행 부재 가드) · D2(consumer entity·adapter order_trigger/trigger_ref_id 매핑)
- [x] 테스트 — watcher D1 + drift D1 + rebuild D1·D2 + adapter D2 round-trip + 기존 suite mode 인자 정합
- [x] PR — #923

## 의존성
- ADR 0115 §2-P2-⑥ (OrderTrigger 키 사슬) · Flyway V80 (order_trigger·trigger_ref_id 컬럼, 기존)

## 마지막 활동
2026-06-11 — D1·D2 fix + 테스트 + ./gradlew check GREEN

## 비고
- 수동 SQL backfill(REAL 286·287) PR 본문 명기 — orchestrator 배포 시 적용.
- 배포는 orchestrator가 15:30 장 마감 후 수행 (본 임무는 push·PR까지).
