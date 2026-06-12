---
id: T-20260612-1354-cancel-odno-mapping
agent: backend-core
model: opus
branch: hotfix/cancel-odno-mapping
status: DONE
startedAt: 2026-06-12T13:54:31.721+09:00
completedAt: 2026-06-12T14:00:28.606+09:00
pr: #930
parentPhase: ?
---

# 목표
F7 — KIS 취소 신규 ODNO 매핑 부재로 인한 외부 주문 오인 흡수 차단 (OODER_NO fallback)

## 결과물 체크리스트
- [x] KisExecutionWsListener cntgYn=1 originalOdno fallback 조회
- [x] 취소 통보 유령 PREINSERT 0 단위 테스트
- [x] 진짜 외부 주문 흡수 회귀 0 테스트
- [x] gradle check+ktlint GREEN

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-12T13:54:31.730+09:00 — 작업 시작

## 비고
