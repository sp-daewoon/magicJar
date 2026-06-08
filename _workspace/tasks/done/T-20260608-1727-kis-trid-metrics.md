---
id: T-20260608-1727-kis-trid-metrics
agent: backend-core
model: opus
branch: agent/backend-core/kis-trid-metrics
status: DONE
startedAt: 2026-06-08T17:27:34.043+09:00
completedAt: 2026-06-08T17:41:52.830+09:00
pr: #827
parentPhase: ?
---

# 목표
KIS API tr_id별 호출 통계 metric + Grafana 패널 (ADR 0116 §2-E 연장)

## 결과물 체크리스트
- [x] port trId add-only
- [x] 3 adapter kis_call_total 계측
- [x] ~28 어댑터 trId 전달
- [x] Grafana 패널
- [x] 단위테스트
- [x] CHANGELOG

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-06-08T17:27:34.054+09:00 — 작업 시작

## 비고
