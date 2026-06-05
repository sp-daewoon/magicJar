---
id: T-20260606-0301-ob-engulfing-presets
agent: frontend
model: opus
branch: agent/frontend/ob-engulfing-presets
status: DONE
startedAt: 2026-06-06T03:01:26.444+09:00
completedAt: 2026-06-06T03:08:43.060+09:00
pr: #757
parentPhase: ?
---

# 목표
OB 장악형 프리셋 2종 + detection/range 파라미터 노출

## 결과물 체크리스트
- [x] smartMoneyPresets 2종
- [x] 파라미터 노출 검증
- [x] parser 문자열 인자 지원
- [x] 테스트

## 의존성
- backend strategy-engine 에이전트가 order_block detection/range 파라미터 병렬 구현 중 (계약 확정: detection ∈ {bos(default), engulfing}, range ∈ {wick(default), body})

## 마지막 활동
2026-06-06T03:01:26.457+09:00 — 작업 시작

## 비고
- 위치 기반 직렬화 계약: params key 순서 swingLookback → searchLookback → mitigationPct → detection → range
- 직렬화 예: order_block(20, 20, 100, engulfing, body)
- add-only — 기존 프리셋 4종·기존 동작 무변경, backend *.kt 0 touch
